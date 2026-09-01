package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Applies EarthShard's short client-only no-block-collision launch grace at
 * the native falling-block movement call. Every other falling block retains
 * normal physics.
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityPredictionMixin {
    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/FallingBlockEntity;move(Lnet/minecraft/entity/MovementType;Lnet/minecraft/util/math/Vec3d;)V"
            )
    )
    private void projectkorra$moveWithPredictedTempBlockCollision(
            final FallingBlockEntity entity, final MovementType movementType,
            final Vec3d movement) {
        ExactPredictionRuntime.moveTempFallingBlock(
                entity, movementType, movement);
    }
}
