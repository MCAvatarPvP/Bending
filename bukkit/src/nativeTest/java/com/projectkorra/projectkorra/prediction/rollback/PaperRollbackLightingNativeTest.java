package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.patches.starlight.chunk.StarlightChunk;
import ca.spottedleaf.moonrise.patches.starlight.light.BlockStarLightEngine;
import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

/** Native propagation feasibility against detached sections, with no live world or chunk scheduler. */
class PaperRollbackLightingNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }
    @Test void nativeLightingPropagatesAndRetractsInsideDetachedChunks() throws Exception {
        onTickThread(() -> {
            var registry = (RegistryAccess) new PaperRollbackDamageNativeTest.Combat().registryAccess();
            var containers = PalettedContainerFactory.create(registry);
            var height = LevelHeightAccessor.create(0, 32);
            var chunks = new HashMap<Long, ProtoChunk>();
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                var chunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, height, containers, null);
                chunk.setPersistedStatus(ChunkStatus.LIGHT); chunk.setLightCorrect(true);
                var nibble = new SWMRNibbleArray[4];
                for (int i = 0; i < nibble.length; i++) { nibble[i] = new SWMRNibbleArray(); nibble[i].setZero(); nibble[i].updateVisible(); }
                ((StarlightChunk) chunk).starlight$setBlockNibbles(nibble);
                ((StarlightChunk) chunk).starlight$setBlockEmptinessMap(new boolean[]{true, true});
                chunks.put(ChunkPos.asLong(x, z), chunk);
            }
            var world = RollbackNativeQueryShell.create(Level.class)
                    .constant(Level::isClientSide, true)
                    .constant(Level::getMinY, 0).constant(Level::getHeight, 32)
                    .constant(Level::getMinSectionY, 0).constant(Level::getMaxSectionY, 1)
                    .constant(Level::getSectionsCount, 2).instance();
            LightChunkGetter getter = new LightChunkGetter() {
                @Override public LightChunk getChunkForLighting(int x, int z) { return chunks.get(ChunkPos.asLong(x, z)); }
                @Override public BlockGetter getLevel() { return world; }
            };
            var engine = new BlockStarLightEngine(); engine.setWorld(world);
            var center = chunks.get(ChunkPos.asLong(0, 0)); var source = new BlockPos(8, 8, 8);
            center.getSections()[0].setBlockState(8, 8, 8, Blocks.GLOWSTONE.defaultBlockState());
            engine.blocksChangedInChunk(getter, 0, 0, Set.of(source), new Boolean[]{false, true});
            var layers = ((StarlightChunk) center).starlight$getBlockNibbles();
            assertEquals(15, layers[1].getVisible(8, 8, 8));
            assertEquals(14, layers[1].getVisible(9, 8, 8));
            center.getSections()[0].setBlockState(8, 8, 8, Blocks.AIR.defaultBlockState());
            engine.blocksChangedInChunk(getter, 0, 0, Set.of(source), new Boolean[]{true, true});
            assertEquals(0, ((StarlightChunk) center).starlight$getBlockNibbles()[1].getVisible(9, 8, 8));
            engine.setWorld(null);
            for (var chunk : chunks.values()) {
                var sunlight = new SWMRNibbleArray[4];
                for (int i = 0; i < sunlight.length; i++) { sunlight[i] = new SWMRNibbleArray(); sunlight[i].setFull(); sunlight[i].updateVisible(); }
                ((StarlightChunk) chunk).starlight$setSkyNibbles(sunlight);
                ((StarlightChunk) chunk).starlight$setSkyEmptinessMap(new boolean[]{true, true});
            }
            var reader = new net.minecraft.world.level.lighting.LevelLightEngine(getter, true, true).starlight$getLightEngine();
            var skyEngine = new ca.spottedleaf.moonrise.patches.starlight.light.SkyStarLightEngine(); skyEngine.setWorld(world);
            var roof = new BlockPos(8, 9, 8);
            center.getSections()[0].setBlockState(8, 9, 8, Blocks.STONE.defaultBlockState());
            skyEngine.blocksChangedInChunk(getter, 0, 0, Set.of(roof), new Boolean[]{false, true});
            assertEquals(14, reader.getSkyLightValue(new BlockPos(8, 8, 8), center));
            center.getSections()[0].setBlockState(8, 9, 8, Blocks.AIR.defaultBlockState());
            skyEngine.blocksChangedInChunk(getter, 0, 0, Set.of(roof), new Boolean[]{true, true});
            assertEquals(15, reader.getSkyLightValue(new BlockPos(8, 8, 8), center));
            skyEngine.setWorld(null);
            return null;
        });
    }
    @Test void productionLightingRewindsNativePropagationAndRejectsMutationsWithoutHalo() throws Exception {
        onTickThread(() -> {
            var bounds = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds(-16, 0, -16, 32, 32, 32);
            var air = new com.projectkorra.projectkorra.platform.mc.block.data.BlockData(com.projectkorra.projectkorra.platform.mc.Material.AIR);
            air.setExactState("minecraft:air");
            var cell = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Cell(air, null,
                    com.projectkorra.projectkorra.platform.mc.block.Biome.DESERT, (byte) 15, .8, .4);
            var builder = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainSeed.Builder(bounds, 1_048_576);
            builder.append(cell, 48 * 48 * 32);
            var terrain = builder.finish();
            var light = com.projectkorra.projectkorra.prediction.rollback.world.RollbackLightSeed.capture(bounds, 48 * 48 * 32, p -> 15, p -> 0);
            var registry = (RegistryAccess) new PaperRollbackDamageNativeTest.Combat().registryAccess();
            var lighting = new PaperRollbackLighting(terrain, light, registry, true);
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
            var environment = new PaperRollbackEnvironment[1];
            var logical = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld(
                    new com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld.Identity("arena",
                            com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld.Dimension.NORMAL, 0, 32),
                    new com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld.Conditions(0, 0, "NORMAL", false, Set.of()),
                    terrain, lighting.rules(delegate, () -> environment[0].skyDarkness()), 100, 10,
                    unused(com.projectkorra.projectkorra.prediction.rollback.world.RollbackItems.class),
                    unused(com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld.Queries.class),
                    unused(com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld.Actions.class));
            var spatial = new PaperRollbackSpatial(logical, (RegistryAccess.Frozen) registry,
                    PaperRollbackEnvironmentNativeTest.seed(), PaperRollbackBorder.capture(new net.minecraft.world.level.border.WorldBorder()), lighting);
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
            return null;
        });
    }
    private static <T> T unused(Class<T> type) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> { throw new AssertionError("Unexpected service: " + method); }));
    }
}
