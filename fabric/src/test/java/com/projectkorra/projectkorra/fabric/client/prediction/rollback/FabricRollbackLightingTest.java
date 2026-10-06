package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.*;
import net.minecraft.world.chunk.light.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackLightingTest {
    @BeforeAll static void bootstrap() { SharedConstants.createGameVersion(); Bootstrap.initialize(); }
    @Test void nativeLightingPropagatesAndRetractsInsideDetachedChunks() {
        var height = HeightLimitView.create(0, 32);
        var palettes = PalettesFactory.fromRegistryManager(FabricRollbackTestRegistry.simulationRegistries());
        var chunks = new HashMap<Long, ProtoChunk>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            var chunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.NO_UPGRADE_DATA, height, palettes, null);
            chunk.getChunkSkyLight().refreshSurfaceY(chunk);
            chunks.put(ChunkPos.toLong(x, z), chunk);
        }
        var center = chunks.get(ChunkPos.toLong(0, 0));
        var lighting = new LightingProvider(new ChunkProvider() {
            @Override public LightSourceView getChunk(int x, int z) { return chunks.get(ChunkPos.toLong(x, z)); }
            @Override public BlockView getWorld() { return center; }
        }, true, true);
        for (var chunk : chunks.values()) {
            var pos = chunk.getPos();
            lighting.setRetainData(pos, true);
            for (int y = -1; y <= 2; y++) {
                var section = ChunkSectionPos.from(pos, y);
                lighting.enqueueSectionData(LightType.BLOCK, section, new ChunkNibbleArray(0));
                lighting.enqueueSectionData(LightType.SKY, section, new ChunkNibbleArray(15));
            }
            for (int y = 0; y < 2; y++) lighting.setSectionStatus(ChunkSectionPos.from(pos, y), true);
            lighting.setColumnEnabled(pos, true);
        }
        settle(lighting);
        var source = new BlockPos(15, 8, 8); var neighbour = source.east();
        assertEquals(15, lighting.get(LightType.SKY).getLightLevel(source));
        center.getSection(0).setBlockState(15, 8, 8, Blocks.GLOWSTONE.getDefaultState());
        center.getChunkSkyLight().refreshSurfaceY(center);
        lighting.setSectionStatus(ChunkSectionPos.from(source), false);
        lighting.checkBlock(source); settle(lighting);
        assertEquals(15, lighting.get(LightType.BLOCK).getLightLevel(source));
        assertEquals(14, lighting.get(LightType.BLOCK).getLightLevel(neighbour));
        assertEquals(0, lighting.get(LightType.SKY).getLightLevel(source));
        center.getSection(0).setBlockState(15, 8, 8, Blocks.AIR.getDefaultState());
        center.getChunkSkyLight().refreshSurfaceY(center);
        lighting.setSectionStatus(ChunkSectionPos.from(source), true);
        lighting.checkBlock(source); settle(lighting);
        assertEquals(0, lighting.get(LightType.BLOCK).getLightLevel(neighbour));
        assertEquals(15, lighting.get(LightType.SKY).getLightLevel(source));
    }
    @Test void productionLightingRestoresBothLayersAndAlternativeTerrain() {
            var bounds = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds(-16, 0, -16, 32, 32, 32);
            var air = new com.projectkorra.projectkorra.platform.mc.block.data.BlockData(com.projectkorra.projectkorra.platform.mc.Material.AIR);
            air.setExactState("minecraft:air");
            var cell = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Cell(air, null,
                    com.projectkorra.projectkorra.platform.mc.block.Biome.DESERT, (byte) 15, .8, .4);
            var builder = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainSeed.Builder(bounds, 1_048_576);
            builder.append(cell, 48 * 48 * 32);
            var terrain = builder.finish();
            var light = com.projectkorra.projectkorra.prediction.rollback.world.RollbackLightSeed.capture(bounds, 48 * 48 * 32, p -> 15, p -> 0);
            var registry = FabricRollbackTestRegistry.simulationRegistries();
            var importedLight = com.projectkorra.projectkorra.prediction.rollback.world.RollbackLightSeed.capture(bounds, 48 * 48 * 32, p -> 3, p -> 10);
            var imported = new FabricRollbackLighting(terrain, importedLight, registry, true);
            var sample = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position(8, 8, 8);
            assertEquals(3, imported.sky(sample)); assertEquals(10, imported.block(sample));
            var importedCheckpoint = imported.captureRollbackState();
            imported.restoreRollbackState(importedCheckpoint);
            assertEquals(3, imported.sky(sample)); assertEquals(10, imported.block(sample));
            var lighting = new FabricRollbackLighting(terrain, light, registry, true);
            var initial = lighting.captureRollbackState();
            var source = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position(15, 8, 8);
            var neighbour = source.offset(1, 0, 0); var roof = source.offset(0, 1, 0);
            var glow = new com.projectkorra.projectkorra.platform.mc.block.data.BlockData(com.projectkorra.projectkorra.platform.mc.Material.GLOWSTONE);
            glow.setExactState("minecraft:glowstone");
            var stone = new com.projectkorra.projectkorra.platform.mc.block.data.BlockData(com.projectkorra.projectkorra.platform.mc.Material.STONE);
            stone.setExactState("minecraft:stone");
            lighting.apply(Map.of(source, glow, roof, stone));
            assertEquals(14, lighting.block(neighbour));
            assertEquals(0, lighting.sky(source));
            var changed = lighting.captureRollbackState();
            lighting.apply(Map.of(source, air, roof, air));
            assertEquals(0, lighting.block(neighbour)); assertEquals(15, lighting.sky(source));
            lighting.restoreRollbackState(changed);
            assertEquals(14, lighting.block(neighbour)); assertEquals(0, lighting.sky(source));
            lighting.restoreRollbackState(initial);
            assertEquals(0, lighting.block(neighbour)); assertEquals(15, lighting.sky(source));
            lighting.apply(Map.of(roof, stone));
            assertEquals(14, lighting.sky(source));
            lighting.restoreRollbackState(initial);
            assertEquals(15, lighting.sky(source));
            var edge = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position(-16, 8, 8);
            assertThrows(IllegalArgumentException.class, () -> lighting.apply(Map.of(source, glow, edge, glow)));
            assertEquals(0, lighting.block(source));
            var delegate = (com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Rules)
                    java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{
                            com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Rules.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("changed")) return null;
                                if (method.getName().equals("physics")) { assertEquals(14, lighting.sky(source)); return null; }
                                throw new AssertionError("Unexpected block rule: " + method);
                            });
            var store = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore(
                    new com.projectkorra.projectkorra.platform.mc.World(), terrain, lighting.rules(delegate, () -> 15), 100);
            var graph = new RollbackStateGraph(value -> false, field -> true, 100);
            var checkpoint = graph.capture(List.of(store), List.of());
            store.replace(source, new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Cell(
                    glow, null, cell.biome(), (byte) 0, .8, .4), false);
            assertEquals(14, store.block(neighbour.x(), neighbour.y(), neighbour.z()).getLightLevel());
            checkpoint.restore();
            assertEquals(air.getMaterial(), store.cell(source).data().getMaterial());
            assertEquals(0, store.block(neighbour.x(), neighbour.y(), neighbour.z()).getLightLevel());
            store.replace(roof, new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Cell(
                    stone, null, cell.biome(), (byte) 0, .8, .4), true);
            checkpoint.restore();
            assertEquals(15, lighting.sky(source));
            var environment = new FabricRollbackEnvironment[1];
            var logical = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld(
                    new com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld.Identity("arena",
                            com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld.Dimension.NORMAL, 0, 32),
                    new com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld.Conditions(0, 0, "NORMAL", false, Set.of()),
                    terrain, lighting.rules(delegate, () -> environment[0].skyDarkness()), 100, 10,
                    unused(com.projectkorra.projectkorra.prediction.rollback.world.RollbackItems.class),
                    unused(com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld.Queries.class),
                    unused(com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld.Actions.class));
            var spatial = new FabricRollbackSpatial(logical, registry,
                    FabricRollbackEnvironmentTest.seed(), new RollbackBorderData(0, 0, 29999984, .2, 5, 5, 15, 60000000, null), lighting, new net.minecraft.scoreboard.Scoreboard());
            environment[0] = spatial.environmentAttributes();
            var position = new BlockPos(source.x(), source.y(), source.z());
            var spatialCheckpoint = graph.capture(List.of(spatial), List.of());
            assertTrue(spatial.skyVisible(logical.terrain(), position));
            var lightView = logical.terrain().block(source.x(), source.y(), source.z());
            byte daytime = lightView.getLightLevel();
            var conditions = logical.conditions();
            logical.conditions(new com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld.Conditions(
                    18_000, 18_000, conditions.difficulty(), false, conditions.loadedChunks()));
            assertTrue(lightView.getLightLevel() < daytime);
            assertTrue(spatial.skyVisible(logical.terrain(), position), "Night does not occlude the sky");
            logical.terrain().replace(roof, new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Cell(
                    stone, null, cell.biome(), (byte) 0, .8, .4), false);
            assertFalse(spatial.skyVisible(logical.terrain(), position));
            spatial.border().setSize(20);
            spatial.environmentAttributes().weather(new RollbackEnvironmentData.Weather(1, 1));
            spatialCheckpoint.restore();
            assertTrue(spatial.skyVisible(logical.terrain(), position));
            assertEquals(daytime, lightView.getLightLevel());
            assertTrue(spatial.border().getSize() > 20);
            assertEquals(0, spatial.environmentAttributes().weather().rain());
            assertThrows(IllegalArgumentException.class, () -> spatial.skyVisible(store, position));
    }
    private static <T> T unused(Class<T> type) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> { throw new AssertionError("Unexpected service: " + method); }));
    }
    private static void settle(LightingProvider lighting) {
        for (int pass = 0; lighting.hasUpdates(); pass++) {
            if (pass == 100) fail("Detached lighting did not settle");
            lighting.doLightUpdates();
        }
    }
}
