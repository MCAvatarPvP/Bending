package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.FallingBlockEntityRenderer;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla culls a falling block while its state equals the block it is
 * inside. Earth abilities launch uncosmeticized TempFallingBlocks from a
 * block of the same type, so locally predicted ones stayed invisible until
 * they left that block. Only predicted TempFallingBlocks skip the check.
 */
@Mixin(FallingBlockEntityRenderer.class)
public abstract class FallingBlockEntityRendererPredictionMixin {
    @Redirect(
            method = "shouldRender(Lnet/minecraft/entity/FallingBlockEntity;Lnet/minecraft/client/render/Frustum;DDD)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            )
    )
    private BlockState projectkorra$renderPredictedTempFallingBlock(
            final World world, final BlockPos pos, final FallingBlockEntity entity,
            final Frustum frustum, final double x, final double y, final double z) {
        // Any state other than the entity's own lets vanilla render it.
        if (ExactPredictionRuntime.isPredictedTempFallingBlock(entity)) return Blocks.AIR.getDefaultState();
        return world.getBlockState(pos);
    }
}
