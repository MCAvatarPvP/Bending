package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Desc;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feeds render-only prediction into Sodium's immutable chunk-build slice.
 *
 * <p>Sodium 0.8 on Minecraft 1.21.11 copies {@link ClientWorld} storage into a
 * {@code LevelSlice} and its mesher reads the primitive-coordinate overload
 * directly. It consequently bypasses vanilla's {@code ChunkRendererRegion}.
 * {@link Pseudo} keeps the target optional when Sodium is not installed. When
 * Sodium is present, however, this injection is required: silently missing a
 * changed getter would let its worker mesh bypass owned TempBlock concealment
 * and expose Paper's delayed physical copy.</p>
 *
 * <p>The {@code BlockPos} overload delegates to this primitive overload, as
 * does Sodium's fluid lookup. Injecting only here composes each read exactly
 * once and keeps block/fluid geometry on the same predicted state.</p>
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.LevelSlice", remap = false)
public abstract class SodiumLevelSlicePredictionMixin {
    @Inject(
            target = @Desc(
                    value = "getBlockState",
                    args = {int.class, int.class, int.class},
                    ret = BlockState.class
            ),
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            remap = false
    )
    private void projectkorra$visualBlockState(final int blockX, final int blockY,
                                                final int blockZ,
                                                final CallbackInfoReturnable<BlockState> cir) {
        final ClientWorld world = MinecraftClient.getInstance().world;
        final BlockState authoritativeState = cir.getReturnValue();
        if (world == null || authoritativeState == null
                || !ExactPredictionRuntime.hasBlockVisualOverrides()) return;
        cir.setReturnValue(ExactPredictionRuntime.visualBlockState(
                world, new BlockPos(blockX, blockY, blockZ), authoritativeState));
    }
}
