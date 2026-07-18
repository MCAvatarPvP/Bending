package com.projectkorra.projectkorra.fabric.mixin;

import com.projectkorra.projectkorra.fabric.FabricGameplayBridge;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
abstract class ServerPlayNetworkHandlerMixin {
    @Inject(method = "onHandSwing", at = @At("HEAD"), cancellable = true, require = 0)
    private void projectKorra$onHandSwing(HandSwingC2SPacket packet, CallbackInfo callback) {
        if (FabricGameplayBridge.onArmSwing(((ServerPlayNetworkHandler) (Object) this).player)) {
            callback.cancel();
        }
    }

    @Inject(method = "onClientCommand", at = @At("HEAD"), require = 0)
    private void projectKorra$onClientCommand(ClientCommandC2SPacket packet, CallbackInfo callback) {
        ClientCommandC2SPacket.Mode mode = packet.getMode();
        String name = mode.name();
        if ("PRESS_SHIFT_KEY".equals(name) || "START_SNEAKING".equals(name)) {
            FabricGameplayBridge.onClientSneakCommand(((ServerPlayNetworkHandler) (Object) this).player, true);
        } else if ("RELEASE_SHIFT_KEY".equals(name) || "STOP_SNEAKING".equals(name)) {
            FabricGameplayBridge.onClientSneakCommand(((ServerPlayNetworkHandler) (Object) this).player, false);
        }
    }

    @Inject(method = "onPlayerInput", at = @At("HEAD"), require = 0)
    private void projectKorra$onPlayerInput(PlayerInputC2SPacket packet, CallbackInfo callback) {
        FabricGameplayBridge.onPlayerInput(
                ((ServerPlayNetworkHandler) (Object) this).player, packet.input().sneak());
    }

    @Inject(method = "onPlayerAction", at = @At("HEAD"), cancellable = true, require = 0)
    private void projectKorra$onPlayerAction(PlayerActionC2SPacket packet, CallbackInfo callback) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        if (FabricGameplayBridge.onPlayerAction(handler.player, packet.getAction())) {
            callback.cancel();
        }
    }

    @Inject(method = "onPlayerInteractBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void projectKorra$onPlayerInteractBlock(PlayerInteractBlockC2SPacket packet, CallbackInfo callback) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        if (FabricGameplayBridge.onRightClickBlock(handler.player, packet.getHand())) {
            callback.cancel();
        }
    }

    @Inject(method = "onPlayerInteractItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void projectKorra$onPlayerInteractItem(PlayerInteractItemC2SPacket packet, CallbackInfo callback) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        if (FabricGameplayBridge.onRightClickItem(handler.player, packet.getHand())) {
            callback.cancel();
        }
    }

    @Inject(method = "onPlayerInteractEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void projectKorra$onPlayerInteractEntity(PlayerInteractEntityC2SPacket packet, CallbackInfo callback) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        boolean[] cancel = {false};
        packet.handle(new PlayerInteractEntityC2SPacket.Handler() {
            @Override
            public void interact(Hand hand) {
                // Match PKListener's PlayerInteractAtEntityEvent exactly.
            }

            @Override
            public void interactAt(Hand hand, Vec3d pos) {
                cancel[0] = FabricGameplayBridge.onRightClickEntity(handler.player, hand);
            }

            @Override
            public void attack() {
            }
        });
        if (cancel[0]) {
            callback.cancel();
        }
    }

    @Inject(method = "onUpdateSelectedSlot", at = @At("HEAD"), cancellable = true, require = 0)
    private void projectKorra$onUpdateSelectedSlot(UpdateSelectedSlotC2SPacket packet, CallbackInfo callback) {
        ServerPlayNetworkHandler handler = (ServerPlayNetworkHandler) (Object) this;
        if (FabricGameplayBridge.onSelectedSlot(handler.player, packet.getSelectedSlot())) {
            handler.player.getInventory().setSelectedSlot(packet.getSelectedSlot());
            callback.cancel();
        } else {
            callback.cancel();
        }
    }
}
