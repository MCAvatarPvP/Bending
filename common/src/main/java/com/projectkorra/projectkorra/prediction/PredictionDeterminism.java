package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ability.CoreAbility;

import java.util.Random;
import java.util.UUID;

/** Shared action seed for gameplay-affecting random choices during exact prediction. */
public final class PredictionDeterminism {
    private static final ThreadLocal<Long> ACTION = new ThreadLocal<>();
    private static final ThreadLocal<Long> SEED = new ThreadLocal<>();

    private PredictionDeterminism() {
    }

    public static void run(final long actionSequence, final Runnable task) {
        run(actionSequence, actionSequence, task);
    }

    public static void run(final long actionSequence, final long deterministicSeed, final Runnable task) {
        final Long previous = ACTION.get();
        final Long previousSeed = SEED.get();
        if (actionSequence > 0L) ACTION.set(actionSequence);
        if (deterministicSeed > 0L) SEED.set(deterministicSeed);
        try {
            task.run();
        } finally {
            if (previous == null) ACTION.remove();
            else ACTION.set(previous);
            if (previousSeed == null) SEED.remove();
            else SEED.set(previousSeed);
        }
    }

    public static Random random(final UUID owner, final String scope) {
        return random(owner, scope, currentSeed());
    }

    /**
     * Returns the input sequence which owns the current constructor or ability
     * progress callback. Child abilities created after the original input keep
     * their parent's sequence instead of silently falling back to an unrelated
     * JVM random seed.
     */
    public static long currentAction() {
        final Long sequence = ACTION.get();
        if (sequence != null && sequence > 0L) return sequence;
        final CoreAbility ability = AbilityExecutionContext.current();
        return ability == null ? 0L : ability.getPredictionActionSequence();
    }

    /** Gameplay seed inherited independently from each loader's transport ordinal. */
    public static long currentSeed() {
        final Long seed = SEED.get();
        if (seed != null && seed > 0L) return seed;
        final CoreAbility ability = AbilityExecutionContext.current();
        return ability == null ? 0L : ability.getPredictionDeterministicSeed();
    }

    public static Random random(final UUID owner, final String scope, final long actionSequence) {
        if (actionSequence <= 0L || owner == null) return new Random();
        final long sequence = actionSequence;
        long seed = sequence ^ owner.getMostSignificantBits() ^ Long.rotateLeft(owner.getLeastSignificantBits(), 29);
        seed ^= scope == null ? 0L : Integer.toUnsignedLong(scope.hashCode()) * 0x9E3779B97F4A7C15L;
        seed ^= seed >>> 30;
        seed *= 0xBF58476D1CE4E5B9L;
        seed ^= seed >>> 27;
        seed *= 0x94D049BB133111EBL;
        seed ^= seed >>> 31;
        return new Random(seed);
    }
}
