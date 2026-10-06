package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackPlayerRenderer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererRollbackMixin {
    @WrapMethod(method = "fillEntityRenderStates")
    private void projectkorra$rollbackExtraction(Camera camera, Frustum frustum, RenderTickCounter ticks, WorldRenderState states, Operation<Void> original) {
        try (var scope = FabricRollbackPlayerRenderer.extracting()) { original.call(camera, frustum, ticks, states); }
    }
    @Redirect(method = "fillEntityRenderStates", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getBlockPos()Lnet/minecraft/util/math/BlockPos;"))
    private BlockPos projectkorra$rollbackChunkPosition(Entity entity) { return FabricRollbackPlayerRenderer.blockPos(entity); }
}
