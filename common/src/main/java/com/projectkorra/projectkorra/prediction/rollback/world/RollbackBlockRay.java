package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.FluidCollisionMode;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.util.RayTraceResult;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.Objects;

/** Detached query and result shared by the native terrain adapters. */
public record RollbackBlockRay(double x, double y, double z, double endX, double endY, double endZ,
                               Fluids fluids, boolean ignorePassable) {
    public static final int MAX_VISITED_BLOCKS = 65_536;
    public enum Fluids { NEVER, SOURCE_ONLY, ALWAYS }

    public RollbackBlockRay {
        requireCoordinate(x); requireCoordinate(y); requireCoordinate(z);
        requireCoordinate(endX); requireCoordinate(endY); requireCoordinate(endZ);
        Objects.requireNonNull(fluids, "fluids");
    }

    public static RollbackBlockRay from(Vector origin, Vector direction, double distance,
                                         FluidCollisionMode fluids, boolean ignorePassable) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(direction, "direction");
        if (!Double.isFinite(distance) || distance < 0) throw new IllegalArgumentException("Ray distance");
        double length = Math.hypot(Math.hypot(direction.getX(), direction.getY()), direction.getZ());
        if (!Double.isFinite(length) || length == 0) throw new IllegalArgumentException("Ray direction");
        return new RollbackBlockRay(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + direction.getX() / length * distance,
                origin.getY() + direction.getY() / length * distance,
                origin.getZ() + direction.getZ() / length * distance,
                Fluids.valueOf(Objects.requireNonNull(fluids, "fluids").name()), ignorePassable);
    }

    public RollbackBlockStore.Position originBlock() {
        return new RollbackBlockStore.Position((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public record Hit(RollbackBlockStore.Position block, double x, double y, double z,
                      BlockFace face, boolean inside) {
        public Hit {
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(face, "face");
            requireCoordinate(x); requireCoordinate(y); requireCoordinate(z);
        }
        public RayTraceResult result() { return new RayTraceResult(new Vector(x, y, z)); }
    }

    private static void requireCoordinate(double value) {
        // Native traversal floors to int coordinates and nudges its endpoints. Leave
        // enough headroom for that nudge so malformed input cannot wrap the traversal.
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE + 1_024.0 || value > Integer.MAX_VALUE - 1_024.0) {
            throw new IllegalArgumentException("Ray coordinate outside native range");
        }
    }
}
