package com.projectkorra.projectkorra.prediction.rollback;

import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns a prepared replica's local input, fixed clock mapping and correction delivery. */
public final class RollbackClientRuntime<S, E> implements RollbackStartClientEndpoint.Runtime {
    public record Held(RollbackMovementInput movement, boolean sprinting) {
        public Held { Objects.requireNonNull(movement, "movement"); }
    }
    /** Presentation consumes provisional state separately from the update's once-only finalized effects. */
    public interface Output<S, E> {
        void update(RollbackEngine.Update<S, RollbackPlayerInput, E> update);
        void stop(RollbackStartServerEndpoint.Failure failure);
    }
    private enum Phase { PREPARED, RUNNING, FAILED, STOPPED }
    private final Thread owner = Thread.currentThread();
    private final UUID session, player;
    private final long seed;
    private final RollbackReplicaTimeline<S, RollbackPlayerInput, E> timeline;
    private final RollbackClientReplica<S, E> replica;
    private final Supplier<Held> held;
    private final Consumer<RollbackInputPacket> send;
    private final Output<S, E> output;
    // Network identity and pending physical input must never rewind with the simulation.
    private final ArrayList<RollbackInputPacket.Edge> actions = new ArrayList<>();
    private long sequence, anchor, clientTick;
    private Phase phase = Phase.PREPARED;
    private boolean busy;

    public RollbackClientRuntime(UUID session, UUID player, long seed,
            RollbackReplicaTimeline<S, RollbackPlayerInput, E> timeline, Supplier<Held> held,
            Consumer<RollbackInputPacket> send, Output<S, E> output) {
        this.session = Objects.requireNonNull(session); this.player = Objects.requireNonNull(player); this.seed = seed;
        this.timeline = Objects.requireNonNull(timeline); this.held = Objects.requireNonNull(held);
        this.send = Objects.requireNonNull(send); this.output = Objects.requireNonNull(output);
        replica = new RollbackClientReplica<>(session, player, timeline);
        if (timeline.limits().stepNanos() != 50_000_000L) throw new IllegalArgumentException("Client combat requires 50ms ticks");
    }

    /** Packet-time aim belongs to the edge, independently of the later tick's movement aim. */
    public boolean action(RollbackInputActions.Kind kind, int slot, float yaw, float pitch) {
        return action(kind, slot, yaw, pitch, RollbackInputActions.defaultHand(kind));
    }
    public boolean action(RollbackInputActions.Kind kind, int slot, float yaw, float pitch, RollbackInputActions.Hand hand) {
        checkThread();
        if (phase != Phase.RUNNING) return false;
        try {
            if (actions.size() >= RollbackPlayerInput.MAXIMUM_ACTIONS) throw new IllegalStateException("Local input action budget exceeded");
            var edge = new RollbackInputPacket.Edge(Math.incrementExact(sequence), kind, slot, yaw, pitch, hand);
            actions.add(edge); sequence = edge.sequence();
            return true;
        } catch (RuntimeException | Error failure) { fail(); throw failure; }
    }

    @Override public void start(long tick) {
        enter(Phase.PREPARED);
        try {
            if (tick < 0 || tick == Long.MAX_VALUE) throw new IllegalArgumentException("Client start clock bounds");
            anchor = clientTick = tick; phase = Phase.RUNNING;
            output.update(replica.reconcile());
        } catch (RuntimeException | Error failure) { fail(); throw failure; }
        finally { busy = false; }
    }

    @Override public void tick(long tick) {
        enter(Phase.RUNNING);
        try {
            if (tick != Math.incrementExact(clientTick)) throw new IllegalStateException("Client input clock skipped or reversed");
            long target = Math.subtractExact(tick, anchor);
            var controls = Objects.requireNonNull(held.get(), "held controls");
            var packet = new RollbackInputPacket(session, tick, controls.movement(), controls.sprinting(), actions);
            var admitted = replica.propose(target, packet.playerInput(player, seed));
            if (admitted != RollbackEngine.Submission.ACCEPTED) {
                throw new IllegalStateException("Local input left the retained clock window: " + admitted);
            }
            actions.clear(); clientTick = tick;
            var state = timeline.diagnostics();
            // Never age out an unconfirmed hit to keep a slow/missing server connection moving.
            // Input retains its original tick while waiting; exceeding the bounded window stops.
            var update = state.tick() < target && state.tick() - state.confirmedTick() < timeline.limits().rollbackTicks()
                    ? replica.advance() : replica.reconcile();
            output.update(update);
            if (phase == Phase.RUNNING) send.accept(packet);
        } catch (RuntimeException | Error failure) { fail(); throw failure; }
        finally { busy = false; }
    }

    @Override public void authority(RollbackAuthorityUpdate update) {
        enter(Phase.RUNNING);
        try {
            var corrected = replica.receive(update);
            if (corrected != null) output.update(corrected);
        } catch (RuntimeException | Error failure) { fail(); throw failure; }
        finally { busy = false; }
    }

    @Override public void stop(RollbackStartServerEndpoint.Failure failure) {
        checkThread(); Objects.requireNonNull(failure);
        if (phase == Phase.STOPPED) return;
        phase = Phase.STOPPED; actions.clear();
        output.stop(failure);
    }
    public boolean running() { checkThread(); return phase == Phase.RUNNING; }
    public boolean failed() { checkThread(); return phase == Phase.FAILED || replica.failed(); }
    private void fail() { if (phase != Phase.STOPPED) phase = Phase.FAILED; }
    private void enter(Phase expected) {
        checkThread();
        if (busy || phase != expected) throw new IllegalStateException("Client runtime cannot enter from " + phase);
        busy = true;
    }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Client runtime crossed threads");
    }
}
