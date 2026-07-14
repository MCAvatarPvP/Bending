package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes Paper's later entity ID to the already-rendering predicted entity.
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldPredictionAliasMixin {
    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void projectkorra$recordAuthoritativeBlock(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        ExactPredictionRuntime.authoritativeBlock((ClientWorld) (Object) this, pos, state);
    }

    @Inject(method = "getEntityById", at = @At("HEAD"), cancellable = true)
    private void projectkorra$resolvePredictedEntity(int id, CallbackInfoReturnable<Entity> ci) {
        Entity alias = ExactPredictionRuntime.aliasedEntity(id);
        if (alias != null) ci.setReturnValue(alias);
    }

    @Inject(method = "removeEntity", at = @At("HEAD"), cancellable = true)
    private void projectkorra$removePredictedEntity(int id, Entity.RemovalReason reason, CallbackInfo ci) {
        if (ExactPredictionRuntime.removeAliasedEntity(id)) ci.cancel();
    }
}
