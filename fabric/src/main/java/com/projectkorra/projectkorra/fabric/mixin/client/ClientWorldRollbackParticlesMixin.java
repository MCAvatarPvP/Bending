package com.projectkorra.projectkorra.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackCloudParticles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientWorld.class)
abstract class ClientWorldRollbackParticlesMixin {
    @ModifyExpressionValue(method = "addParticle(Lnet/minecraft/particle/ParticleEffect;ZZDDDDDD)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleManager;addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;"))
    private Particle projectkorra$recordCloudParticle(Particle particle) {
        return FabricRollbackCloudParticles.created(particle);
    }
}
