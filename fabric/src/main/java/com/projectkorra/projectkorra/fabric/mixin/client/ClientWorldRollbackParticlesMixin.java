package com.projectkorra.projectkorra.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackCloudParticles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.Entity;
import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackPlayerRenderer;

@Mixin(ClientWorld.class)
abstract class ClientWorldRollbackParticlesMixin {
    @Inject(method = "addEntity", at = @At("RETURN"))
    private void projectkorra$handoffCloud(Entity entity, CallbackInfo callback) {
        if (entity instanceof AreaEffectCloudEntity) FabricRollbackPlayerRenderer.cloudTracked(entity.getEntityWorld(), entity.getUuid());
    }

    @ModifyExpressionValue(method = "addParticle(Lnet/minecraft/particle/ParticleEffect;ZZDDDDDD)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleManager;addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;"))
    private Particle projectkorra$recordCloudParticle(Particle particle) {
        return FabricRollbackCloudParticles.created(particle);
    }
}
