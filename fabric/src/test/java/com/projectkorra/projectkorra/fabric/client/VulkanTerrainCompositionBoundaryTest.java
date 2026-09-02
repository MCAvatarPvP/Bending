package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the optional VulkanMod terrain-composition boundary. */
class VulkanTerrainCompositionBoundaryTest {
    @Test
    void vulkanRenderRegionReadsUseTheRenderOnlyOverlay() throws IOException {
        final String mixin = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/VulkanRenderRegionPredictionMixin.java");
        final String config = source("src/main/resources/projectkorra.mixins.json");

        assertTrue(config.contains("client.VulkanRenderRegionPredictionMixin"),
                "the optional VulkanMod compatibility mixin must be registered");
        assertTrue(mixin.contains("@Pseudo")
                        && mixin.contains(
                        "targets = \"net.vulkanmod.render.chunk.build.RenderRegion\"")
                        && mixin.contains("require = 1")
                        && mixin.contains("remap = false"),
                "VulkanMod must remain optional, but a present incompatible target must fail closed");
        assertTrue(mixin.contains(
                        "method = {\"getBlockState\", \"method_8320\"}"),
                "the hook must cover Loom's named method and the 1.21.11 production alias");
        assertTrue(mixin.contains("ExactPredictionRuntime.visualBlockState(")
                        && mixin.contains("@Shadow @Final private World level")
                        && mixin.contains(
                        "this.level instanceof ClientWorld world")
                        && mixin.contains("|| pos == null")
                        && mixin.contains("authoritativeState == null")
                        && mixin.contains(
                        "!ExactPredictionRuntime.hasBlockVisualOverrides()"),
                "VulkanMod must compose against the snapshot's own world with the same null-safe overlay as other terrain renderers");
        assertFalse(mixin.contains("setBlockState("),
                "renderer compatibility must never mutate authoritative ClientWorld storage");
        assertFalse(mixin.contains("getFluidState"),
                "VulkanMod fluid lookup already delegates to the composed block-state getter");
    }

    private static String source(final String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path)) path = Path.of("fabric").resolve(relative);
        assertTrue(Files.exists(path), "missing source: " + path);
        return Files.readString(path);
    }
}
