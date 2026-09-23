package com.projectkorra.projectkorra.prediction.rollback;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Tick-local output buffer. Replaying replaces the whole buffer for that tick. */
public final class RollbackStep<E> {
    private final long tick;
    private final int maximumEffects;
    private final List<E> effects = new ArrayList<>();
    private boolean finished;

    RollbackStep(final long tick, final int maximumEffects) {
        this.tick = tick;
        this.maximumEffects = maximumEffects;
    }

    public long tick() {
        return tick;
    }

    /** The effect must be an immutable description, not a closure over mutable live state. */
    public void emit(final E effect) {
        if (finished) throw new IllegalStateException("The simulation step has finished");
        if (effects.size() >= maximumEffects) throw new IllegalStateException("Rollback effect budget exceeded");
        effects.add(Objects.requireNonNull(effect, "effect"));
    }

    List<E> finish() {
        finished = true;
        return List.copyOf(effects);
    }
}
