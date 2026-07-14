package com.projectkorra.projectkorra.fabric.mixin;

import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.mc.entity.Projectile;
import com.projectkorra.projectkorra.platform.mc.event.entity.ProjectileHitEvent;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ProjectileEntity.class)
public abstract class ProjectileEntityMixin {

    @Inject(method = "onEntityHit", at = @At("HEAD"), cancellable = true)
    private void projectkorra$onEntityHit(EntityHitResult hitResult, CallbackInfo ci) {
        ProjectileEntity projectile = (ProjectileEntity) (Object) this;
        if (!(projectile.getEntityWorld() instanceof ServerWorld)) {
            return;
        }

        ProjectileHitEvent event = createEvent(projectile);
        if (event == null) {
            return;
        }
        event.setHitEntity(FabricMC.entity(hitResult.getEntity()));
        Platform.events().call(event);
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "onBlockHit", at = @At("HEAD"), cancellable = true)
    private void projectkorra$onBlockHit(BlockHitResult hitResult, CallbackInfo ci) {
        ProjectileEntity projectile = (ProjectileEntity) (Object) this;
        if (!(projectile.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }

        ProjectileHitEvent event = createEvent(projectile);
        if (event == null) {
            return;
        }
        event.setHitBlock(FabricMC.block(world, hitResult.getBlockPos()));
        Platform.events().call(event);
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    private ProjectileHitEvent createEvent(ProjectileEntity projectile) {
        if (!(FabricMC.entity(projectile) instanceof Projectile commonProjectile)) {
            return null;
        }
        ProjectileHitEvent event = new ProjectileHitEvent();
        event.setEntity(commonProjectile);
        return event;
    }
}
