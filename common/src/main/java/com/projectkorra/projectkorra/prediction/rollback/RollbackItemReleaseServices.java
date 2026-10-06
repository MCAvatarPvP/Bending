package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import java.util.*;

/** Routes release edges to native item services while retaining all other execution phases. */
public final class RollbackItemReleaseServices<E> implements RollbackPlayerExecution.Services<E, Void> {
    public interface Release<S> extends RollbackStateCell<S> { void release(RollbackPlayer player); }
    private final Thread thread = Thread.currentThread();
    private final RollbackPlayerExecution.Services<E, ?> phases;
    private final Release<?> release;
    private Object clock;
    public RollbackItemReleaseServices(RollbackPlayerExecution.Services<E, ?> phases, Release<?> release) {
        this.phases = Objects.requireNonNull(phases); this.release = Objects.requireNonNull(release);
    }
    @Override public void begin(RollbackStep<E> effects) {
        idle();
        if (!RollbackClock.active()) throw new IllegalStateException("Release execution requires simulation time");
        clock = RollbackClock.identity(); phases.begin(Objects.requireNonNull(effects));
    }
    @Override public void action(RollbackPlayer player, RollbackPlayerInput.Edge edge, CommonInputHandler.InputResult result) {
        running(); Objects.requireNonNull(edge); Objects.requireNonNull(result);
        if (edge.action().kind() == RollbackInputActions.Kind.RELEASE_USE_ITEM) {
            if (!result.cancelEvent()) release.release(Objects.requireNonNull(player));
        } else phases.action(player, edge, result);
    }
    @Override public void tickWorld(long tick) { running(); phases.tickWorld(tick); }
    @Override public void end() {
        checkThread(); if (clock == null) return;
        try { phases.end(); } finally { clock = null; }
    }
    @Override public Void captureRollbackState() { idle(); return null; }
    @Override public void restoreRollbackState(Void ignored) { idle(); }
    @Override public Collection<?> rollbackReferences() { idle(); return List.of(phases, release); }
    private void running() { checkThread(); if (clock == null || clock != RollbackClock.identity()) throw new IllegalStateException("No matching release execution clock"); }
    private void idle() { checkThread(); if (clock != null) throw new IllegalStateException("Release execution remains active"); }
    private void checkThread() { if (thread != Thread.currentThread()) throw new IllegalStateException("Release execution crossed threads"); }
}
