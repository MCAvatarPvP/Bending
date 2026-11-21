package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerEntity.class)
public interface PlayerEntityRollbackItemAccess {
    @Accessor("selectedItem") ItemStack rollback$lastItem();
    @Accessor("selectedItem") void rollback$lastItem(ItemStack value);
}
