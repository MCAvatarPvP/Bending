package com.projectkorra.projectkorra.prediction.movement;

/** Exact validation rules for velocity-ownership metadata. */
public final class VelocityReceiptPolicy {
    private static final double NETWORK_LIMIT = 3.9;
    private static final double NETWORK_TOLERANCE = 0.001;

    private VelocityReceiptPolicy() {
    }

    public static boolean accepts(final boolean locallyOwned, final long actionSequence,
                                  final int impulseOrdinal, final int targetEntityId) {
        return (!locallyOwned || actionSequence > 0L)
                && impulseOrdinal > 0 && targetEntityId >= 0;
    }

    /**
     * Pairs authenticated ownership metadata with the vanilla packet emitted
     * for that write. The announced vector is clamped and quantized by
     * Minecraft before it reaches the client, so compare within one network
     * velocity unit instead of requiring bit equality.
     */
    public static boolean matchesPacket(final double announcedX, final double announcedY,
                                        final double announcedZ, final double packetX,
                                        final double packetY, final double packetZ) {
        final double dx = clamp(announcedX) - packetX;
        final double dy = clamp(announcedY) - packetY;
        final double dz = clamp(announcedZ) - packetZ;
        return Double.isFinite(dx) && Double.isFinite(dy) && Double.isFinite(dz)
                && dx * dx + dy * dy + dz * dz
                <= NETWORK_TOLERANCE * NETWORK_TOLERANCE;
    }

    /**
     * A self-owned push of the local player is always executed by the client
     * common runtime. Losing its retained mutation must not turn the delayed
     * vanilla echo into a second push. Remote targets still require vanilla's
     * packet when their movement was intentionally not predicted.
     */
    public static boolean suppressesMissingMutation(final boolean selfOwned,
                                                     final boolean targetsLocalPlayer) {
        return selfOwned && targetsLocalPlayer;
    }

    private static double clamp(final double value) {
        return Math.max(-NETWORK_LIMIT, Math.min(NETWORK_LIMIT, value));
    }
}
