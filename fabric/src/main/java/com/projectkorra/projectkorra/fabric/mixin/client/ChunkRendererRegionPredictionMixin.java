package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.chunk.ChunkRendererRegion;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Composes prediction into chunk meshes without installing predicted states in
 * the client chunk. Vanilla networking therefore remains the sole writer of
 * {@link ClientWorld} block storage.
 */
@Mixin(ChunkRendererRegion.class)
public abstract class ChunkRendererRegionPredictionMixin {
    @Shadow @Final private World world;

    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
    private void projectkorra$visualBlockState(final BlockPos pos,
                                                final CallbackInfoReturnable<BlockState> cir) {
        if (this.world instanceof ClientWorld clientWorld) {
            cir.setReturnValue(ExactPredictionRuntime.visualBlockState(
                    clientWorld, pos, cir.getReturnValue()));
        }
    }

    @Inject(method = "getFluidState", at = @At("RETURN"), cancellable = true)
    private void projectkorra$visualFluidState(final BlockPos pos,
                                                final CallbackInfoReturnable<FluidState> cir) {
        if (!(this.world instanceof ClientWorld)) return;
        // getBlockState is already composed by the injection above. Keeping
        // fluid and model lookup on the same state prevents water/lava remnants
        // when a visual override represents air or another fluid.
        cir.setReturnValue(((ChunkRendererRegion) (Object) this)
                .getBlockState(pos).getFluidState());
    }
}
