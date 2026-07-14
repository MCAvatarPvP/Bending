package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the authority boundary that prevents moving TempBlocks from ghosting. */
class TempBlockMetadataAuthorityTest {
    @Test
    void tempBlockMetadataCannotWriteWorldOrCreateVanillaEchoes() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        assertTrue(Files.exists(source), "ExactPredictionRuntime source must be available to the invariant test");

        String runtime = Files.readString(source);
        int start = runtime.indexOf("private void applyTempBlockBatch0");
        int end = runtime.indexOf("private static BlockState visibleServerLayer", start);
        assertTrue(start >= 0 && end > start, "TempBlock metadata handler must be present");

        String handler = runtime.substring(start, end);
        assertFalse(handler.contains("world.setBlockState("),
                "TempBlock metadata must never overwrite vanilla authority or newer prediction");
        assertFalse(handler.contains("blockEchoes.add("),
                "Metadata must never invent an echo for a vanilla packet that may have already arrived");
    }
}
