package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
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
        if (ExactPredictionRuntime.hideDisplayFallback(entity)) cir.setReturnValue(false);
    }
}
