package com.projectkorra.projectkorra.platform.bukkit;

import org.junit.jupiter.api.Test;
import com.projectkorra.projectkorra.platform.mc.Material;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BukkitMCMaterialMappingTest {
    @Test
    void everyModernNativeMaterialRetainsItsIdentity() {
        for (final org.bukkit.Material nativeMaterial : org.bukkit.Material.values()) {
            if (nativeMaterial.isLegacy()) continue;
            final Material common = BukkitMC.material(nativeMaterial);
            assertEquals(nativeMaterial.name(), common.name(),
                    "unlisted blocks must not turn into air before abilities query their solidity");
            assertSame(nativeMaterial, BukkitMC.material(common));
        }
    }

    @Test
    void compatibilityNamesResolveToTheirCurrentNativeMaterials() {
        for (final Material common : Material.values()) {
            assertEquals(common.canonical().name(), BukkitMC.material(common).name());
        }
        assertSame(org.bukkit.Material.IRON_CHAIN, BukkitMC.material(Material.CHAIN));
        assertSame(Material.IRON_CHAIN, BukkitMC.material(org.bukkit.Material.IRON_CHAIN));
    }

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
