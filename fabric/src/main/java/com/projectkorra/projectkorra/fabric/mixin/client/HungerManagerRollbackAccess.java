package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.entity.player.HungerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HungerManager.class)
public interface HungerManagerRollbackAccess {
    @Accessor("exhaustion") float rollback$exhaustion();
    @Accessor("exhaustion") void rollback$exhaustion(float value);
}
