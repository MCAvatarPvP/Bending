package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps predicted AirGlider travel on vanilla air damping without input or Elytra acceleration. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityAirGliderPredictionMixin {
    @ModifyVariable(method = "travel", at = @At("HEAD"), argsOnly = true)
    private Vec3d projectkorra$removeAirGliderMovementInput(final Vec3d movementInput) {
        final LivingEntity entity = (LivingEntity) (Object) this;
        if (entity instanceof ClientPlayerEntity player
                && ExactPredictionRuntime.suppressVanillaAirGliderEffects(player)) return Vec3d.ZERO;
        return movementInput;
    }

    @Redirect(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/LivingEntity;isGliding()Z"))
    private boolean projectkorra$excludeVanillaElytraTravel(final LivingEntity entity) {
        if (entity instanceof ClientPlayerEntity player
                && ExactPredictionRuntime.suppressVanillaAirGliderEffects(player)) return false;
        return entity.isGliding();
    }
}
