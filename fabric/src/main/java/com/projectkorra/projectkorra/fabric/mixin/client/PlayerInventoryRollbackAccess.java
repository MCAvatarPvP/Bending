package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackInventoryLimit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Paper's inventory limit is set only on the owned replica. Ordinary inventories retain vanilla's 64. */
@Mixin(PlayerInventory.class)
public abstract class PlayerInventoryRollbackAccess implements Inventory, FabricRollbackInventoryLimit {
    @Unique private int rollback$maximumStack = 64;
    @Override @Unique public void rollback$maximumStack(int value) {
        if (value < 1) throw new IllegalArgumentException("Player inventory stack limit");
        rollback$maximumStack = value;
    }
    @Override public int getMaxCountPerStack() { return rollback$maximumStack; }
}
