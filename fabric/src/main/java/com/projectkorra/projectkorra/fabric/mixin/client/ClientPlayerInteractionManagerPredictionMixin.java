package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents the vanilla mining overlay from being started on a predicted smash. */
@Mixin(ClientPlayerInteractionManager.class)
abstract class ClientPlayerInteractionManagerPredictionMixin {
    @Inject(method = {"attackBlock", "updateBlockBreakingProgress"},
            at = @At("HEAD"), cancellable = true)
    private void projectkorra$skipPredictedEarthSmashBreaking(
            final BlockPos pos, final Direction direction,
            final CallbackInfoReturnable<Boolean> callback) {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null
                || !ExactPredictionRuntime.suppressLocalBlockBreaking(client.world, pos)) return;

        ((ClientPlayerInteractionManager) (Object) this).cancelBlockBreaking();
        client.world.setBlockBreakingInfo(client.player.getId(), pos, -1);
        // MinecraftClient#doAttack still swings the hand after attackBlock
        // returns, so Paper receives the same native LEFT_CLICK ability input.
        callback.setReturnValue(false);
    }
}
