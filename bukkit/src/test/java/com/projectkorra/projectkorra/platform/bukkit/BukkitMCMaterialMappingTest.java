package com.projectkorra.projectkorra.platform.bukkit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitMCMaterialMappingTest {
    @Test
    void blockWritesCloneCachedNativeBlockDataWithoutNameMatching() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java");
        if (!Files.exists(source)) source = Path.of("bukkit").resolve(source);
        final String adapter = Files.readString(source);

        assertFalse(adapter.contains("Material.matchMaterial("));
        assertTrue(adapter.contains("NATIVE_MATERIALS[value.ordinal()]"));
        assertTrue(adapter.contains("COMMON_MATERIALS[value.ordinal()]"));
        assertTrue(adapter.contains("NATIVE_BLOCK_DATA[ordinal]"));
        assertTrue(adapter.contains("return prototype.clone();"));
    }
}
