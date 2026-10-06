package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainSeed;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class RollbackTerrainSeedTest {
    private static Cell cell(Material material, byte light, String biome, byte[] tile) {
        var data = material.createBlockData(); data.setExactState("minecraft:" + material.name().toLowerCase(java.util.Locale.ROOT));
        return new Cell(data, tile, Biome.DESERT, biome, light, 0.8, 0.4);
    }
    private static Rules unusedRules() {
        return (Rules) Proxy.newProxyInstance(Rules.class.getClassLoader(), new Class<?>[]{Rules.class},
                (proxy, method, args) -> {
                    if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    throw new AssertionError(method);
                });
    }

    @Test void indexedCoordinatesPaletteAndDetachedTileDataSurviveOverlayRewind() {
        var bounds = new Bounds(-1, -2, -3, 1, 0, -1);
        var builder = new RollbackTerrainSeed.Builder(bounds, 8_192);
        byte[] tile = {1, 2, 3};
        var chest = cell(Material.CHEST, (byte) 7, "minecraft:plains", tile);
        builder.append(chest); builder.append(cell(Material.STONE, (byte) 1, "minecraft:plains", null));
        for (int index = 2; index < 8; index++) builder.append(cell(Material.AIR, (byte) 15, "custom:forest", null));
        var seed = builder.finish(); tile[0] = 9;
        assertEquals(8, seed.cellCount()); assertEquals(3, seed.paletteSize());
        assertArrayEquals(new byte[]{1, 2, 3}, seed.cell(new Position(-1, -2, -3)).blockEntity());
        assertEquals(Material.STONE, seed.cell(new Position(0, -2, -3)).data().getMaterial());
        assertEquals("custom:forest", seed.cell(new Position(0, -1, -2)).biomeKey());
        chest.blockEntity()[0] = 8; chest.data().setExactState("minecraft:air");
        var store = new RollbackBlockStore(new World(), seed, unusedRules(), 20);
        var position = new Position(-1, -2, -3); var before = store.captureRollbackState();
        store.replace(position, cell(Material.AIR, (byte) 0, "minecraft:desert", null), false);
        assertEquals(Material.AIR, store.cell(position).data().getMaterial());
        store.restoreRollbackState(before);
        assertEquals(Material.CHEST, store.cell(position).data().getMaterial());
        assertEquals("minecraft:plains", store.cell(position).biomeKey());
        assertArrayEquals(new byte[]{1, 2, 3}, store.cell(position).blockEntity());
        assertTrue(store.drainChanges().isEmpty());
        assertThrows(IllegalStateException.class, () -> seed.cell(new Position(1, -2, -3)));
    }

    @Test void rejectsIncompleteOversizedAndInexactCapturesAndDoesNotMergeDistinctNativeBiomes() {
        var bounds = new Bounds(0, 0, 0, 2, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> new RollbackTerrainSeed.Builder(bounds, 7));
        var tiny = new RollbackTerrainSeed.Builder(bounds, 8);
        assertThrows(IllegalStateException.class, () -> tiny.append(cell(Material.AIR, (byte) 0, "minecraft:plains", null)));
        var builder = new RollbackTerrainSeed.Builder(bounds, 4096);
        assertThrows(IllegalArgumentException.class, () -> builder.append(new Cell(Material.AIR.createBlockData(), null, Biome.DESERT, (byte) 0, 0.8, 0.4)));
        builder.append(cell(Material.AIR, (byte) 0, "minecraft:plains", null));
        assertThrows(IllegalStateException.class, builder::finish);
        builder.append(cell(Material.AIR, (byte) 0, "minecraft:forest", null));
        assertEquals(2, builder.finish().paletteSize());
        assertThrows(IllegalStateException.class, builder::finish);
        assertThrows(IllegalStateException.class, () -> builder.append(cell(Material.AIR, (byte) 0, "minecraft:plains", null)));
    }

    @Test void mutableFacadePropertiesWithTheSameExactBaseRemainDistinctPaletteEntries() {
        var first = new com.projectkorra.projectkorra.platform.mc.block.data.Levelled(Material.WATER);
        first.setExactState("minecraft:water[level=0]");
        var second = first.clone(); second.setExactState(first.getExactState()); second.setLevel(8);
        var builder = new RollbackTerrainSeed.Builder(new Bounds(0, 0, 0, 2, 1, 1), 4096);
        builder.append(new Cell(first, null, Biome.DESERT, (byte) 0, 0.8, 0.4));
        builder.append(new Cell(second, null, Biome.DESERT, (byte) 0, 0.8, 0.4));
        var seed = builder.finish();
        assertEquals(2, seed.paletteSize());
        assertEquals(8, ((com.projectkorra.projectkorra.platform.mc.block.data.Levelled) seed.cell(new Position(1, 0, 0)).data()).getLevel());
        assertEquals(0, ((com.projectkorra.projectkorra.platform.mc.block.data.Levelled) seed.cell(new Position(0, 0, 0)).data()).getLevel());
    }
}
