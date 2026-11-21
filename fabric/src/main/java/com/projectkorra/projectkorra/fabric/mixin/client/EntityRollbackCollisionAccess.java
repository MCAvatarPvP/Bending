package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Accesses pure native helpers; it does not inject into ordinary entity movement. */
@Mixin(Entity.class)
public interface EntityRollbackCollisionAccess {
    @Invoker("adjustMovementForCollisions")
    static Vec3d rollback$clip(Vec3d motion, Box bounds, List<VoxelShape> colliders) { throw new AssertionError("Mixin not applied"); }
    @Invoker("collectStepHeights")
    static float[] rollback$stepHeights(Box bounds, List<VoxelShape> colliders, float maximum, float previousY) { throw new AssertionError("Mixin not applied"); }
}
