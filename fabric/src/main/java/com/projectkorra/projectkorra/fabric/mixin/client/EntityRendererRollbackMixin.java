package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackPlayerRenderer;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererRollbackMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void projectkorra$rollbackCulling(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> result) {
        var visible = FabricRollbackPlayerRenderer.shouldRender(entity, frustum, x, y, z); if (visible != null) result.setReturnValue(visible);
    }
    @Inject(method = "getAndUpdateRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/EntityRenderer;updateRenderState(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/entity/state/EntityRenderState;F)V", shift = At.Shift.AFTER), locals = LocalCapture.CAPTURE_FAILHARD)
    private void projectkorra$rollbackMotion(Entity entity, float delta, CallbackInfoReturnable<EntityRenderState> result, EntityRenderState state) {
        FabricRollbackPlayerRenderer.apply(entity, state, delta);
    }
}
