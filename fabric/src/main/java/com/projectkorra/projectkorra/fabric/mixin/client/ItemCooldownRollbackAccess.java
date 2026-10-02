package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;

@Mixin(ItemCooldownManager.class)
public interface ItemCooldownRollbackAccess {
    @Accessor("entries") Map<Identifier, Object> rollback$entries();
    @Accessor("tick") int rollback$tick();
    @Accessor("tick") void rollback$tick(int value);
}
