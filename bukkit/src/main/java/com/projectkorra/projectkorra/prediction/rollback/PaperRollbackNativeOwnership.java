package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.network.PacketProcessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * Whole-roster ownership of the two native player tick paths and packet handoff.
 * This does not capture common registries, native values, terrain or plugin tasks.
 * The bootstrap must reserve those too, and install lifecycle event interception.
 */
public final class PaperRollbackNativeOwnership {
    private final Thread owner = Thread.currentThread();
    private final List<ServerPlayer> players;
    private final ServerLevel world;
    private final PaperRollbackEntityTickGate.Lease worldTicks;
    private final List<PaperRollbackConnectionTickGate.Lease> connections;
    private final List<PaperRollbackConnectionTickGate.Lease> acquired = new ArrayList<>();
    private boolean acquiring, started, ready, stopping, restoring, released;

    /** Returns the cleanup handle before any native ownership changes. */
    public static PaperRollbackNativeOwnership prepare(Collection<ServerPlayer> players, Runnable stopBeforeMutation) {
        boundary(); return new PaperRollbackNativeOwnership(List.copyOf(players), Objects.requireNonNull(stopBeforeMutation));
    }
    private PaperRollbackNativeOwnership(List<ServerPlayer> players, Runnable stopBeforeMutation) {
        worldTicks = PaperRollbackEntityTickGate.prepare(players);
        this.players = players; world = players.getFirst().level();
        connections = players.stream().map(player -> PaperRollbackConnectionTickGate.prepare(player, stopBeforeMutation)).toList();
    }
    /** Call at the live tick boundary before world entity iteration. Failure requires cleanup. */
    public void acquire() {
        checkThread();
        if (started || acquiring || stopping || released) throw new IllegalStateException("Native roster already started/closed");
        acquiring = true; started = true;
        try {
            worldTicks.acquire();
            for (var connection : connections) { connection.acquire(); acquired.add(connection); }
        } catch (RuntimeException | Error failure) { stopping = true; throw failure; }
        finally { acquiring = false; }
    }
    /** No snapshot or private simulation may start until every participant is ready. */
    public boolean pollReady(PacketProcessor processor) {
        return pollReady(connection -> connection.pollPacketDrain(processor));
    }
    boolean pollReady(PacketProcessor processor, java.util.concurrent.Executor serverQueue) {
        return pollReady(connection -> connection.pollPacketDrain(processor, serverQueue));
    }
    private boolean pollReady(java.util.function.Predicate<PaperRollbackConnectionTickGate.Lease> poll) {
        checkActive();
        try {
            boolean complete = true;
            for (var connection : acquired) complete &= poll.test(connection);
            requireCurrent();
            ready = complete;
            return complete;
        } catch (RuntimeException | Error failure) { stopping = true; ready = false; throw failure; }
    }
    /** After a failed release, complete any renewed handoffs before retrying cleanup. */
    public boolean pollCleanup(PacketProcessor processor) {
        return pollCleanup(connection -> connection.pollPacketDrain(processor));
    }
    boolean pollCleanup(PacketProcessor processor, java.util.concurrent.Executor serverQueue) {
        return pollCleanup(connection -> connection.pollPacketDrain(processor, serverQueue));
    }
    private boolean pollCleanup(java.util.function.Predicate<PaperRollbackConnectionTickGate.Lease> poll) {
        checkThread();
        if (!stopping || released || restoring) throw new IllegalStateException("Native roster is not awaiting cleanup");
        boolean complete = true;
        for (var connection : acquired) complete &= poll.test(connection);
        return complete;
    }
    public void requireReady() {
        checkActive();
        if (!ready) throw new IllegalStateException("Native roster packet handoff is pending");
        requireCurrent();
    }
    private void requireCurrent() {
        worldTicks.requireCurrent();
        for (var connection : acquired) connection.requireCurrent();
        if (players.stream().anyMatch(player -> player.level() != world || player.isRemoved()
                || player.isPassenger() || !player.getPassengers().isEmpty()))
            throw new IllegalStateException("Native roster changed during ownership");
    }
    /**
     * Restore the whole duel while both native tick paths remain gated. The callback
     * must be idempotent. A failure permanently prevents further simulation through
     * this owner; retry cleanup, never resume the private timeline.
     */
    public void restoreAndRelease(Runnable restore) {
        checkThread(); Objects.requireNonNull(restore);
        if (restoring || acquiring) throw new IllegalStateException("Recursive native roster cleanup");
        if (released) return;
        stopping = true; ready = false; restoring = true;
        try {
            if (!started) {
                worldTicks.restoreAndRelease(() -> { });
            } else if (acquired.isEmpty()) {
                worldTicks.restoreAndRelease(restore);
            } else {
                PaperRollbackConnectionTickGate.restoreAll(acquired, restore,
                        () -> worldTicks.restoreAndRelease(() -> { }));
            }
            for (var connection : connections) if (!acquired.contains(connection)) connection.restoreAndRelease(() -> { });
            released = true;
        } finally { restoring = false; }
    }
    private void checkActive() {
        checkThread();
        if (!started || acquiring || stopping || released || acquired.size() != connections.size())
            throw new IllegalStateException("Native roster is not active");
    }
    private void checkThread() {
        boundary();
        if (Thread.currentThread() != owner) throw new IllegalStateException("Native roster crossed threads");
    }
    private static void boundary() {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Change native roster ownership on the live server tick thread");
    }
}
