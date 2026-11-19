package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.platform.fabric.FabricPredictionMC;
import net.minecraft.entity.AreaEffectCloudEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AreaEffectCloudEntity.class)
abstract class AreaEffectCloudPredictionMixin {
    @Inject(method = "clientTick", at = @At("HEAD"), cancellable = true)
    private void projectkorra$selectParticles(CallbackInfo callback) {
        var cloud = (AreaEffectCloudEntity) (Object) this;
        if (!(cloud instanceof com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackCloudParticles)
                && FabricPredictionMC.hideOwnedEntityParticles(cloud)) callback.cancel();
    }
}
