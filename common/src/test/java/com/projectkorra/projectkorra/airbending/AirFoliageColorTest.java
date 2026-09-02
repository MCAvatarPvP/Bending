package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirFoliageColorTest {

    @Test
    void usesMinecraftSpeciesTintsForLeaves() {
        assertColor(0x619961, Material.SPRUCE_LEAVES);
        assertColor(0x80A755, Material.BIRCH_LEAVES);
        assertColor(0x92C648, Material.MANGROVE_LEAVES);
        assertColor(0xE7A8B7, Material.CHERRY_LEAVES);
        assertColor(0xA7BFA0, Material.PALE_OAK_LEAVES);
    }

    @Test
    void colorsOtherFoliageByItsSourceFamily() {
        assertColor(0x48B518, Material.SHORT_GRASS);
        assertColor(0x3A8E8C, Material.WARPED_ROOTS);
        assertColor(0xA6536F, Material.CRIMSON_ROOTS);
        assertColor(0x8A6846, Material.DEAD_BUSH);
        assertColor(0xD4473F, Material.POPPY);
    }

    @Test
    void particlesInheritAttackDirectionAndSpeed() {
        final Vector slow = AirFoliage.particleVelocity(new Vector(0.5, 0, 0), 1, 5);
        final Vector fast = AirFoliage.particleVelocity(new Vector(1.4, 0, 0), 1, 5);
        final Vector reverse = AirFoliage.particleVelocity(new Vector(-1.4, 0, 0), 1, 5);

        assertTrue(slow.getX() > 0 && fast.getX() > slow.getX(),
                "faster swipes should push leaves farther in their travel direction");
        assertTrue(reverse.getX() < 0, "reversing the sweep must reverse the leaf push");
        assertTrue(fast.getY() > 0, "leaves should receive a small loft before native gravity takes over");
    }

    private static void assertColor(final int expected, final Material material) {
        final Color color = AirFoliage.foliageColor(material);
        assertEquals(expected, color.asRGB(), material.toString());
    }
}
