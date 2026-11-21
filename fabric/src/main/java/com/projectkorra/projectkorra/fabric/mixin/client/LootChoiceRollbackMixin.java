package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackLoot;
import net.minecraft.loot.entry.LeafEntry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.loot.entry.LeafEntry$Choice")
abstract class LootChoiceRollbackMixin {
    @Shadow @Final private LeafEntry field_1004;

    @Inject(method = "getWeight", at = @At("HEAD"), cancellable = true)
    private void rollback$paperLuck(float luck, CallbackInfoReturnable<Integer> result) {
        if (FabricRollbackLoot.alternativeLuck()) {
            var entry = (LootEntryRollbackAccess) field_1004;
            result.setReturnValue(FabricRollbackLoot.alternativeWeight(entry.rollback$weight(), entry.rollback$quality(), luck));
        }
    }
}
