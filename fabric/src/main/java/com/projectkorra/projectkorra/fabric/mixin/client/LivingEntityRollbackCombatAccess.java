package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to native damage history; no change to ordinary damage behavior. */
@Mixin(LivingEntity.class)
public interface LivingEntityRollbackCombatAccess {
    @Accessor("lastDamageTaken")
    float rollback$lastDamageTaken();
}
