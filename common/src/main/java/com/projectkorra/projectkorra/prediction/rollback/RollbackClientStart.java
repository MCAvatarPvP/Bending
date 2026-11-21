package com.projectkorra.projectkorra.prediction.rollback;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Client clock agreement over an already loaded private snapshot. Never starts legacy prediction. */
public final class RollbackClientStart {
    public enum Phase { PREPARED, PROBED, SCHEDULED, COMMITTED, STARTED, ABORTED }
    private final Thread owner = Thread.currentThread();
    private final UUID session;
    private final BooleanSupplier ready;
    private final long deadline;
    private Phase phase = Phase.PREPARED;
    private RollbackStartNegotiation.ClockReply reply;
    private RollbackStartNegotiation.Scheduled acknowledgement;
    private long lastTick;

    public RollbackClientStart(UUID session, long clientTick, int timeoutTicks, BooleanSupplier ready) {
        this.session = Objects.requireNonNull(session); this.ready = Objects.requireNonNull(ready);
        if (clientTick < 0 || timeoutTicks < 4 || timeoutTicks > 1200) throw new IllegalArgumentException("Client start bounds");
        lastTick = clientTick; deadline = Math.addExact(clientTick, timeoutTicks);
        if (!ready.getAsBoolean()) throw new IllegalStateException("Private client bootstrap is not ready");
    }

    /** Returns an acknowledgement to send, or null for stale/one-way messages. After
     * STARTED, control belongs to the active runtime rather than this start barrier. */
    public RollbackStartPacket.Message receive(RollbackStartPacket.Message message, long clientTick) {
        checkThread();
        RollbackStartPacket.requireDirection(message, RollbackStartPacket.Direction.SERVER_TO_CLIENT);
        if (!session.equals(message.session()) || reply != null && !reply.challenge().equals(message.challenge())
                || phase == Phase.STARTED || phase == Phase.ABORTED) return null;
        return switch (message) {
            case RollbackStartNegotiation.Probe probe -> probe(probe, clientTick);
            case RollbackStartNegotiation.Schedule schedule -> schedule(schedule, clientTick);
            case RollbackStartPacket.Commit commit -> { commit(commit.schedule(), clientTick); yield null; }
            case RollbackStartPacket.Abort ignored -> { abort(); yield null; }
            default -> throw new IllegalArgumentException("Unexpected server start message");
        };
    }

    public RollbackStartNegotiation.ClockReply probe(RollbackStartNegotiation.Probe probe, long clientTick) {
        if (!session.equals(probe.session())) throw new IllegalArgumentException("Stale start session");
        requireReady(clientTick);
        if (reply != null) {
            if (reply.challenge().equals(probe.challenge())) return reply;
            abort(); throw new IllegalArgumentException("Start challenge changed");
        }
        if (phase != Phase.PREPARED) throw new IllegalStateException("Client is not prepared");
        reply = new RollbackStartNegotiation.ClockReply(session, probe.challenge(), clientTick);
        phase = Phase.PROBED;
        return reply;
    }

    public RollbackStartNegotiation.Scheduled schedule(RollbackStartNegotiation.Schedule schedule, long clientTick) {
        if (!session.equals(schedule.session()) || reply == null || !reply.challenge().equals(schedule.challenge())) throw new IllegalArgumentException("Stale start schedule");
        requireReady(clientTick);
        if (acknowledgement != null) {
            if (acknowledgement.schedule().equals(schedule)) return acknowledgement;
            abort(); throw new IllegalArgumentException("Start schedule changed");
        }
        if (phase != Phase.PROBED || schedule.clientTick() <= clientTick || schedule.clientTick() > deadline) {
            abort(); throw new IllegalStateException("Client cannot meet the scheduled start");
        }
        acknowledgement = new RollbackStartNegotiation.Scheduled(schedule, clientTick);
        phase = Phase.SCHEDULED;
        return acknowledgement;
    }

    public void commit(RollbackStartNegotiation.Schedule schedule, long clientTick) {
        if (!session.equals(schedule.session())) throw new IllegalArgumentException("Stale start commit");
        requireReady(clientTick);
        if (acknowledgement == null || !acknowledgement.schedule().equals(schedule)
                || phase != Phase.SCHEDULED && phase != Phase.COMMITTED || clientTick >= schedule.clientTick()) {
            abort(); throw new IllegalStateException("Unscheduled or late start commit");
        }
        phase = Phase.COMMITTED;
    }

    /** True once, on the agreed local tick; the caller now hands over to its private runtime. */
    public boolean start(long clientTick) {
        if (!poll(clientTick)) return false;
        if (phase != Phase.COMMITTED || clientTick != acknowledgement.schedule().clientTick()) return false;
        phase = Phase.STARTED;
        return true;
    }

    public boolean poll(long clientTick) {
        checkThread();
        if (phase == Phase.ABORTED) return false;
        if (clientTick < lastTick) { abort(); throw new IllegalArgumentException("Client clock moved backwards"); }
        lastTick = clientTick;
        if (phase == Phase.STARTED) return true;
        try { if (!ready.getAsBoolean()) abort(); }
        catch (RuntimeException | Error failure) { abort(); throw failure; }
        if (clientTick > deadline || acknowledgement != null && clientTick > acknowledgement.schedule().clientTick()
                || phase == Phase.SCHEDULED && clientTick == acknowledgement.schedule().clientTick()) abort();
        return phase != Phase.ABORTED;
    }
    public void abort() {
        checkThread();
        if (phase == Phase.STARTED) throw new IllegalStateException("Stop the running client session through its runtime");
        phase = Phase.ABORTED;
    }
    public Phase phase() { checkThread(); return phase; }
    private void requireReady(long tick) { if (!poll(tick)) throw new IllegalStateException("Client start aborted"); }
    private void checkThread() { if (Thread.currentThread() != owner) throw new IllegalStateException("Client start crossed threads"); }
}
