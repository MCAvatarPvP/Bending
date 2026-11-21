package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.mixin.client.VoxelShapeRollbackAccess;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackVoxelIntersections;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.EntityView;

import java.util.ArrayList;
import java.util.List;

/** Paper's query geometry for the private replica. Native entity eligibility remains authoritative. */
final class FabricRollbackEntityCollisions {
    private static final double EPSILON = RollbackVoxelIntersections.EPSILON;
    private FabricRollbackEntityCollisions() { }

    static List<VoxelShape> collisions(EntityView world, Entity except, Box bounds) {
        if (RollbackVoxelIntersections.empty(box(bounds))) return List.of();
        var result = new ArrayList<VoxelShape>();
        for (var other : world.getOtherEntities(except, bounds.expand(-EPSILON), entity -> !entity.isSpectator())) {
            if (except == null ? other.isCollidable(null) : except.collidesWith(other)) result.add(VoxelShapes.cuboid(other.getBoundingBox()));
        }
        return List.copyOf(result);
    }

    static boolean unobstructed(EntityView world, Entity except, VoxelShape shape) {
        if (shape.isEmpty()) return true;
        var grid = grid(shape); boolean single = RollbackVoxelIntersections.single(grid);
        var bounds = shape.getBoundingBox();
        for (var other : world.getOtherEntities(except, single ? bounds.expand(-EPSILON) : bounds)) {
            if (other.isRemoved() || !other.intersectionChecked || except != null && other.isConnectedThroughVehicle(except)) continue;
            var box = box(other.getBoundingBox());
            if (single || !RollbackVoxelIntersections.empty(box) && RollbackVoxelIntersections.intersects(grid, box)) return false;
        }
        return true;
    }

    private static RollbackVoxelIntersections.Grid grid(VoxelShape shape) {
        var voxels = ((VoxelShapeRollbackAccess) shape).rollback$voxels();
        var x = shape.getPointPositions(Direction.Axis.X); var y = shape.getPointPositions(Direction.Axis.Y); var z = shape.getPointPositions(Direction.Axis.Z);
        return new RollbackVoxelIntersections.Grid() {
            @Override public int size(int axis) { return switch (axis) { case 0 -> voxels.getXSize(); case 1 -> voxels.getYSize(); case 2 -> voxels.getZSize(); default -> throw new IllegalArgumentException("Axis"); }; }
            @Override public double coordinate(int axis, int index) { return switch (axis) { case 0 -> x.getDouble(index); case 1 -> y.getDouble(index); case 2 -> z.getDouble(index); default -> throw new IllegalArgumentException("Axis"); }; }
            @Override public boolean full(int x, int y, int z) { return voxels.contains(x, y, z); }
        };
    }
    private static RollbackBlockStore.Box box(Box value) { return new RollbackBlockStore.Box(value.minX, value.minY, value.minZ, value.maxX, value.maxY, value.maxZ); }
}
