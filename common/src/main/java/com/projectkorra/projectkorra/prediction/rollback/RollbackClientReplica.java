package com.projectkorra.projectkorra.prediction.rollback;

import java.util.*;

/**
 * Reconciles a prepared client simulation from the server's ordered publication stream.
 * Unacknowledged local inputs stay speculative until accepted or the server finalizes
 * their tick. This owner never accepts a client-supplied damage result or position.
 */
public final class RollbackClientReplica<S, E> {
    private final Thread owner = Thread.currentThread();
    private final UUID session, localPlayer;
    private final RollbackReplicaTimeline<S, RollbackPlayerInput, E> timeline;
    private final Set<UUID> roster;
    private final NavigableMap<Long, RollbackPlayerInput> proposed = new TreeMap<>();
    private final NavigableMap<Long, Map<UUID, RollbackPlayerInput>> authoritative = new TreeMap<>();
    private Map<UUID, Set<Long>> received = Map.of();
    private RollbackAuthorityUpdate latest;
    private boolean failed;

    public RollbackClientReplica(UUID session, UUID localPlayer, RollbackReplicaTimeline<S, RollbackPlayerInput, E> timeline) {
        this.session = Objects.requireNonNull(session); this.localPlayer = Objects.requireNonNull(localPlayer);
        this.timeline = Objects.requireNonNull(timeline); roster = Set.copyOf(timeline.participants());
        var state = timeline.diagnostics();
        if (!timeline.replica() || !roster.contains(localPlayer) || state.tick() != 0 || state.queuedInputTicks() != 0 || state.failed()) {
            throw new IllegalArgumentException("Client replica requires a fresh prepared prediction timeline");
        }
    }

    /** Retain exactly the local intent sent for this mapped simulation tick, including its server-derived seeds. */
    public RollbackEngine.Submission propose(long tick, RollbackPlayerInput input) {
        check(); Objects.requireNonNull(input);
        var previous = proposed.get(tick);
        if (previous != null) return previous.equals(input) ? RollbackEngine.Submission.DUPLICATE : RollbackEngine.Submission.CONFLICTING_INPUT;
        if (received.getOrDefault(localPlayer, Set.of()).contains(tick)) throw new IllegalStateException("Authority received an input this replica did not send");
        var result = timeline.correct(localPlayer, tick, input);
        if (result == RollbackEngine.Submission.ACCEPTED || result == RollbackEngine.Submission.DUPLICATE) {
            proposed.put(tick, input);
            return RollbackEngine.Submission.ACCEPTED;
        }
        return result;
    }

    public RollbackEngine.Update<S, RollbackPlayerInput, E> advance() {
        check();
        // Reaching capacity is a pacing condition, not permission to discard unconfirmed history.
        return timeline.advance();
    }

    /** Apply locally collected late input without advancing the negotiated clock. */
    public RollbackEngine.Update<S, RollbackPlayerInput, E> reconcile() {
        check();
        return timeline.reconcile();
    }

    /** Returns null for a stale session or already delivered publication. Invalid authority stops this replica. */
    public RollbackEngine.Update<S, RollbackPlayerInput, E> receive(RollbackAuthorityUpdate update) {
        check(); Objects.requireNonNull(update);
        if (!session.equals(update.session())) return null;
        if (latest != null && update.publication() < latest.publication()) return null;
        try { return apply(update); }
        catch (RuntimeException | Error failure) { failed = true; throw failure; }
    }

    private RollbackEngine.Update<S, RollbackPlayerInput, E> apply(RollbackAuthorityUpdate update) {
        if (latest != null && update.publication() == latest.publication()) {
            if (!latest.equals(update)) throw new IllegalArgumentException("Conflicting authority publication");
            return null;
        }
        long previousHead = latest == null ? 0 : latest.headTick();
        long previousFinalized = latest == null ? 0 : latest.finalizedTick();
        if (update.publication() != (latest == null ? 1 : Math.incrementExact(latest.publication()))
                || update.headTick() < previousHead || update.finalizedTick() < previousFinalized
                || update.firstTick() > previousHead + 1 || update.firstTick() < previousFinalized
                || latest != null && update.revision() < latest.revision()
                || !update.receivedTicks().keySet().equals(roster)) {
            throw new IllegalArgumentException("Authority stream skipped or reversed its timeline/roster");
        }
        var receipts = new TreeMap<UUID, Set<Long>>();
        update.receivedTicks().forEach((player, ticks) -> receipts.put(player, Set.copyOf(ticks)));
        received.forEach((player, ticks) -> {
            for (long tick : ticks) if (tick > update.finalizedTick() && !receipts.get(player).contains(tick)) {
                throw new IllegalArgumentException("Authority forgot accepted input");
            }
        });
        var inputs = new TreeMap<>(authoritative);
        long frameTick = update.firstTick();
        for (var frame : update.frames()) {
            var previous = inputs.get(frameTick);
            if (previous != null) {
                for (var player : roster) {
                    if ((latest != null && update.revision() == latest.revision()
                            || received.getOrDefault(player, Set.of()).contains(frameTick)) && !previous.get(player).equals(frame.get(player))) {
                        throw new IllegalArgumentException("Authority rewrote accepted input or changed history without a revision");
                    }
                }
            }
            if (frameTick == previousFinalized && !timeline.frames(frameTick, frameTick).getFirst().inputs().equals(frame)) {
                throw new IllegalArgumentException("Authority changed finalized input");
            }
            inputs.put(frameTick++, frame);
        }
        long initialHead = timeline.diagnostics().tick();
        if (update.headTick() - previousFinalized > RollbackAuthorityUpdate.MAXIMUM_FRAMES) {
            throw new IllegalArgumentException("Authority catch-up exceeds retained history budget");
        }
        // Validate the entire update before applying any correction.
        for (long tick = previousFinalized + 1; tick <= update.headTick(); tick++) {
            var frame = inputs.get(tick);
            if (frame == null) throw new IllegalArgumentException("Missing authority input frame");
            var proposal = proposed.get(tick);
            if (proposal != null && receipts.get(localPlayer).contains(tick) && !proposal.equals(frame.get(localPlayer))) {
                throw new IllegalArgumentException("Authority changed a received local input");
            }
        }
        for (var entry : inputs.subMap(previousFinalized, false, Math.min(initialHead, update.headTick()), true).entrySet()) {
            correct(entry.getKey(), entry.getValue(), update, receipts);
        }
        var effects = new ArrayList<RollbackEngine.Effect<E>>();
        var result = timeline.confirm(Math.min(initialHead, update.finalizedTick()));
        effects.addAll(result.finalizedEffects());
        long replayedFrom = result.replayedFrom();
        for (long tick = initialHead + 1; tick <= update.headTick(); tick++) {
            correct(tick, inputs.get(tick), update, receipts);
            result = timeline.advance();
            if (result.replayedFrom() > 0) replayedFrom = replayedFrom < 0 ? result.replayedFrom() : Math.min(replayedFrom, result.replayedFrom());
            result = timeline.confirm(Math.min(tick, update.finalizedTick()));
            effects.addAll(result.finalizedEffects());
        }
        proposed.headMap(update.finalizedTick(), true).clear();
        inputs.headMap(update.finalizedTick(), true).clear();
        authoritative.clear(); authoritative.putAll(inputs); received = receipts; latest = update;
        return new RollbackEngine.Update<>(result.head(), result.confirmed(), result.revision(), replayedFrom, effects);
    }

    private void correct(long tick, Map<UUID, RollbackPlayerInput> frame, RollbackAuthorityUpdate update, Map<UUID, Set<Long>> receipts) {
        for (var player : roster) {
            RollbackPlayerInput input = frame.get(player);
            if (player.equals(localPlayer) && tick > update.finalizedTick() && !receipts.get(player).contains(tick)) {
                var local = proposed.floorEntry(tick);
                if (local != null && local.getKey() > update.finalizedTick() && !receipts.get(player).contains(local.getKey())) {
                    input = local.getKey() == tick ? local.getValue() : local.getValue().predict();
                }
            }
            var result = timeline.correct(player, tick, input);
            if (result != RollbackEngine.Submission.ACCEPTED && result != RollbackEngine.Submission.DUPLICATE) {
                throw new IllegalStateException("Client timeline rejected authority input: " + result);
            }
        }
    }

    public long authoritativeHead() { check(); return latest == null ? 0 : latest.headTick(); }
    public boolean failed() { return failed || timeline.diagnostics().failed(); }
    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Client replica crossed threads");
        if (failed()) throw new IllegalStateException("Client replica failed; stop its session");
    }
}
