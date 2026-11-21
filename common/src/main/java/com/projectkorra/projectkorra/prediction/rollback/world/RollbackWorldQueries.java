package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.RayTraceResult;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.Objects;
import java.util.function.Predicate;

/** Native block queries composed with Paper's entity-ray selection over logical membership. */
public final class RollbackWorldQueries implements RollbackWorld.Queries {
    public interface Geometry {
        RollbackBlockRay.Hit rayTrace(RollbackBlockStore terrain, RollbackBlockRay ray);
        default RollbackBlockRay.Hit rayTrace(RollbackBlockStore terrain, RollbackBlockRay ray, Entity viewer) {
            throw new IllegalStateException("Entity sight requires native collision context");
        }
        boolean motionBlockingHeight(BlockData data);
    }
    private final Geometry geometry;
    public RollbackWorldQueries(Geometry geometry) { this.geometry = Objects.requireNonNull(geometry, "geometry"); }
    @Override public RollbackBlockRay.Hit rayTraceBlocks(RollbackWorld world, RollbackBlockRay ray) {
        return geometry.rayTrace(world.terrain(), ray);
    }
    @Override public RollbackBlockRay.Hit rayTraceBlocks(RollbackWorld world, RollbackBlockRay ray, Entity viewer) {
        return geometry.rayTrace(world.terrain(), ray, viewer);
    }
    @Override public RollbackBlockStore.Position highestBlock(RollbackWorld world, int x, int z) {
        // Level.getHeight returns the first free Y above the matching heightmap block.
        // Never skip uncaptured cells or substitute a collision-shape solidity test.
        int visited = 0;
        for (int y = world.getMaxHeight() - 1; ; y--) {
            if (++visited > RollbackBlockRay.MAX_VISITED_BLOCKS) throw new IllegalStateException("Height query budget exceeded");
            if (geometry.motionBlockingHeight(world.terrain().cell(new RollbackBlockStore.Position(x, y, z)).data())) {
                return new RollbackBlockStore.Position(x, y + 1, z);
            }
            if (y == world.getMinHeight()) return new RollbackBlockStore.Position(x, y, z);
        }
    }
    @Override public RayTraceResult rayTrace(RollbackWorld world, RollbackWorldRay ray, double raySize, Predicate<Entity> filter) {
        RollbackBlockRay.Hit block = rayTraceBlocks(world, ray.blocks());
        double blockDistance = block == null ? ray.distance() : Math.sqrt(squared(ray.blocks(), new Vector(block.x(), block.y(), block.z())));
        // Floating-point clipping may place a hit a few ulps beyond the nominal end.
        RollbackWorldRay limited = ray.limitedTo(Math.min(ray.distance(), blockDistance));
        var segment = limited.blocks();
        var broadphase = RollbackBoxRay.expanded(new BoundingBox(
                new Vector(Math.min(segment.x(), segment.endX()), Math.min(segment.y(), segment.endY()), Math.min(segment.z(), segment.endZ())),
                new Vector(Math.max(segment.x(), segment.endX()), Math.max(segment.y(), segment.endY()), Math.max(segment.z(), segment.endZ()))), raySize);
        var candidates = world.getNearbyEntities(new BoundingBox(new Vector(broadphase.minX(), broadphase.minY(), broadphase.minZ()),
                new Vector(broadphase.maxX(), broadphase.maxY(), broadphase.maxZ())), filter);
        RayTraceResult nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entity entity : candidates) {
            Vector hit = RollbackBoxRay.trace(RollbackBoxRay.expanded(entity.getBoundingBox(), raySize), limited);
            if (hit == null) continue;
            double distance = squared(segment, hit);
            if (distance < nearestDistance) {
                nearest = new RayTraceResult(hit, entity);
                nearestDistance = distance;
            }
        }
        if (block == null) return nearest;
        return nearest != null && nearestDistance < blockDistance * blockDistance ? nearest : block.result();
    }
    private static double squared(RollbackBlockRay ray, Vector point) {
        double x = point.getX() - ray.x(), y = point.getY() - ray.y(), z = point.getZ() - ray.z();
        return x * x + y * y + z * z;
    }
}
