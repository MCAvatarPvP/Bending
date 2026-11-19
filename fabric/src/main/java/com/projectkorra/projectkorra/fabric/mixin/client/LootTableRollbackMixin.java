package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackLoot;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.LootTable;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.function.Consumer;

@Mixin(LootTable.class)
abstract class LootTableRollbackMixin {
    @Inject(method = "processStacks", at = @At("HEAD"), cancellable = true)
    private static void rollback$preserveOverstackedLoot(ServerWorld world, Consumer<ItemStack> consumer,
            CallbackInfoReturnable<Consumer<ItemStack>> result) {
        if (FabricRollbackLoot.unsplit(world)) {
            result.setReturnValue(stack -> {
                if (stack.isItemEnabled(world.getEnabledFeatures())) consumer.accept(stack);
            });
        }
    }
}
