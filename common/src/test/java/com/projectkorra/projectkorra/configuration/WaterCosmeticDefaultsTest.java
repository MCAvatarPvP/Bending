package com.projectkorra.projectkorra.configuration;

import com.projectkorra.projectkorra.platform.mc.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WaterCosmeticDefaultsTest {
    private static final List<String> EXPECTED = List.of(
            "white, WHITE_STAINED_GLASS",
            "orange, ORANGE_STAINED_GLASS",
            "magenta, MAGENTA_STAINED_GLASS",
            "light_blue, LIGHT_BLUE_STAINED_GLASS",
            "yellow, YELLOW_STAINED_GLASS",
            "lime, LIME_STAINED_GLASS",
            "pink, PINK_STAINED_GLASS",
            "gray, GRAY_STAINED_GLASS",
            "light_gray, LIGHT_GRAY_STAINED_GLASS",
            "cyan, CYAN_STAINED_GLASS",
            "purple, PURPLE_STAINED_GLASS",
            "blue, BLUE_STAINED_GLASS",
            "brown, BROWN_STAINED_GLASS",
            "green, GREEN_STAINED_GLASS",
            "red, RED_STAINED_GLASS",
            "black, BLACK_STAINED_GLASS"
    );

    @Test
    void defaultsContainEveryUniqueStainedGlassColor() {
        assertEquals(EXPECTED, ConfigManager.DEFAULT_WATER_COSMETICS);

        final Set<Material> materials = new HashSet<>();
        for (String mapping : EXPECTED) {
            final String materialName = mapping.substring(mapping.indexOf(", ") + 2);
            final Material material = Material.getMaterial(materialName);
            assertNotNull(material, materialName + " must exist in the common material registry");
            materials.add(material);
        }
        assertEquals(16, materials.size());
    }
}
