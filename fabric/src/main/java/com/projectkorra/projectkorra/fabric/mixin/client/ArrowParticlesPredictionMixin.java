package com.projectkorra.projectkorra.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.projectkorra.projectkorra.platform.fabric.FabricPredictionMC;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PersistentProjectileEntity.class)
abstract class ArrowParticlesPredictionMixin {
    @WrapWithCondition(method = {"tick", "spawnBubbleParticles"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;addParticleClient(Lnet/minecraft/particle/ParticleEffect;DDDDDD)V"))
    private boolean projectkorra$selectFlightTrail(World world, ParticleEffect particle, double x, double y, double z,
                                                   double dx, double dy, double dz) {
        return !FabricPredictionMC.hideOwnedEntityParticles((Entity) (Object) this);
    }
}
