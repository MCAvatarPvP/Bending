package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.FluidCollisionMode;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.Objects;

/** Keeps the direction even when the query distance is zero or a block stops it at the origin. */
public record RollbackWorldRay(RollbackBlockRay blocks, RollbackEntityBody.Motion direction, double distance) {
    public RollbackWorldRay {
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(direction, "direction");
        if (!Double.isFinite(distance) || distance < 0) throw new IllegalArgumentException("World ray distance");
        double length = direction.x() * direction.x() + direction.y() * direction.y() + direction.z() * direction.z();
        if (Math.abs(length - 1) > 1e-12) throw new IllegalArgumentException("World ray direction must be normalized");
        requireEnd(blocks.x(), blocks.endX(), direction.x(), distance);
        requireEnd(blocks.y(), blocks.endY(), direction.y(), distance);
        requireEnd(blocks.z(), blocks.endZ(), direction.z(), distance);
    }
    public static RollbackWorldRay from(Vector origin, Vector direction, double distance, FluidCollisionMode fluids, boolean ignorePassable) {
        Objects.requireNonNull(origin, "origin"); Objects.requireNonNull(direction, "direction");
        double length = Math.sqrt(direction.getX() * direction.getX() + direction.getY() * direction.getY() + direction.getZ() * direction.getZ());
        if (!Double.isFinite(length) || length == 0) throw new IllegalArgumentException("World ray direction");
        var normalized = new RollbackEntityBody.Motion(direction.getX() / length, direction.getY() / length, direction.getZ() / length);
        return create(origin.getX(), origin.getY(), origin.getZ(), normalized, distance,
                RollbackBlockRay.Fluids.valueOf(Objects.requireNonNull(fluids, "fluids").name()), ignorePassable);
    }
    public RollbackWorldRay limitedTo(double distance) {
        if (distance > this.distance) throw new IllegalArgumentException("Ray limit extends query");
        return create(blocks.x(), blocks.y(), blocks.z(), direction, distance, blocks.fluids(), blocks.ignorePassable());
    }
    public Vector origin() { return new Vector(blocks.x(), blocks.y(), blocks.z()); }
    private static RollbackWorldRay create(double x, double y, double z, RollbackEntityBody.Motion direction,
                                           double distance, RollbackBlockRay.Fluids fluids, boolean ignorePassable) {
        return new RollbackWorldRay(new RollbackBlockRay(x, y, z, x + direction.x() * distance,
                y + direction.y() * distance, z + direction.z() * distance, fluids, ignorePassable), direction, distance);
    }
    private static void requireEnd(double origin, double end, double direction, double distance) {
        double expected = origin + direction * distance;
        if (Math.abs(expected - end) > Math.max(1e-10, Math.ulp(expected) * 4)) throw new IllegalArgumentException("Inconsistent world ray endpoint");
    }
}
