package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.entity.projectile.PersistentProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PersistentProjectileEntity.class)
public interface ProjectileRollbackAccess {
    @Invoker("isInGround") boolean rollback$inGround();
}
