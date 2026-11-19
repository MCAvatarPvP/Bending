package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.ProjectileDeflection;
import net.minecraft.util.hit.BlockHitResult;
import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackProjectiles;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Delivers native impacts only for locally owned prediction projectiles. */
@Mixin(ProjectileEntity.class)
public abstract class ProjectileEntityPredictionMixin {
    @Unique private boolean projectkorra$cancelledRollbackBlock;

    @Inject(method = "hitOrDeflect", at = @At("HEAD"), cancellable = true)
    private void projectkorra$rollbackHit(HitResult hit, CallbackInfoReturnable<ProjectileDeflection> result) {
        if (!FabricRollbackProjectiles.active()) return;
        boolean cancelled = FabricRollbackProjectiles.cancelled((ProjectileEntity)(Object)this, hit);
        projectkorra$cancelledRollbackBlock = cancelled && hit.getType() == HitResult.Type.BLOCK;
        if (cancelled && !projectkorra$cancelledRollbackBlock) result.setReturnValue(ProjectileDeflection.NONE);
    }

    @Inject(method = "onBlockHit", at = @At("HEAD"), cancellable = true)
    private void projectkorra$rollbackBlock(BlockHitResult hit, CallbackInfo callback) {
        if (FabricRollbackProjectiles.active() && projectkorra$cancelledRollbackBlock) callback.cancel();
    }

    @Inject(method = "onCollision", at = @At("HEAD"), cancellable = true)
    private void projectkorra$predictedImpact(HitResult hit, CallbackInfo ci) {
        if (FabricRollbackProjectiles.active()) return;
        if (ExactPredictionRuntime.projectileHit((ProjectileEntity) (Object) this, hit)) {
            ci.cancel();
        }
    }
}
