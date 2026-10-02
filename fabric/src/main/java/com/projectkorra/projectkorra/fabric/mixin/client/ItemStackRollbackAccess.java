package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.component.MergedComponentMap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Supports Paper's in-place item API only on privately owned rollback stacks. */
@Mixin(ItemStack.class)
public interface ItemStackRollbackAccess {
    @Accessor("item") Item rollback$item();
    @Mutable @Accessor("item") void rollback$item(Item item);
    @Accessor("components") MergedComponentMap rollback$components();
    @Mutable @Accessor("components") void rollback$components(MergedComponentMap components);
}
