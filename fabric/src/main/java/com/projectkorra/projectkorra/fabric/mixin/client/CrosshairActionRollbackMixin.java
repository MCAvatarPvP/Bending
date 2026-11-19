package com.projectkorra.projectkorra.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackPlayerRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Vanilla rechecks its pick before submitting the native action. Use the same private terrain and reach. */
@Mixin(MinecraftClient.class)
abstract class CrosshairActionRollbackMixin {
    @WrapOperation(method = {"doAttack", "handleBlockBreaking"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"))
    private BlockState projectkorra$pickedBlock(ClientWorld world, BlockPos pos, Operation<BlockState> original) {
        var client = (MinecraftClient)(Object)this;
        var state = FabricRollbackPlayerRenderer.pickedBlock(client.player, client.getCameraEntity(), pos);
        return state == null ? original.call(world, pos) : state;
    }
    @WrapOperation(method = "doAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/component/type/AttackRangeComponent;isWithinRange(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/util/math/Vec3d;)Z"))
    private boolean projectkorra$withinRange(AttackRangeComponent range, LivingEntity player, Vec3d point, Operation<Boolean> original) {
        var client = (MinecraftClient)(Object)this;
        var result = FabricRollbackPlayerRenderer.withinWeaponRange(player, client.getCameraEntity(), range, point);
        return result == null ? original.call(range, player, point) : result;
    }
}
