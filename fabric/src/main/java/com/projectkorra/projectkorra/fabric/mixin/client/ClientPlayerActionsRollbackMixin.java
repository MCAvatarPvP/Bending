package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackNativeActions;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerEntity.class)
abstract class ClientPlayerActionsRollbackMixin {
    @Inject(method = "swingHand", at = @At("HEAD"), cancellable = true)
    private void projectkorra$swing(Hand hand, CallbackInfo callback) {
        if (FabricRollbackNativeActions.playerPacket((ClientPlayerEntity) (Object) this, () -> new HandSwingC2SPacket(hand))) callback.cancel();
    }
    @Inject(method = "dropSelectedItem", at = @At("HEAD"), cancellable = true)
    private void projectkorra$drop(boolean all, CallbackInfoReturnable<Boolean> callback) {
        if (FabricRollbackNativeActions.playerPacket((ClientPlayerEntity) (Object) this, () -> new PlayerActionC2SPacket(
                all ? PlayerActionC2SPacket.Action.DROP_ALL_ITEMS : PlayerActionC2SPacket.Action.DROP_ITEM, BlockPos.ORIGIN, Direction.DOWN))) callback.setReturnValue(false);
    }
}
