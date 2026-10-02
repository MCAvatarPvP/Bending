package com.projectkorra.projectkorra.prediction.rollback;

import java.util.Objects;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One prepared/active client session, including cancellation after start handoff. */
public final class RollbackStartClientEndpoint {
    public interface Runtime {
        void start(long clientTick);
        void tick(long clientTick);
        void authority(RollbackAuthorityUpdate update);
        void stop(RollbackStartServerEndpoint.Failure failure);
    }
    private final Thread owner = Thread.currentThread();
    private final Consumer<RollbackStartPacket.Message> send;
    private Entry entry;
    private int packets;

    public RollbackStartClientEndpoint(Consumer<RollbackStartPacket.Message> send) { this.send = Objects.requireNonNull(send); }

    public void prepare(UUID session, long tick, int timeoutTicks, BooleanSupplier ready, Runtime runtime) {
        checkThread(); Objects.requireNonNull(runtime);
        if (entry != null) throw new IllegalStateException("Client rollback session already owned");
        entry = new Entry(session, new RollbackClientStart(session, tick, timeoutTicks, ready), ready, runtime);
    }

    public void receive(RollbackStartPacket.Message message, long tick) {
        checkThread(); RollbackStartPacket.requireDirection(message, RollbackStartPacket.Direction.SERVER_TO_CLIENT);
        if (packets >= 32) return;
        packets++;
        Entry current = entry;
        if (current == null) {
            if (message instanceof RollbackStartNegotiation.Probe) send.accept(new RollbackStartPacket.Abort(message.session(), message.challenge(), RollbackStartPacket.AbortReason.INCOMPATIBLE));
            return;
        }
        if (current.ended) return;
        if (!current.session.equals(message.session()) || current.challenge != null && !current.challenge.equals(message.challenge())) return;
        if (message instanceof RollbackStartPacket.Abort abort) {
            current.fail(new RollbackStartServerEndpoint.Failure(abort.reason(), "Server stopped rollback", null), false); return;
        }
        if (current.started) return;
        if (message instanceof RollbackStartNegotiation.Probe) current.challenge = message.challenge();
        try {
            var response = current.barrier.receive(message, tick);
            if (response != null) send.accept(response);
        } catch (RuntimeException | Error failure) {
            if (current.ended) throw failure;
            current.fail(new RollbackStartServerEndpoint.Failure(RollbackStartPacket.AbortReason.STATE_CHANGED, "Client start message failed", failure), true);
        }
    }

    public void tick(long tick) {
        checkThread(); packets = 0;
        Entry current = entry;
        if (current == null || current.ended) return;
        try {
            if (!current.ready.getAsBoolean() || !current.barrier.poll(tick)) {
                current.fail(new RollbackStartServerEndpoint.Failure(RollbackStartPacket.AbortReason.STATE_CHANGED, "Client readiness or clock changed", null), true); return;
            }
            if (current.started) {
                if (tick == current.runtimeTick) return;
                if (tick != Math.incrementExact(current.runtimeTick)) throw new IllegalStateException("Nonsequential client runtime tick");
                current.runtime.tick(tick); current.runtimeTick = tick;
            } else if (current.barrier.start(tick)) {
                current.started = true; current.runtimeTick = tick;
                current.runtime.start(tick);
                while (!current.ended && !current.authority.isEmpty()) current.runtime.authority(current.authority.remove());
            }
        } catch (RuntimeException | Error failure) {
            if (current.ended) throw failure;
            current.fail(new RollbackStartServerEndpoint.Failure(RollbackStartPacket.AbortReason.STATE_CHANGED, "Client runtime/start tick failed", failure), true);
        }
    }

    public boolean ownsSession() { checkThread(); return entry != null; }
    public boolean acceptsAuthority() { checkThread(); return entry != null && !entry.ended; }
    public void authority(RollbackAuthorityUpdate update) {
        checkThread();
        Entry current = entry;
        if (current == null || current.ended || !current.session.equals(update.session())) return;
        try {
            if (current.started) current.runtime.authority(update);
            else {
                if (current.authority.size() >= 8) throw new IllegalStateException("Authority arrived too far ahead of negotiated start");
                current.authority.add(update);
            }
        } catch (RuntimeException | Error failure) {
            if (current.ended) throw failure;
            current.fail(new RollbackStartServerEndpoint.Failure(RollbackStartPacket.AbortReason.STATE_CHANGED, "Client authority update failed", failure), true);
        }
    }
    public void stop(RollbackStartPacket.AbortReason reason) {
        stop(new RollbackStartServerEndpoint.Failure(reason, "Client session stopped", null));
    }
    public void stop(RollbackStartServerEndpoint.Failure failure) {
        checkThread();
        if (entry != null) entry.fail(Objects.requireNonNull(failure), true);
    }

    /** Only after the owner has repaired a failed stop; successful cleanup releases automatically. */
    public void finishStop(UUID session) {
        checkThread();
        if (entry == null || !entry.session.equals(session)) return;
        if (!entry.ended) throw new IllegalStateException("Client rollback session is still running");
        entry = null;
    }

    private final class Entry {
        final UUID session;
        final RollbackClientStart barrier;
        final BooleanSupplier ready;
        final Runtime runtime;
        final ArrayDeque<RollbackAuthorityUpdate> authority = new ArrayDeque<>();
        UUID challenge;
        boolean started, ended;
        long runtimeTick;
        Entry(UUID session, RollbackClientStart barrier, BooleanSupplier ready, Runtime runtime) {
            this.session = session; this.barrier = barrier; this.ready = ready; this.runtime = runtime;
        }
        void fail(RollbackStartServerEndpoint.Failure failure, boolean notify) {
            if (ended) return;
            ended = true;
            authority.clear();
            if (!started) barrier.abort();
            Throwable problem = failure.cause();
            if (notify && challenge != null) {
                try { send.accept(new RollbackStartPacket.Abort(session, challenge, failure.reason())); }
                catch (RuntimeException | Error sendFailure) {
                    if (problem == null) problem = sendFailure; else if (problem != sendFailure) problem.addSuppressed(sendFailure);
                }
            }
            runtime.stop(new RollbackStartServerEndpoint.Failure(failure.reason(), failure.detail(), problem));
            if (entry == this) entry = null;
        }
    }
    private void checkThread() { if (Thread.currentThread() != owner) throw new IllegalStateException("Client start endpoint crossed threads"); }
}
