package com.projectkorra.projectkorra.prediction.hit;

/** Pure timing rules for validating a client-observed player contact. */
public final class HitRewind {
    public static final int TICK_MILLIS = 50;

    private HitRewind() {
    }

    /**
     * The attacker sees a defender only after both players' one-way network
     * paths have elapsed. Convert their two RTT values into that combined
     * one-way age, with one tick of scheduling/jitter slack.
     */
    public static int combinedRewindTicks(final int attackerPingMillis,
                                          final int defenderPingMillis,
                                          final int hardMaximumTicks) {
        final long combinedRoundTrip = Math.max(0L, attackerPingMillis)
                + Math.max(0L, defenderPingMillis);
        final long combinedOneWayTicks = (combinedRoundTrip + 2L * TICK_MILLIS - 1L)
                / (2L * TICK_MILLIS);
        final long withSchedulingSlack = combinedOneWayTicks + 1L;
        return (int) Math.min(Math.max(0, hardMaximumTicks), withSchedulingSlack);
    }

    /** Maps a client tick into the server history without permitting extra age. */
    public static long mapClientTick(final long helloClientTick,
                                     final long helloServerTick,
                                     final long clientTick,
                                     final long currentServerTick,
                                     final int attackerPingMillis,
                                     final int defenderPingMillis,
                                     final int hardMaximumTicks) {
        final int rewindTicks = combinedRewindTicks(
                attackerPingMillis, defenderPingMillis, hardMaximumTicks);
        final long clockMapped = saturatingAdd(helloServerTick,
                saturatingSubtract(clientTick, helloClientTick));
        final long mapped = saturatingSubtract(clockMapped, rewindTicks);
        return Math.max(currentServerTick - rewindTicks,
                Math.min(currentServerTick, mapped));
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
}
