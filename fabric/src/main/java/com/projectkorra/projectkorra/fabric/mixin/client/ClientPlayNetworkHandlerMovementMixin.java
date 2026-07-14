package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.PredictionClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks the movement/look state that has actually been sent to the server.
 */
@Mixin(ClientCommonNetworkHandler.class)
abstract class ClientPlayNetworkHandlerMovementMixin {
    @Inject(method = "sendPacket", at = @At("HEAD"))
    private void projectkorra$recordServerVisiblePose(Packet<?> packet, CallbackInfo ci) {
        PredictionClient.beforeVanillaPacket(MinecraftClient.getInstance(), packet);
        if (packet instanceof PlayerMoveC2SPacket move) {
            PredictionClient.recordMovementPacket(MinecraftClient.getInstance(), move);
        }
    }
}
