package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackMelee;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
abstract class PlayerMeleeRollbackMixin {
    @Inject(method = "isCriticalHit", at = @At("HEAD"), cancellable = true)
    private void projectkorra$critical(Entity target, CallbackInfoReturnable<Boolean> result) {
        if (FabricRollbackMelee.disableCrits((PlayerEntity) (Object) this)) result.setReturnValue(false);
    }
    @ModifyArg(method = "attack", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;addExhaustion(F)V"), index = 0)
    private float projectkorra$attackExhaustion(float ordinary) {
        return FabricRollbackMelee.exhaustion((PlayerEntity) (Object) this, ordinary);
    }
    @Redirect(method = "knockbackTarget", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;setSprinting(Z)V"))
    private void projectkorra$interruptSprint(PlayerEntity player, boolean sprinting) {
        if (sprinting || FabricRollbackMelee.clearSprint(player)) player.setSprinting(sprinting);
    }
}
