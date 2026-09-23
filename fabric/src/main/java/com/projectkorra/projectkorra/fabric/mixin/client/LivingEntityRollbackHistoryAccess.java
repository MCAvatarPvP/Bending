package com.projectkorra.projectkorra.fabric.mixin.client;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LazyEntityReference;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingEntityRollbackHistoryAccess {
    @Accessor("attackingPlayer") LazyEntityReference<PlayerEntity> rollback$lastHurtByPlayer();
    @Accessor("attackingPlayer") void rollback$lastHurtByPlayer(LazyEntityReference<PlayerEntity> value);
    @Accessor("attackerReference") LazyEntityReference<LivingEntity> rollback$lastHurtByMob();
    @Accessor("attackerReference") void rollback$lastHurtByMob(LazyEntityReference<LivingEntity> value);
    @Accessor("attacking") LivingEntity rollback$lastHurtMob();
    @Accessor("attacking") void rollback$lastHurtMob(LivingEntity value);
    @Accessor("lastDamageSource") DamageSource rollback$lastDamage();
    @Accessor("lastDamageSource") void rollback$lastDamage(DamageSource value);
    @Accessor("piercingCooldowns") Object2LongMap<Entity> rollback$kinetic();
    @Accessor("piercingCooldowns") void rollback$kinetic(Object2LongMap<Entity> value);
}
