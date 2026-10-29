package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import com.projectkorra.projectkorra.fabric.client.config.ClientBendingConfig;
import com.projectkorra.projectkorra.fabric.client.prediction.entity.PredictedEntityIdAllocator;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererPredictionDisplayMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void projectkorra$hideDisplayFallback(Entity entity, Frustum frustum,
                                                 double x, double y, double z,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (!ClientBendingConfig.isVisualsEnabled()) {
            // Server visuals: predicted entities keep simulating for gameplay
            // but stay unseen, and Paper's own copies are never hidden.
            if (PredictedEntityIdAllocator.isPredictedId(entity.getId())
                    || ExactPredictionRuntime.isPredictedOwned(entity)) cir.setReturnValue(false);
            return;
        }
        if (ExactPredictionRuntime.hideDisplayFallback(entity)) cir.setReturnValue(false);
    }
}
