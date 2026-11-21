package com.projectkorra.projectkorra.prediction.rollback;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Authority tick owner: publish the whole roster before finalized effects or match results. */
public final class RollbackServerRuntime<S, E> implements RollbackStartServerEndpoint.Runtime {
    public interface Transport {
        RollbackIngress.Registration enroll(RollbackSession<?, ?> session, Consumer<RollbackIngress.Registration> stopped);
        /** Must publish the session update to every enrolled peer or throw. */
        void publish(RollbackSession<?, ?> session);
    }
    public interface Output<S, E> {
        /** Outside replay, after successful authority publication; finalized effects must not be retried. */
        void update(RollbackEngine.Update<S, RollbackPlayerInput, E> update);
        /** Idempotent whole-roster live restoration. Throwing retains the input reservation for retry. */
        void stop(RollbackStartServerEndpoint.Failure failure);
    }
    private enum Phase { PREPARED, RUNNING, FAILED, STOPPED }
    private final Thread owner = Thread.currentThread();
    private final UUID id;
    private final long seed;
    private final RollbackTimeline<S, RollbackPlayerInput, E> timeline;
    private final Transport transport;
    private final Output<S, E> output;
    private final Runnable confirmedResults;
    private RollbackSession<S, E> session;
    private RollbackIngress.Registration registration;
    private Phase phase = Phase.PREPARED;
    private long tick;
    private boolean busy, stopping, restored;

    public RollbackServerRuntime(UUID id, long seed, RollbackTimeline<S, RollbackPlayerInput, E> timeline,
            Transport transport, Output<S, E> output, Runnable confirmedResults) {
        this.id = Objects.requireNonNull(id); this.seed = seed; this.timeline = Objects.requireNonNull(timeline);
        this.transport = Objects.requireNonNull(transport); this.output = Objects.requireNonNull(output);
        this.confirmedResults = Objects.requireNonNull(confirmedResults);
        if (timeline instanceof RollbackReplicaTimeline<?, ?, ?> replica && replica.replica())
            throw new IllegalArgumentException("Authority runtime cannot use a client replica");
        var state = timeline.diagnostics();
        if (state.tick() != 0 || state.queuedInputTicks() != 0 || state.failed() || timeline.limits().stepNanos() != 50_000_000L)
            throw new IllegalArgumentException("Authority requires a fresh combat timeline");
    }

    @Override public void start(RollbackStartNegotiation negotiation, long serverTick) {
        enter(Phase.PREPARED);
        try {
            if (!id.equals(negotiation.sessionId())) throw new IllegalArgumentException("Foreign negotiation");
            session = negotiation.start(serverTick, seed, timeline);
            tick = serverTick; phase = Phase.RUNNING;
            registration = Objects.requireNonNull(transport.enroll(session, value -> {
                registration = value;
                stop(new RollbackStartServerEndpoint.Failure(RollbackStartPacket.AbortReason.STATE_CHANGED,
                        "Authority ingress stopped: " + value.stopReason(), null));
            }));
            if (phase != Phase.RUNNING) {
                registration.stop(RollbackIngress.StopReason.REQUESTED);
                if (restored) registration.finishStop();
            }
        } catch (RuntimeException | Error failure) { fail(); throw failure; }
        finally { busy = false; }
    }

    @Override public void tick(long serverTick) {
        enter(Phase.RUNNING);
        try {
            if (serverTick != Math.incrementExact(tick)) throw new IllegalStateException("Authority clock skipped or reversed");
            var update = session.advance();
            transport.publish(session);
            if (phase != Phase.RUNNING || session.closed()) return;
            output.update(update);
            if (phase != Phase.RUNNING || session.closed()) return;
            confirmedResults.run();
            tick = serverTick;
        } catch (RuntimeException | Error failure) { fail(); throw failure; }
        finally { busy = false; }
    }

    @Override public void stop(RollbackStartServerEndpoint.Failure failure) {
        requireLive(); Objects.requireNonNull(failure);
        if (phase == Phase.STOPPED || stopping) return;
        stopping = true; phase = Phase.FAILED;
        try {
            if (session != null) session.close();
            if (registration != null) registration.stop(RollbackIngress.StopReason.REQUESTED);
            if (!restored) { output.stop(failure); restored = true; }
            if (registration != null) registration.finishStop();
            phase = Phase.STOPPED;
        } finally { stopping = false; }
    }
    private void fail() {
        if (phase != Phase.STOPPED) phase = Phase.FAILED;
        if (session != null) session.close();
    }
    private void enter(Phase expected) {
        requireLive();
        if (busy || phase != expected) throw new IllegalStateException("Authority cannot enter from " + phase);
        busy = true;
    }
    private void requireLive() {
        if (Thread.currentThread() != owner || RollbackDomain.active() || RollbackClock.active())
            throw new IllegalStateException("Authority transport requires the live owning thread");
    }
}
