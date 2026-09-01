package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source boundaries for render-only, per-frame WaterManipulation fluids. */
class PredictedFluidForegroundBoundaryTest {
    @Test
    void predictedFluidUsesVanillaTessellationInsteadOfAChunkRebuild() throws IOException {
        final String overlay = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientBlockVisualOverlay.java");
        final String renderer = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionBlockVisualRenderer.java");
        final String mesh = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionFluidMesh.java");

        assertTrue(overlay.contains("|| !state.getFluidState().isEmpty()"),
                "local TempBlock water must be eligible for the immediate foreground");
        assertTrue(renderer.contains("PredictionFluidMesh.tessellate(")
                        && renderer.contains("PREDICTED_FLUID_LAYER")
                        && renderer.contains("LayeringTransform.VIEW_OFFSET_Z_LAYERING_FORWARD")
                        && renderer.contains("block.fluidMesh.replay(vertices, matrix)"),
                "predicted fluid must be extracted once and submitted every frame on a fluid-compatible layer");
        assertTrue(mesh.contains("renderer.renderFluid(pos, view, capture, state, fluid)")
                        && mesh.contains("this.vertices = List.copyOf(vertices)")
                        && mesh.contains("x - this.originX"),
                "fluid geometry must come from vanilla, then become immutable block-local render data");
        assertFalse(mesh.contains("setBlockState("),
                "the fluid foreground must never write prediction into ClientWorld");
    }

    @Test
    void fluidNeighborsComeFromTheSameLogicalOverlay() throws IOException {
        final String renderer = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionBlockVisualRenderer.java");
        final String runtime = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        final String water = source(
                "../common/src/main/java/com/projectkorra/projectkorra/waterbending/WaterManipulation.java");

        assertTrue(renderer.contains("private record ComposedRenderView")
                        && renderer.contains("ExactPredictionRuntime.composedVisualBlockState("),
                "fluid height and face culling must see adjacent predicted TEMP/DIRECT states");
        assertTrue(runtime.contains("blockVisualOverlay.compose(world, pos, authoritativeState)"),
                "fluid extraction must use logical composition, not the foreground terrain cutout");
        assertTrue(water.contains("new TempBlock(block, Material.WATER.createBlockData(), this)")
                        || water.contains("new TempBlock(block, WATER, this)"),
                "the regression boundary must continue to cover WaterManipulation's TempBlock projectile");
    }

    private static String source(final String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path)) path = Path.of("fabric").resolve(relative);
        assertTrue(Files.exists(path), "missing source: " + path);
        return Files.readString(path);
    }
}
