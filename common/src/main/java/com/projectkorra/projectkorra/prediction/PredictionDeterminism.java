package com.projectkorra.projectkorra.prediction;

import java.util.Random;
import java.util.UUID;

/** Shared action seed for gameplay-affecting random choices during exact prediction. */
public final class PredictionDeterminism {
    private static final ThreadLocal<Long> ACTION = new ThreadLocal<>();

    private PredictionDeterminism() {
    }

    public static void run(final long actionSequence, final Runnable task) {
        final Long previous = ACTION.get();
        if (actionSequence > 0L) ACTION.set(actionSequence);
        try {
            task.run();
        } finally {
            if (previous == null) ACTION.remove();
            else ACTION.set(previous);
        }
    }

    public static Random random(final UUID owner, final String scope) {
        final Long sequence = ACTION.get();
        if (sequence == null || sequence <= 0L || owner == null) return new Random();
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
