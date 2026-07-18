package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards smooth movement for locally predicted Display entities. */
class DisplayInterpolationBoundaryTest {
    @Test
    void predictedClientBlockDisplayTeleportFeedsTheNativePositionInterpolator() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricPredictionMC.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        final String fabric = Files.readString(source);
        final int start = fabric.indexOf("private static final class ClientBlockDisplay");
        final int end = fabric.indexOf("private static final class ClientShulkerBullet", start);
        assertTrue(start >= 0 && end > start);
        final String display = fabric.substring(start, end);

        assertTrue(display.contains("value.getTeleportDuration() > 0")
                        && display.contains("value.getInterpolator().setLerpDuration")
                        && display.contains("value.getInterpolator().refreshPositionAndAngles"),
                "predicted BlockDisplays must interpolate their render position instead of snapping every ability tick");
    }

    @Test
    void clientDisplayTeleportFeedsTheNativePositionInterpolator() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricMC.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        final String fabric = Files.readString(source);
        final int start = fabric.indexOf("private static class DisplayView");
        final int end = fabric.indexOf("private static final class BlockDisplayView", start);
        assertTrue(start >= 0 && end > start);
        final String display = fabric.substring(start, end);

        assertTrue(display.contains("value.getEntityWorld().isClient()")
                        && display.contains("value.getInterpolator().setLerpDuration")
                        && display.contains("value.getInterpolator().refreshPositionAndAngles"),
                "direct requestTeleport snaps a locally predicted display; its render position must use DisplayEntity's interpolator");
    }
}
