package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.mixin.client.EntityRollbackCollisionAccess;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Box;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.Motion;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackMovementSolver;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackMovementSolver.Colliders;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.util.ArrayList;
import java.util.List;

/** Runs Minecraft's pure collision and step helpers over supplied logical-world shapes. */
public final class FabricRollbackMovement implements RollbackMovementSolver.Native<VoxelShape> {
    @Override public Motion clip(Box bounds, Motion requested, Colliders<VoxelShape> colliders) {
        Vec3d result = EntityRollbackCollisionAccess.rollback$clip(new Vec3d(requested.x(), requested.y(), requested.z()), box(bounds), shapes(colliders));
        return new Motion(result.x, result.y, result.z);
    }
    @Override public float[] stepHeights(Box bounds, Colliders<VoxelShape> colliders, float maximum, float previousY) {
        return EntityRollbackCollisionAccess.rollback$stepHeights(box(bounds), shapes(colliders), maximum, previousY);
    }
    private static net.minecraft.util.math.Box box(Box value) {
        return new net.minecraft.util.math.Box(value.minX(), value.minY(), value.minZ(), value.maxX(), value.maxY(), value.maxZ());
    }
    private static List<VoxelShape> shapes(Colliders<VoxelShape> colliders) {
        List<VoxelShape> result = new ArrayList<>(colliders.size());
        result.addAll(colliders.shapes());
        for (Box value : colliders.boxes()) result.add(VoxelShapes.cuboid(box(value)));
        return result;
    }
}
