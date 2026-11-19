package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.loot.entry.LeafEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LeafEntry.class)
public interface LootEntryRollbackAccess {
    @Accessor("weight") int rollback$weight();
    @Accessor("quality") int rollback$quality();
}
