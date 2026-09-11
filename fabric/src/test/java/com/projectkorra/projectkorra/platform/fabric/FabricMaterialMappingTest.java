package com.projectkorra.projectkorra.platform.fabric;

import com.projectkorra.projectkorra.platform.mc.Material;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

class FabricMaterialMappingTest {
    @Test
    void commonCatalogIncludesEveryVanillaBlock() {
        // Inspect the pinned game's block definitions without bootstrapping a server outside Fabric Loader.
        int blocks = 0;
        for (final var field : Blocks.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Block.class.isAssignableFrom(field.getType())) continue;
            final Material material = Material.getMaterial(field.getName());
            assertNotNull(material, field.getName() + " must not fall back to AIR in the Fabric adapter");
            assertEquals(field.getName(), material.canonical().name());
            blocks++;
        }
        assertTrue(blocks > 1_000, "the complete vanilla block catalog must be checked");
    }
}
