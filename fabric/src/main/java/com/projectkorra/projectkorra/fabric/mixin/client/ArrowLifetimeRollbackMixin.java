package com.projectkorra.projectkorra.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackArrow;
import com.projectkorra.projectkorra.prediction.rollback.RollbackWorldSettings.ArrowLifetime;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.TridentEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PersistentProjectileEntity.class)
abstract class ArrowLifetimeRollbackMixin implements FabricRollbackArrow {
    @Unique private ArrowLifetime projectkorra$lifetime;
    @Shadow protected abstract void age();

    @Override public void rollback$arrowLifetime(ArrowLifetime lifetime) { projectkorra$lifetime = lifetime; }

    @ModifyConstant(method = "age", constant = @Constant(intValue = 1200))
    private int projectkorra$despawnAt(int ordinary) {
        if (projectkorra$lifetime == null) return ordinary;
        var arrow = (PersistentProjectileEntity) (Object) this;
        return switch (arrow.pickupType) {
            case CREATIVE_ONLY -> projectkorra$lifetime.creativeTicks();
            case DISALLOWED -> projectkorra$lifetime.unpickableTicks();
            case ALLOWED -> arrow instanceof TridentEntity ? projectkorra$lifetime.tridentTicks() : projectkorra$lifetime.groundTicks();
        };
    }

    // Vanilla clears inGroundTime on the airborne branch immediately before its flight physics.
    @Inject(method = "tick", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, ordinal = 1,
            target = "Lnet/minecraft/entity/projectile/PersistentProjectileEntity;inGroundTime:I"))
    private void projectkorra$ageFlyingArrow(CallbackInfo callback) {
        var arrow = (PersistentProjectileEntity) (Object) this;
        if (projectkorra$lifetime != null && projectkorra$lifetime.airGrace() >= 0 && arrow.age > projectkorra$lifetime.airGrace()) age();
    }

    @WrapOperation(method = {"fall", "setVelocityClient"}, at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/entity/projectile/PersistentProjectileEntity;life:I"))
    private void projectkorra$retainAirborneLife(PersistentProjectileEntity arrow, int value, Operation<Void> write) {
        if (projectkorra$lifetime == null || projectkorra$lifetime.airGrace() < 0) write.call(arrow, value);
    }
}
