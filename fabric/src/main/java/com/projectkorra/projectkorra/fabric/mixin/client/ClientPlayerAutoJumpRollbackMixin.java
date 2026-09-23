package com.projectkorra.projectkorra.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackAutoJump;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The native algorithm's query receiver is a facade; collision membership and shape policy belong to the owned body. */
@Mixin(ClientPlayerEntity.class)
abstract class ClientPlayerAutoJumpRollbackMixin {
    @WrapOperation(method = "autoJump", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/ShapeContext;of(Lnet/minecraft/entity/Entity;)Lnet/minecraft/block/ShapeContext;"))
    private ShapeContext projectkorra$autoJumpShape(Entity entity, Operation<ShapeContext> original) {
        return original.call(FabricRollbackAutoJump.collisionSource(entity));
    }
    @WrapOperation(method = "autoJump", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getCollisions(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Box;)Ljava/lang/Iterable;"))
    private Iterable<VoxelShape> projectkorra$autoJumpCollisions(World world, Entity entity, Box bounds, Operation<Iterable<VoxelShape>> original) {
        return original.call(world, FabricRollbackAutoJump.collisionSource(entity), bounds);
    }
}
