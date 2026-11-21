package com.projectkorra.projectkorra.fabric.mixin;

import com.projectkorra.projectkorra.fabric.FabricPhysicsBridge;
import com.projectkorra.projectkorra.fabric.PrivateFluidFlow;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlowableFluid.class)
abstract class FlowableFluidMixin {
    @Inject(method = "onScheduledTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z"),
            cancellable = true)
    private void projectKorra$onLevelChange(ServerWorld world, BlockPos pos, BlockState state, FluidState fluid,
                                             CallbackInfo callback) {
        if (world instanceof PrivateFluidFlow owner && owner.projectKorra$fluidFlow() != null
                && owner.projectKorra$fluidFlow().cancelLevelChange(pos)) callback.cancel();
    }

    @Inject(method = "flow", at = @At("HEAD"), cancellable = true)
    private void projectKorra$onFluidFlow(WorldAccess world, BlockPos pos, BlockState state,
                                          Direction direction, FluidState fluidState, CallbackInfo callback) {
        if (FabricPhysicsBridge.shouldCancelFluidFlow(world, pos, direction)) {
            callback.cancel();
        }
    }
}
