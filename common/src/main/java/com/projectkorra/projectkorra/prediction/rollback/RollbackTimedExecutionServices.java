package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import java.util.*;

/** Connects native world time to the shared execution boundary before callbacks and input. */
public final class RollbackTimedExecutionServices<E> implements RollbackPlayerExecution.Services<E, Void> {
    public interface WorldTime<S> extends RollbackStateCell<S> { void advanceTick(long tick); }
    private final Thread thread = Thread.currentThread();
    private final WorldTime<?> world;
    private final RollbackPlayerExecution.Services<E, ?> phases;
    private Object clock;
    public RollbackTimedExecutionServices(WorldTime<?> world, RollbackPlayerExecution.Services<E, ?> phases) {
        this.world = Objects.requireNonNull(world); this.phases = Objects.requireNonNull(phases);
    }
    @Override public void begin(RollbackStep<E> effects) {
        idle();
        if (!RollbackClock.active()) throw new IllegalStateException("Execution requires a simulation clock");
        clock = RollbackClock.identity();
        world.advanceTick(Objects.requireNonNull(effects).tick());
        phases.begin(effects);
    }
    @Override public void action(RollbackPlayer player, RollbackPlayerInput.Edge edge, CommonInputHandler.InputResult result) {
        running(); phases.action(player, edge, result);
    }
    @Override public void tickWorld(long tick) { running(); phases.tickWorld(tick); }
    @Override public void end() {
        checkThread();
        if (clock == null) return;
        try { phases.end(); } finally { clock = null; }
    }
    @Override public Void captureRollbackState() { idle(); return null; }
    @Override public void restoreRollbackState(Void ignored) { idle(); }
    @Override public Collection<?> rollbackReferences() { idle(); return List.of(world, phases); }
    private void running() { checkThread(); if (clock == null || clock != RollbackClock.identity()) throw new IllegalStateException("No matching execution clock"); }
    private void idle() { checkThread(); if (clock != null) throw new IllegalStateException("Execution phase still active"); }
    private void checkThread() { if (thread != Thread.currentThread()) throw new IllegalStateException("Execution services crossed threads"); }
}
