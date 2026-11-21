package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import net.minecraft.entity.damage.DamageSource;

/** Access for owned rollback bodies; does not inject into ordinary damage execution. */
@Mixin(LivingEntity.class)
public interface LivingEntityRollbackCombatAccess {
    @Accessor("lastDamageTaken")
    float rollback$lastDamageTaken();
    @Accessor("lastDamageTaken") void rollback$lastDamageTaken(float value);
    @Accessor("lastDamageTime") void rollback$lastDamageTime(long value);
    @Invoker("tryUseDeathProtector") boolean rollback$tryUseDeathProtector(DamageSource source);
    @Invoker("playThornsSound") void rollback$playThornsSound(DamageSource source);
}
