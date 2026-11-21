package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.block.data.Waterlogged;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackServerBlocksNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }
    @Test void freshNativeDefaultsSupportLightEffectsWithoutLiveWorldAccess() {
        var blocks = new PaperRollbackServerBlocks();
        var light = blocks.create(Material.LIGHT);
        assertEquals(15, ((Levelled) light).getLevel());
        assertFalse(((Waterlogged) light).isWaterlogged());
        ((Levelled) light).setLevel(3); ((Waterlogged) light).setWaterlogged(true);
        var next = blocks.create(Material.LIGHT);
        assertNotSame(light, next); assertEquals(15, ((Levelled) next).getLevel());
        assertFalse(((Waterlogged) next).isWaterlogged());
        assertEquals(Material.SNOW_BLOCK, blocks.create(Material.SNOW_BLOCK).getMaterial());
        assertEquals(Material.AIR, blocks.create(Material.AIR).getMaterial());
        assertEquals(Material.SHORT_GRASS, blocks.create(Material.GRASS).getMaterial());
        assertEquals(Material.DIRT_PATH, blocks.create(Material.GRASS_PATH).getMaterial());
        assertThrows(IllegalArgumentException.class, () -> blocks.create(Material.DIAMOND_SWORD));
        assertNull(org.bukkit.Bukkit.getServer());
    }
}
