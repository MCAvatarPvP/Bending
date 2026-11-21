package com.projectkorra.projectkorra.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackActorAllocation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.concurrent.atomic.AtomicInteger;

@Mixin(Entity.class)
abstract class EntityRollbackAllocationMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/atomic/AtomicInteger;incrementAndGet()I", remap = false))
    private int projectkorra$privateId(AtomicInteger global, Operation<Integer> original, EntityType<?> type, World world) {
        Integer owned = FabricRollbackActorAllocation.networkId(type, world);
        return owned != null ? owned : original.call(global);
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/random/Random;create()Lnet/minecraft/util/math/random/Random;"))
    private Random projectkorra$privateRandom(Operation<Random> original, EntityType<?> type, World world) {
        var owned = FabricRollbackActorAllocation.random(type, world);
        return owned != null ? owned : original.call();
    }
}
