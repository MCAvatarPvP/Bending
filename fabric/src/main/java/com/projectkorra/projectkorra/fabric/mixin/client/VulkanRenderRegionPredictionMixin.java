package com.projectkorra.projectkorra.fabric.mixin.client;

import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feeds render-only prediction into VulkanMod's immutable terrain snapshot.
 *
 * <p>VulkanMod 0.6.8 on Minecraft 1.21.11 copies chunk storage into its own
 * {@code RenderRegion}, bypassing vanilla's {@code ChunkRendererRegion}. Its
 * block, fluid, lighting, and occlusion builders all read this getter, and its
 * fluid getter delegates to it. Composing the returned state here therefore
 * gives the Vulkan terrain compiler the same TEMP-over-DIRECT view as vanilla
 * and Sodium without mutating either the snapshot or {@link ClientWorld}.</p>
 *
 * <p>The named method is used in a Loom development runtime; the intermediary
 * alias is present in VulkanMod's production jar. {@link Pseudo} keeps the
 * renderer optional when it is absent. When it is installed, however, at least
 * one alias must match so a changed terrain boundary fails at startup instead
 * of silently exposing Paper's delayed physical copy.</p>
 */
@Pseudo
@Mixin(targets = "net.vulkanmod.render.chunk.build.RenderRegion", remap = false)
public abstract class VulkanRenderRegionPredictionMixin {
    @Shadow @Final private World level;

    @Inject(
            method = {"getBlockState", "method_8320"},
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            remap = false
    )
    private void projectkorra$visualBlockState(
            final BlockPos pos,
            final CallbackInfoReturnable<BlockState> cir) {
        final BlockState authoritativeState = cir.getReturnValue();
        if (!(this.level instanceof ClientWorld world) || pos == null
                || authoritativeState == null
                || !ExactPredictionRuntime.hasBlockVisualOverrides()) return;
        cir.setReturnValue(ExactPredictionRuntime.visualBlockState(
                world, pos, authoritativeState));
    }
}
