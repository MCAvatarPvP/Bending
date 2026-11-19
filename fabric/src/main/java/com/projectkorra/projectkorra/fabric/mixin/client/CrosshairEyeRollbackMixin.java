package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackCrosshair;
import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackSimulatedPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class CrosshairEyeRollbackMixin {
    @Inject(method = "getCameraPosVec", at = @At("HEAD"), cancellable = true)
    private void projectkorra$crosshairEye(float delta, CallbackInfoReturnable<Vec3d> result) {
        if ((Object)this instanceof FabricRollbackSimulatedPlayer player) {
            var eye = FabricRollbackCrosshair.eye(player);
            if (eye != null) result.setReturnValue(eye);
        }
    }
}
