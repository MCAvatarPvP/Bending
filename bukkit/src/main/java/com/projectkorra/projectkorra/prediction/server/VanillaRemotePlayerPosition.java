package com.projectkorra.projectkorra.prediction.server;

/**
 * Mirrors the vanilla client position interpolator for one remote player.
 * Packet targets are supplied by the PacketEvents listener exactly as they
 * were sent to that viewer; this class does no server-position sampling.
 */
final class VanillaRemotePlayerPosition {
    static final int INTERPOLATION_STEPS = 3;
    private static final double MAX_INTERPOLATION_DISTANCE_SQUARED = 64.0 * 64.0;
    private static final double PACKET_SCALE = 4096.0;

    private double renderedX;
    private double renderedY;
    private double renderedZ;
    private double previousRenderedX;
    private double previousRenderedY;
    private double previousRenderedZ;
    private double packetBaseX;
    private double packetBaseY;
    private double packetBaseZ;
    private double targetX;
    private double targetY;
    private double targetZ;
    private int interpolationSteps;

    VanillaRemotePlayerPosition(final double x, final double y, final double z) {
        reset(x, y, z);
    }

    void reset(final double x, final double y, final double z) {
        this.renderedX = this.previousRenderedX = this.packetBaseX = this.targetX = x;
        this.renderedY = this.previousRenderedY = this.packetBaseY = this.targetY = y;
        this.renderedZ = this.previousRenderedZ = this.packetBaseZ = this.targetZ = z;
        this.interpolationSteps = 0;
    }

    /** Applies ClientboundMoveEntityPacket after VecDeltaCodec decoding. */
    void relativeMove(final double deltaX, final double deltaY, final double deltaZ) {
        this.packetBaseX = decodeRelative(this.packetBaseX, deltaX);
        this.packetBaseY = decodeRelative(this.packetBaseY, deltaY);
        this.packetBaseZ = decodeRelative(this.packetBaseZ, deltaZ);
        interpolateTo(this.packetBaseX, this.packetBaseY, this.packetBaseZ);
    }

    /** Applies ClientboundEntityPositionSyncPacket. */
    void positionSync(final double x, final double y, final double z) {
        this.packetBaseX = x;
        this.packetBaseY = y;
        this.packetBaseZ = z;
        interpolateTo(x, y, z);
    }

    /** Applies ClientboundTeleportEntityPacket's absolute/relative position. */
    void teleport(final double x, final double y, final double z,
                  final boolean relativeX, final boolean relativeY, final boolean relativeZ) {
        interpolateTo(relativeX ? this.renderedX + x : x,
                relativeY ? this.renderedY + y : y,
                relativeZ ? this.renderedZ + z : z);
    }

    /** Advances the same three-step InterpolationHandler used by RemotePlayer. */
    void tick() {
        this.previousRenderedX = this.renderedX;
        this.previousRenderedY = this.renderedY;
        this.previousRenderedZ = this.renderedZ;
        if (this.interpolationSteps <= 0) return;

        final double alpha = 1.0 / this.interpolationSteps;
        this.renderedX = lerp(alpha, this.renderedX, this.targetX);
        this.renderedY = lerp(alpha, this.renderedY, this.targetY);
        this.renderedZ = lerp(alpha, this.renderedZ, this.targetZ);
        this.interpolationSteps--;
    }

    private void interpolateTo(final double x, final double y, final double z) {
        final boolean sameTarget = this.interpolationSteps > 0
                ? same(this.targetX, x) && same(this.targetY, y) && same(this.targetZ, z)
                : same(this.renderedX, x) && same(this.renderedY, y) && same(this.renderedZ, z);
        if (sameTarget) return;

        final double dx = x - this.renderedX;
        final double dy = y - this.renderedY;
        final double dz = z - this.renderedZ;
        if (dx * dx + dy * dy + dz * dz > MAX_INTERPOLATION_DISTANCE_SQUARED) {
            this.renderedX = this.previousRenderedX = this.targetX = x;
            this.renderedY = this.previousRenderedY = this.targetY = y;
            this.renderedZ = this.previousRenderedZ = this.targetZ = z;
            this.interpolationSteps = 0;
            return;
        }

        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.interpolationSteps = INTERPOLATION_STEPS;
    }

    double x() {
        return this.renderedX;
    }

    double y() {
        return this.renderedY;
    }

    double z() {
        return this.renderedZ;
    }

    double previousX() {
        return this.previousRenderedX;
    }

    double previousY() {
        return this.previousRenderedY;
    }

    double previousZ() {
        return this.previousRenderedZ;
    }

    double sweptMinX() {
        return Math.min(this.previousRenderedX, this.renderedX);
    }

    double sweptMinY() {
        return Math.min(this.previousRenderedY, this.renderedY);
    }

    double sweptMinZ() {
        return Math.min(this.previousRenderedZ, this.renderedZ);
    }

    double sweptMaxX() {
        return Math.max(this.previousRenderedX, this.renderedX);
    }

    double sweptMaxY() {
        return Math.max(this.previousRenderedY, this.renderedY);
    }

    double sweptMaxZ() {
        return Math.max(this.previousRenderedZ, this.renderedZ);
    }

    /** Exact continuous intersection for a linearly interpolated entity box. */
    boolean sweptIntersects(
            final double queryMinX, final double queryMinY, final double queryMinZ,
            final double queryMaxX, final double queryMaxY, final double queryMaxZ,
            final double boxMinOffsetX, final double boxMinOffsetY, final double boxMinOffsetZ,
            final double boxMaxOffsetX, final double boxMaxOffsetY, final double boxMaxOffsetZ) {
        // Minkowski-expand the query by the entity box, then clip the
        // interpolated feet-position segment against that expanded AABB.
        return segmentIntersectsAabb(
                this.previousRenderedX, this.previousRenderedY, this.previousRenderedZ,
                this.renderedX, this.renderedY, this.renderedZ,
                queryMinX - boxMaxOffsetX, queryMinY - boxMaxOffsetY, queryMinZ - boxMaxOffsetZ,
                queryMaxX - boxMinOffsetX, queryMaxY - boxMinOffsetY, queryMaxZ - boxMinOffsetZ);
    }

    int interpolationSteps() {
        return this.interpolationSteps;
    }

    private static double lerp(final double alpha, final double from, final double to) {
        return from + alpha * (to - from);
    }

    private static double decodeRelative(final double base, final double delta) {
        if (delta == 0.0) return base;
        return (Math.round(base * PACKET_SCALE) + Math.round(delta * PACKET_SCALE)) / PACKET_SCALE;
    }

    static boolean segmentIntersectsAabb(
            final double fromX, final double fromY, final double fromZ,
            final double toX, final double toY, final double toZ,
            final double minX, final double minY, final double minZ,
            final double maxX, final double maxY, final double maxZ) {
        double minimumTime = 0.0;
        double maximumTime = 1.0;

        final double deltaX = toX - fromX;
        if (deltaX == 0.0) {
            if (fromX < minX || fromX > maxX) return false;
        } else {
            double first = (minX - fromX) / deltaX;
            double second = (maxX - fromX) / deltaX;
            if (first > second) {
                final double swap = first;
                first = second;
                second = swap;
            }
            minimumTime = Math.max(minimumTime, first);
            maximumTime = Math.min(maximumTime, second);
            if (minimumTime > maximumTime) return false;
        }

        final double deltaY = toY - fromY;
        if (deltaY == 0.0) {
            if (fromY < minY || fromY > maxY) return false;
        } else {
            double first = (minY - fromY) / deltaY;
            double second = (maxY - fromY) / deltaY;
            if (first > second) {
                final double swap = first;
                first = second;
                second = swap;
            }
            minimumTime = Math.max(minimumTime, first);
            maximumTime = Math.min(maximumTime, second);
            if (minimumTime > maximumTime) return false;
        }

        final double deltaZ = toZ - fromZ;
        if (deltaZ == 0.0) {
            return fromZ >= minZ && fromZ <= maxZ;
        }
        double first = (minZ - fromZ) / deltaZ;
        double second = (maxZ - fromZ) / deltaZ;
        if (first > second) {
            final double swap = first;
            first = second;
            second = swap;
        }
        minimumTime = Math.max(minimumTime, first);
        maximumTime = Math.min(maximumTime, second);
        return minimumTime <= maximumTime;
    }

    private static boolean same(final double first, final double second) {
        return Double.compare(first, second) == 0;
    }
}
