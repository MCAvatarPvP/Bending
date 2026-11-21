package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;

@Mixin(LivingEntity.class)
public interface LivingEntityRollbackItemAccess {
    @Accessor("activeItemStack") ItemStack rollback$useItem();
    @Accessor("activeItemStack") void rollback$useItem(ItemStack value);
    @Accessor("riptideStack") ItemStack rollback$spinItem();
    @Accessor("riptideStack") void rollback$spinItem(ItemStack value);
    @Accessor("lastEquipmentStacks") Map<EquipmentSlot, ItemStack> rollback$lastEquipment();
}
