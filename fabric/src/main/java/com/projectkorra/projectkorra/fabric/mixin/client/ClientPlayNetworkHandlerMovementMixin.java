package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.PredictionClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures native input at vanilla's render-thread packet boundary.
 * Movement/look acceptance is handled separately one layer lower by
 * ClientConnectionMovementMixin.
 */
@Mixin(ClientCommonNetworkHandler.class)
abstract class ClientPlayNetworkHandlerMovementMixin {
    @Inject(method = "sendPacket", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/ClientConnection;send(Lnet/minecraft/network/packet/Packet;)V"))
    private void projectkorra$captureNativeInput(Packet<?> packet, CallbackInfo ci) {
        PredictionClient.beforeVanillaPacket(MinecraftClient.getInstance(), packet);
    }
}
