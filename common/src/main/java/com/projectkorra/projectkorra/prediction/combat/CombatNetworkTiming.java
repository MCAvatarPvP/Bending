package com.projectkorra.projectkorra.prediction.combat;

/** Pure clock rules for the bounded reactive-combat timeline. */
public final class CombatNetworkTiming {
    private static final int TICK_MILLIS = 50;

    private CombatNetworkTiming() {
    }

    /** Converts a Minecraft RTT estimate into its one-way tick budget. */
    public static int oneWayTicks(final int pingMillis, final int hardMaximumTicks) {
        final long ping = Math.max(0L, pingMillis);
        final long ticks = (ping + 2L * TICK_MILLIS - 1L) / (2L * TICK_MILLIS);
        return (int) Math.min(Math.max(0, hardMaximumTicks), ticks);
    }

    /** Minimum one scheduling tick, with three ticks remaining only a cap. */
    public static int responseTicks(final int pingMillis, final int jitterTicks,
                                    final int hardMaximumTicks) {
        final int maximum = Math.max(1, hardMaximumTicks);
        return Math.max(1, Math.min(maximum,
                oneWayTicks(pingMillis, maximum) + Math.max(0, jitterTicks)));
    }

    /**
     * Maps packet-time client ticks without granting more history than the
     * measured one-way latency plus observed arrival jitter.
     */
    public static Sample sample(final long helloClientTick, final long helloServerTick,
                                final long clientTick, final long currentServerTick,
                                final int pingMillis, final int hardMaximumTicks) {
        final int maximum = Math.max(0, hardMaximumTicks);
        final int baseline = oneWayTicks(pingMillis, maximum);
        final long expectedArrival = saturatingAdd(helloServerTick,
                saturatingSubtract(clientTick, helloClientTick));
        final long observedLate = Math.max(0L,
                saturatingSubtract(currentServerTick, expectedArrival));
        final int jitter = (int) Math.min(Math.max(0, maximum - baseline), observedLate);
        final int age = Math.min(maximum, baseline + jitter);
        return new Sample(saturatingSubtract(currentServerTick, age), age, jitter);
    }

    private static long saturatingAdd(final long left, final long right) {
        try {
            return Math.addExact(left, right);
        } catch (final ArithmeticException ignored) {
            return right < 0L ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private static long saturatingSubtract(final long left, final long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (final ArithmeticException ignored) {
            return left < 0L ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    public record Sample(long effectiveTick, int ageTicks, int jitterTicks) {
    }
}
