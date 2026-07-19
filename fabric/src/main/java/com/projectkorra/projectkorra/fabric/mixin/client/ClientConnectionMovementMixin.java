package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.PredictionClient;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Commits Paper-visible movement only after ClientConnection itself accepts
 * the packet. A networking mixin that cancels ClientConnection#send therefore
 * cannot leave prediction one mouse step ahead of Paper.
 */
@Mixin(value = ClientConnection.class, priority = 500)
abstract class ClientConnectionMovementMixin {
    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/ClientConnection;sendImmediately(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V"))
    private void projectkorra$commitAcceptedMovement(Packet<?> packet,
                                                      ChannelFutureListener listener,
                                                      boolean flush,
                                                      CallbackInfo ci) {
        PredictionClient.acceptedMovementPacket(MinecraftClient.getInstance(), packet);
    }

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/ClientConnection;sendImmediately(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
                    shift = At.Shift.AFTER))
    private void projectkorra$flushAcceptedInputMetadata(Packet<?> packet,
                                                          ChannelFutureListener listener,
                                                          boolean flush,
                                                          CallbackInfo ci) {
        PredictionClient.acceptedNativeInputPacket(MinecraftClient.getInstance(), packet);
    }
}
