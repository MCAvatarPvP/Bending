package com.projectkorra.projectkorra.fabric.client.prediction.entity;

/** Pure timing boundary for EarthShard's client-predicted launch movement. */
public final class EarthShardFallingCollisionPolicy {
    public static final long GRACE_NANOS = 300_000_000L;

    private EarthShardFallingCollisionPolicy() {
    }

    public static boolean ignoresBlocks(final String ability,
                                        final long spawnedAtNanos,
                                        final long nowNanos) {
        final long elapsed = nowNanos - spawnedAtNanos;
        return "EarthShard".equalsIgnoreCase(ability)
                && elapsed >= 0L && elapsed < GRACE_NANOS;
    }
}
