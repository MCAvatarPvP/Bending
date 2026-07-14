package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ability.CoreAbility;

import java.util.function.ToLongFunction;

/**
 * Aligns delayed authoritative lifetimes with an already-running prediction.
 */
public final class PredictionTiming {
    private static volatile ToLongFunction<CoreAbility> provider = ignored -> 0L;

    private PredictionTiming() {
    }

    public static void install(ToLongFunction<CoreAbility> newProvider) {
        provider = newProvider == null ? ignored -> 0L : newProvider;
    }

    public static void clear(ToLongFunction<CoreAbility> expected) {
        if (provider == expected) provider = ignored -> 0L;
    }

    public static long alignDuration(CoreAbility ability, long durationMillis) {
        if (durationMillis <= 1L || ability == null) return durationMillis;
        long compensation = compensation(ability);
        return Math.max(1L, durationMillis - Math.min(durationMillis - 1L, compensation));
    }

    /** Aligns charge/duration clocks on a newly-created server ability. */
    public static void alignStart(CoreAbility ability) {
        if (ability != null) ability.alignPredictedStart(compensation(ability));
    }

    private static long compensation(CoreAbility ability) {
        if (ability == null) return 0L;
        try {
            return Math.max(0L, provider.applyAsLong(ability));
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
