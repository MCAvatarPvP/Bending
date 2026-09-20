package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Delivers native impacts only for locally owned prediction projectiles. */
@Mixin(ProjectileEntity.class)
public abstract class ProjectileEntityPredictionMixin {
    @Inject(method = "onCollision", at = @At("HEAD"), cancellable = true)
    private void projectkorra$predictedImpact(HitResult hit, CallbackInfo ci) {
        if (ExactPredictionRuntime.projectileHit((ProjectileEntity) (Object) this, hit)) {
            ci.cancel();
        }
    }
}
