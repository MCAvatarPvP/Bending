package com.projectkorra.projectkorra.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackNativeTick;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ClientPlayerEntity.class)
abstract class ClientPlayerTickRollbackMixin {
    @WrapMethod(method = "tick")
    private void projectkorra$ownedTick(Operation<Void> original) {
        if (!FabricRollbackNativeTick.tick((ClientPlayerEntity) (Object) this)) original.call();
    }
}
