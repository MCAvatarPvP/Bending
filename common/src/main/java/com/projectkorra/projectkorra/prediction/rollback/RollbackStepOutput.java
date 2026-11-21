package com.projectkorra.projectkorra.prediction.rollback;

import java.util.Objects;
import java.util.function.Consumer;

/** Stable native-output target whose only destination is the currently bound provisional tick. */
public final class RollbackStepOutput<E> implements Consumer<E>, RollbackStateCell<Void> {
    private final Thread thread = Thread.currentThread();
    private Binding binding;

    /** The execution owner closes this binding in its finally path, including partial tick failure. */
    public Binding open(RollbackStep<E> step) {
        idle();
        Object clock = RollbackClock.identity();
        if (clock == null) throw new IllegalStateException("Output binding requires a simulation tick");
        binding = new Binding(Objects.requireNonNull(step), clock);
        return binding;
    }
    @Override public void accept(E output) {
        checkThread();
        if (binding == null || RollbackClock.identity() != binding.clock)
            throw new IllegalStateException("Output has no matching simulation tick");
        binding.step.emit(Objects.requireNonNull(output));
    }
    public final class Binding implements AutoCloseable {
        private final RollbackStep<E> step;
        private final Object clock;
        private boolean closed;
        private Binding(RollbackStep<E> step, Object clock) { this.step = step; this.clock = clock; }
        @Override public void close() {
            checkThread();
            if (closed) return;
            if (binding != this) throw new IllegalStateException("Output bindings closed out of order");
            binding = null; closed = true;
        }
    }
    @Override public Void captureRollbackState() { idle(); return null; }
    @Override public void restoreRollbackState(Void ignored) { idle(); }
    private void idle() { checkThread(); if (binding != null) throw new IllegalStateException("Output tick is still bound"); }
    private void checkThread() { if (Thread.currentThread() != thread) throw new IllegalStateException("Simulation output crossed threads"); }
}
