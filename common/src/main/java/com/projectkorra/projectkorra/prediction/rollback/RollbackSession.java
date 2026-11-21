package com.projectkorra.projectkorra.prediction.rollback;

import java.util.*;

/**
 * Authenticated input ingress around one server-owned timeline. Receipt records
 * deliberately live outside replay state: rewinding a fight cannot forget inputs
 * already accepted from the network. Construction requires a completed, trusted
 * session-start negotiation; this class does not enable a match or send packets.
 */
public final class RollbackSession<S, E> {
    /** Connection identity must come from the loader's authenticated connection, never payload data. */
    public record Peer(UUID player, Object connection, long clientTickAtStart) {
        public Peer {
            Objects.requireNonNull(player, "player"); Objects.requireNonNull(connection, "connection");
            if (clientTickAtStart < 0) throw new IllegalArgumentException("Client clock anchor");
        }
    }
    public enum Status { ACCEPTED, DUPLICATE, UNKNOWN_CONNECTION, STALE_SESSION, INVALID_TICK, FINALIZED, TOO_FAR_AHEAD, CONFLICTING_INPUT, ACTION_ORDER, CLOSED }
    public record Receipt(Status status, long serverTick) { }
    /** receivedTicks can contain gaps; finalized ticks need no retransmission even when their input never arrived. */
    public record Acknowledgement(UUID session, long headTick, long finalizedTick, long revision, List<Long> receivedTicks) {
        public Acknowledgement { receivedTicks = List.copyOf(receivedTicks); }
    }

    private static final class Member {
        final Peer peer;
        final NavigableMap<Long, RollbackInputPacket> pending = new TreeMap<>();
        long finalizedSequence;
        Member(Peer peer) { this.peer = peer; }
    }
    private final Thread thread = Thread.currentThread();
    private final UUID id;
    private final long seed;
    private final RollbackTimeline<S, RollbackPlayerInput, E> timeline;
    private final IdentityHashMap<Object, Member> connections = new IdentityHashMap<>();
    private final Map<UUID, Member> members = new TreeMap<>();
    private final List<Peer> peers;
    private long publication, publishedHead;
    private long unpublishedCorrection = Long.MAX_VALUE;
    private boolean closed;

    public RollbackSession(UUID id, long seed, RollbackTimeline<S, RollbackPlayerInput, E> timeline, Collection<Peer> peers) {
        this.id = Objects.requireNonNull(id, "session"); this.seed = seed; this.timeline = Objects.requireNonNull(timeline, "timeline");
        var state = timeline.diagnostics();
        if (timeline instanceof RollbackReplicaTimeline<?, ?, ?> replica && replica.replica()) {
            throw new IllegalArgumentException("Server session requires an authority timeline");
        }
        if (state.tick() != 0 || state.queuedInputTicks() != 0 || state.failed()) throw new IllegalArgumentException("Session requires a fresh timeline");
        for (var peer : peers) {
            var member = new Member(peer);
            if (members.putIfAbsent(peer.player(), member) != null || connections.put(peer.connection(), member) != null) {
                throw new IllegalArgumentException("Duplicate session player or connection");
            }
        }
        if (!members.keySet().equals(new HashSet<>(timeline.participants()))) throw new IllegalArgumentException("Session roster does not match simulation");
        this.peers = members.values().stream().map(member -> member.peer).toList();
    }

    public Receipt receive(Object authenticatedConnection, RollbackInputPacket packet) {
        checkThread();
        if (closed) return new Receipt(Status.CLOSED, -1);
        var member = connections.get(authenticatedConnection);
        if (member == null) return new Receipt(Status.UNKNOWN_CONNECTION, -1);
        Objects.requireNonNull(packet, "packet");
        if (!id.equals(packet.session())) return new Receipt(Status.STALE_SESSION, -1);
        final long tick;
        try { tick = Math.subtractExact(packet.clientTick(), member.peer.clientTickAtStart()); }
        catch (ArithmeticException overflow) { return new Receipt(Status.INVALID_TICK, -1); }
        if (tick < 1) return new Receipt(Status.INVALID_TICK, tick);
        var state = timeline.diagnostics();
        if (state.failed()) { close(); return new Receipt(Status.CLOSED, tick); }
        if (tick <= state.confirmedTick()) return new Receipt(Status.FINALIZED, tick);
        if (tick > state.tick() && tick - state.tick() - 1 > timeline.limits().futureTicks()) return new Receipt(Status.TOO_FAR_AHEAD, tick);
        var existing = member.pending.get(tick);
        if (existing != null) return new Receipt(existing.equals(packet) ? Status.DUPLICATE : Status.CONFLICTING_INPUT, tick);
        if (!ordered(member, tick, packet)) return new Receipt(Status.ACTION_ORDER, tick);
        try {
            var result = timeline.submit(member.peer.player(), tick, packet.playerInput(member.peer.player(), seed));
            if (result != RollbackEngine.Submission.ACCEPTED) {
                // The session is the timeline's sole ingress; divergence means another writer bypassed it.
                throw new IllegalStateException("Timeline rejected validated session input: " + result);
            }
            member.pending.put(tick, packet);
            return new Receipt(Status.ACCEPTED, tick);
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    private static boolean ordered(Member member, long tick, RollbackInputPacket packet) {
        if (packet.actions().isEmpty()) return true;
        long lower = member.finalizedSequence, upper = Long.MAX_VALUE;
        boolean hasUpper = false;
        for (var previous : member.pending.headMap(tick, false).descendingMap().values()) {
            if (!previous.actions().isEmpty()) { lower = previous.actions().getLast().sequence(); break; }
        }
        for (var next : member.pending.tailMap(tick, false).values()) {
            if (!next.actions().isEmpty()) { upper = next.actions().getFirst().sequence(); hasUpper = true; break; }
        }
        return packet.actions().getFirst().sequence() > lower && (!hasUpper || packet.actions().getLast().sequence() < upper);
    }

    public RollbackEngine.Update<S, RollbackPlayerInput, E> advance() {
        requireOpen();
        try {
            var update = timeline.advance();
            rememberCorrection(update);
            for (var member : members.values()) {
                var finished = member.pending.headMap(update.confirmed().tick(), true);
                for (var packet : finished.values()) {
                    if (!packet.actions().isEmpty()) member.finalizedSequence = packet.actions().getLast().sequence();
                }
                finished.clear();
            }
            return update;
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    public RollbackEngine.Update<S, RollbackPlayerInput, E> reconcile() {
        requireOpen();
        try { var update = timeline.reconcile(); rememberCorrection(update); return update; }
        catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    public Acknowledgement acknowledgement(Object authenticatedConnection) {
        requireOpen();
        var member = connections.get(authenticatedConnection);
        if (member == null) throw new IllegalArgumentException("Unknown session connection");
        var state = timeline.diagnostics();
        return new Acknowledgement(id, state.tick(), state.confirmedTick(), state.revision(), List.copyOf(member.pending.keySet()));
    }

    /** Ordered publication for all peers. Call after each server tick; send failures stop the session. */
    public RollbackAuthorityUpdate publish() {
        requireOpen();
        try {
            var update = reconcile();
            long head = update.head().tick();
            if (head == 0) throw new IllegalStateException("No combat tick has run");
            long first = Math.min(unpublishedCorrection, publishedHead < head ? publishedHead + 1 : head);
            var history = timeline.frames(first, head).stream().map(RollbackEngine.Frame::inputs).toList();
            var received = new TreeMap<UUID, List<Long>>();
            members.forEach((player, member) -> received.put(player, List.copyOf(member.pending.keySet())));
            var packet = new RollbackAuthorityUpdate(id, Math.incrementExact(publication), update.revision(), head,
                    update.confirmed().tick(), first, history, received);
            publication = packet.publication(); publishedHead = head; unpublishedCorrection = Long.MAX_VALUE;
            return packet;
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    private void rememberCorrection(RollbackEngine.Update<S, RollbackPlayerInput, E> update) {
        if (update.replayedFrom() > 0) unpublishedCorrection = Math.min(unpublishedCorrection, update.replayedFrom());
    }

    /** Stops the whole session. Loader teardown must also restore the agreed normal gameplay path for every participant. */
    public void close() { checkThread(); closed = true; connections.clear(); members.clear(); }
    public boolean closed() { checkThread(); return closed; }
    public UUID id() { return id; }
    /** Trusted loader connection identities, retained for coordinated teardown after close. */
    public List<Peer> peers() { checkThread(); return peers; }
    private void requireOpen() { checkThread(); if (closed) throw new IllegalStateException("Rollback session is closed"); }
    private void checkThread() { if (Thread.currentThread() != thread) throw new IllegalStateException("Rollback session crossed threads"); }
}
