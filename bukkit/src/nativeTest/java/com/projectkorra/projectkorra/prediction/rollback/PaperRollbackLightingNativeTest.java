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
            return null;
        });
    }
}
