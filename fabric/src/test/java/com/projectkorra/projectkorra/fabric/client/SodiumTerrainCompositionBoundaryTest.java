package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the optional Sodium terrain-composition boundary. */
class SodiumTerrainCompositionBoundaryTest {
    @Test
    void sodiumPrimitiveLevelSliceReadsUseTheRenderOnlyOverlay() throws IOException {
        final String mixin = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/SodiumLevelSlicePredictionMixin.java");
        final String overlay = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientBlockVisualOverlay.java");
        final String config = source("src/main/resources/projectkorra.mixins.json");

        assertTrue(config.contains("client.SodiumLevelSlicePredictionMixin"),
                "the optional renderer compatibility mixin must be registered");
        assertTrue(mixin.contains("@Pseudo")
                        && mixin.contains("targets = \"net.caffeinemc.mods.sodium.client.world.LevelSlice\"")
                        && mixin.contains("require = 1")
                        && mixin.contains("remap = false"),
                "Sodium must remain optional, but a present incompatible target must fail closed");
        assertTrue(mixin.contains("args = {int.class, int.class, int.class}")
                        && mixin.contains("ret = BlockState.class"),
                "the hook must select Sodium's primitive meshing overload exactly");
        assertTrue(mixin.contains("ExactPredictionRuntime.visualBlockState(")
                        && mixin.contains("new BlockPos(blockX, blockY, blockZ)")
                        && mixin.contains("world == null || authoritativeState == null")
                        && mixin.contains("!ExactPredictionRuntime.hasBlockVisualOverrides()"),
                "Sodium must consume the same null-safe render-only composition as vanilla");
        assertFalse(mixin.contains("setBlockState("),
                "renderer compatibility must never mutate authoritative ClientWorld storage");
        assertFalse(mixin.contains("BlockPos pos, final CallbackInfoReturnable"),
                "the delegating BlockPos overload must not compose the primitive result twice");
        assertTrue(overlay.contains("&& this.foregroundDirect.isEmpty()")
                        && overlay.contains("&& this.foregroundTemp.isEmpty()"),
                "Sodium must keep composing until detached foreground handoffs receive a terrain read");
    }

    private static String source(final String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path)) path = Path.of("fabric").resolve(relative);
        assertTrue(Files.exists(path), "missing source: " + path);
        return Files.readString(path);
    }
}
