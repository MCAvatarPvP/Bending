package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.entity.damage.DamageRecord;
import net.minecraft.entity.damage.DamageTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;

@Mixin(DamageTracker.class)
public interface DamageTrackerRollbackAccess {
    @Accessor("recentDamage") List<DamageRecord> rollback$entries();
    @Accessor("ageOnLastDamage") int rollback$lastDamageTime();
    @Accessor("ageOnLastDamage") void rollback$lastDamageTime(int value);
    @Accessor("ageOnLastAttacked") int rollback$combatStartTime();
    @Accessor("ageOnLastAttacked") void rollback$combatStartTime(int value);
    @Accessor("ageOnLastUpdate") int rollback$combatEndTime();
    @Accessor("ageOnLastUpdate") void rollback$combatEndTime(int value);
    @Accessor("recentlyAttacked") boolean rollback$inCombat();
    @Accessor("recentlyAttacked") void rollback$inCombat(boolean value);
    @Accessor("hasDamage") boolean rollback$takingDamage();
    @Accessor("hasDamage") void rollback$takingDamage(boolean value);
}
