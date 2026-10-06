package com.projectkorra.projectkorra.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackPlayerRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Substitute the owned camera's input pose before native third-person collision, without moving its focused entity. */
@Mixin(Camera.class)
public abstract class CameraRollbackMixin {
    @Unique private FabricRollbackPlayerRenderer.CameraView projectkorra$view;
    @Unique private float projectkorra$delta;

    @WrapMethod(method = "update")
    private void projectkorra$cameraUpdate(World area, Entity entity, boolean thirdPerson, boolean inverse, float delta, Operation<Void> original) {
        if (projectkorra$view != null) throw new IllegalStateException("Reentrant rollback camera update");
        projectkorra$view = FabricRollbackPlayerRenderer.camera((Camera) (Object) this, entity, area); projectkorra$delta = delta;
        try { original.call(area, entity, thirdPerson, inverse, delta); }
        finally { projectkorra$view = null; }
    }
    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setPos(DDD)V"))
    private void projectkorra$position(Camera camera, double x, double y, double z, Operation<Void> original) {
        if (projectkorra$view != null) { var point = projectkorra$view.eyePosition(projectkorra$delta); x = point.x; y = point.y; z = point.z; }
        original.call(camera, x, y, z);
    }
    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setPos(Lnet/minecraft/util/math/Vec3d;)V"))
    private void projectkorra$vehiclePosition(Camera camera, Vec3d position, Operation<Void> original) {
        original.call(camera, projectkorra$view == null ? position : projectkorra$view.eyePosition(projectkorra$delta));
    }
    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;hasVehicle()Z"))
    private boolean projectkorra$vehicle(Entity entity, Operation<Boolean> original) {
        // The captured position and distance already include the private vehicle; old live interpolation must not offset them.
        return projectkorra$view == null && original.call(entity);
    }
    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getYaw(F)F"))
    private float projectkorra$yaw(Entity entity, float delta, Operation<Float> original) {
        return projectkorra$view == null || projectkorra$view.localAim() ? original.call(entity, delta) : projectkorra$view.yaw(delta);
    }
    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getPitch(F)F"))
    private float projectkorra$pitch(Entity entity, float delta, Operation<Float> original) {
        return projectkorra$view == null || projectkorra$view.localAim() ? original.call(entity, delta) : projectkorra$view.pitch(delta);
    }
    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;clipToSpace(F)F"))
    private float projectkorra$distance(Camera camera, float distance, Operation<Float> original) {
        return original.call(camera, projectkorra$view == null ? distance : projectkorra$view.player().cameraMotion().distance());
    }
    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;isSleeping()Z"))
    private boolean projectkorra$sleeping(LivingEntity entity, Operation<Boolean> original) {
        return projectkorra$view == null ? original.call(entity) : projectkorra$view.player().living().pose() == EntityPose.SLEEPING;
    }
    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getSleepingDirection()Lnet/minecraft/util/math/Direction;"))
    private Direction projectkorra$sleepDirection(LivingEntity entity, Operation<Direction> original) {
        return projectkorra$view == null ? original.call(entity) : projectkorra$view.player().living().sleepingDirection();
    }
}
