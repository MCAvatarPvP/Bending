package com.projectkorra.projectkorra.fabric.mixin.client;

import net.minecraft.client.particle.LeavesParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.TintedParticleEffect;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Restores the initial velocity that vanilla's tinted-leaf factory discards. */
@Mixin(LeavesParticle.TintedLeavesFactory.class)
public abstract class TintedLeavesVelocityMixin {

    @Inject(
            method = "createParticle(Lnet/minecraft/particle/TintedParticleEffect;"
                    + "Lnet/minecraft/client/world/ClientWorld;DDDDDD"
                    + "Lnet/minecraft/util/math/random/Random;)"
                    + "Lnet/minecraft/client/particle/Particle;",
            at = @At("RETURN")
    )
    private void projectkorra$applyInitialVelocity(
            final TintedParticleEffect effect,
            final ClientWorld world,
            final double x,
            final double y,
            final double z,
            final double velocityX,
            final double velocityY,
            final double velocityZ,
            final Random random,
            final CallbackInfoReturnable<Particle> cir) {
        final Particle particle = cir.getReturnValue();
        if (particle != null) particle.setVelocity(velocityX, velocityY, velocityZ);
    }
}
