package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.network.PacketProcessor;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.function.Predicate;

/** Coordinates live roster gates; capture/terrain and client bootstrap are owned by the caller. */
public final class PaperRollbackLiveOwnership {
    private final Thread owner = Thread.currentThread();
    private final PaperRollbackLifecycle lifecycle;
    private final Runnable stopBeforeMutation;
    private final Set<UUID> roster;
    private final RollbackLiveScheduler.Lease tasks;
    private final RollbackLiveOwnership.Lease common;
    private final PaperRollbackNativeOwnership nativeState;
    private final int taskCapacity;
    private PaperRollbackLifecycle.Lease events;
    private RollbackTaskBindings.Capture capturedTasks;
    private boolean started, acquiring, ready, running, stopping, restoring, released, nativeCleanup;

    /** Read-only preparation: retain the returned owner before calling acquire. */
    public static PaperRollbackLiveOwnership prepare(Collection<ServerPlayer> players,
            RollbackLiveScheduler scheduler, Predicate<RollbackLiveScheduler.Work> selectTasks,
            int taskCapacity, PaperRollbackLifecycle lifecycle, Runnable stopBeforeMutation) {
        boundary();
        return new PaperRollbackLiveOwnership(List.copyOf(players), scheduler, selectTasks,
                taskCapacity, lifecycle, stopBeforeMutation);
    }
    private PaperRollbackLiveOwnership(List<ServerPlayer> players, RollbackLiveScheduler scheduler,
            Predicate<RollbackLiveScheduler.Work> selectTasks, int taskCapacity,
            PaperRollbackLifecycle lifecycle, Runnable stopBeforeMutation) {
        if (taskCapacity < 1) throw new IllegalArgumentException("Task reservation capacity");
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.stopBeforeMutation = Objects.requireNonNull(stopBeforeMutation);
        this.taskCapacity = taskCapacity;
        var ids = new HashSet<UUID>();
        for (var player : players) if (!ids.add(player.getUUID())) throw new IllegalArgumentException("Duplicate roster player");
        roster = Set.copyOf(ids);
        common = RollbackLiveOwnership.prepare(roster);
        tasks = Objects.requireNonNull(scheduler).prepare(selectTasks);
        nativeState = PaperRollbackNativeOwnership.prepare(players, stopBeforeMutation);
    }
    /** Freeze callbacks before common ownership can suppress their effects. Failure requires abort. */
    public void acquire() {
        checkThread();
        if (started || stopping || released) throw new IllegalStateException("Live roster already started/closed");
        started = true; acquiring = true;
        try {
            events = lifecycle.reserve(roster, stopBeforeMutation);
            capturedTasks = tasks.freeze(taskCapacity);
            common.acquire();
            nativeState.acquire();
        } catch (RuntimeException | Error failure) { stopping = true; throw failure; }
        finally { acquiring = false; }
    }
    public boolean pollReady(PacketProcessor processor) {
        return pollReady(() -> nativeState.pollReady(processor));
    }
    boolean pollReady(PacketProcessor processor, java.util.concurrent.Executor serverQueue) {
        return pollReady(() -> nativeState.pollReady(processor, serverQueue));
    }
    private boolean pollReady(java.util.function.BooleanSupplier poll) {
        active();
        try {
            tasks.requireCurrent(); common.requireCurrent();
            if (!poll.getAsBoolean()) return false;
            tasks.requireCurrent(); common.requireCurrent();
            events.suspendNativeEffects(); ready = true; return true;
        } catch (RuntimeException | Error failure) { stopping = true; ready = false; throw failure; }
    }
    public RollbackTaskBindings.Capture capturedTasks() { requireReady(); return capturedTasks; }
    public void requireReady() {
        active();
        if (!ready) throw new IllegalStateException("Live roster handoff pending");
        tasks.requireCurrent(); common.requireCurrent(); nativeState.requireReady();
    }
    /** After private simulation starts, outgoing callbacks are mandatory for restoration. */
    public void beginSimulation() { requireReady(); running = true; }

    /** Resume original callbacks only when no private simulation has run. */
    public void abort(Runnable restoreState) {
        checkThread(); Objects.requireNonNull(restoreState);
        if (running) throw new IllegalStateException("Running rollback requires outgoing task bindings");
        restore(null, restoreState);
    }
    /** State restoration must include common/native values and terrain, and be idempotent. */
    public void restoreAndRelease(RollbackTaskBindings outgoing, Runnable restoreState) {
        checkThread(); Objects.requireNonNull(outgoing); Objects.requireNonNull(restoreState);
        if (!running) throw new IllegalStateException("Private simulation has not started");
        restore(outgoing, restoreState);
    }
    private void restore(RollbackTaskBindings outgoing, Runnable restoreState) {
        if (restoring || acquiring) throw new IllegalStateException("Recursive live roster cleanup");
        if (released) return;
        stopping = true; ready = false; restoring = true;
        try {
            Runnable commit = () -> {
                nativeCleanup = true;
                nativeState.restoreAndRelease(restoreState);
                nativeCleanup = false;
                common.restoreAndRelease(() -> { });
                if (events != null) events.release();
            };
            if (outgoing == null) tasks.restore(commit); else tasks.replace(outgoing, commit);
            released = true;
        } finally { restoring = false; }
    }
    /** Failed native detach can require another packet barrier before retrying restoration. */
    public boolean pollCleanup(PacketProcessor processor) {
        checkCleanup(); return !nativeCleanup || nativeState.pollCleanup(processor);
    }
    boolean pollCleanup(PacketProcessor processor, java.util.concurrent.Executor serverQueue) {
        checkCleanup(); return !nativeCleanup || nativeState.pollCleanup(processor, serverQueue);
    }
    private void checkCleanup() {
        checkThread();
        if (!stopping || released || restoring) throw new IllegalStateException("Live roster is not awaiting cleanup");
    }
    private void active() {
        checkThread();
        if (!started || acquiring || stopping || released) throw new IllegalStateException("Live roster is not active");
    }
    private void checkThread() {
        boundary();
        if (Thread.currentThread() != owner) throw new IllegalStateException("Live roster crossed threads");
    }
    private static void boundary() {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Change live roster ownership on the server tick thread");
    }
}
