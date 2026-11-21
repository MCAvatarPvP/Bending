package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Box;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.Motion;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackMovementSolver;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackMovementSolver.Colliders;
import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/** Paper's Moonrise collision solver and native step-height collector without a live Level. */
public final class PaperRollbackMovement implements RollbackMovementSolver.Native<Object> {
    private static final MethodHandle HEIGHTS = heights();

    private static MethodHandle heights() {
        try {
            return MethodHandles.privateLookupIn(Entity.class, MethodHandles.lookup()).findStatic(Entity.class, "calculateStepHeights",
                    MethodType.methodType(float[].class, AABB.class, List.class, List.class, float.class, float.class));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Paper step-height collector is unavailable", failure); }
    }
    @Override public Motion clip(Box bounds, Motion requested, Colliders<Object> colliders) {
        Vec3 result = CollisionUtil.performCollisions(new Vec3(requested.x(), requested.y(), requested.z()),
                box(bounds), shapes(colliders), boxes(colliders));
        return new Motion(result.x, result.y, result.z);
    }
    @Override public float[] stepHeights(Box bounds, Colliders<Object> colliders, float maximum, float previousY) {
        try { return (float[]) HEIGHTS.invokeExact(box(bounds), shapes(colliders), boxes(colliders), maximum, previousY); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Native step-height collection failed", failure); }
    }
    private static AABB box(Box value) { return new AABB(value.minX(), value.minY(), value.minZ(), value.maxX(), value.maxY(), value.maxZ()); }
    private static List<AABB> boxes(Colliders<?> colliders) { return colliders.boxes().stream().map(PaperRollbackMovement::box).toList(); }
    @SuppressWarnings("unchecked")
    private static List<VoxelShape> shapes(Colliders<Object> colliders) {
        colliders.shapes().forEach(VoxelShape.class::cast);
        return (List<VoxelShape>) (List<?>) colliders.shapes();
    }
}
