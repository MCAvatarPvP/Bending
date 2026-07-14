package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictionDesyncRendererTest {
    @Test
    void rendererUsesOnlyTheBoundedOwnedTempDesyncSnapshot() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionDesyncRenderer.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        String renderer = Files.readString(source);

        assertTrue(renderer.contains("ExactPredictionRuntime.ownedTempDesyncs"));
        assertTrue(renderer.contains("DrawStyle.filledAndStroked"));
        assertTrue(renderer.contains("0x38FFD54F"), "desync marker must remain translucent");
    }
}
