package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.*;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.bukkit.craftbukkit.CraftWorld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackTerrainImageNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void detachedSectionExpansionUsesExactClimateAndRejectsChangesToBlocksLightAndBiomes() throws Exception {
        onTickThread(() -> {
            var registry = PaperRollbackEnvironmentNativeTest.registries();
            var containers = PalettedContainerFactory.create(registry);
            var section = new LevelChunkSection(containers);
            section.setBlockState(2, 10, 3, Blocks.REDSTONE_BLOCK.defaultBlockState());
            var noise = section.getNoiseBiome(0, 0, 0);
            var manager = new BiomeManager((x, y, z) -> noise, 1975);
            var block = new SWMRNibbleArray(); block.setZero(); block.set(2, 10, 3, 6); block.updateVisible();
            var sky = new SWMRNibbleArray(); sky.setFull(); sky.updateVisible();
            var chunk = RollbackNativeQueryShell.create(LevelChunk.class)
                    .constant(LevelChunk::getSections, new LevelChunkSection[]{section})
                    .constant(LevelChunk::getBlockEntitiesPos, Set.of())
                    .constant(LevelChunk::starlight$getSkyNibbles, new SWMRNibbleArray[]{sky})
                    .constant(LevelChunk::starlight$getBlockNibbles, new SWMRNibbleArray[]{block})
                    .constant(LevelChunk::isLightCorrect, true).instance();
            var cache = RollbackNativeQueryShell.create(ServerChunkCache.class)
                    .query(value -> value.getChunkAtIfLoadedImmediately(0, 0), null, args -> chunk).instance();
            var bukkit = RollbackNativeQueryShell.create(CraftWorld.class).constant(CraftWorld::getUID, new UUID(0, 63)).instance();
            var lighting = RollbackNativeQueryShell.create(LevelLightEngine.class).constant(LevelLightEngine::getMinLightSection, 0).instance();
            var dimension = registry.lookupOrThrow(Registries.DIMENSION_TYPE)
                    .get(net.minecraft.resources.Identifier.parse("minecraft:overworld")).orElseThrow().value();
            var level = RollbackNativeQueryShell.create(ServerLevel.class).constant(ServerLevel::getBiomeManager, manager)
                    .constant(ServerLevel::getWorld, bukkit).constant(ServerLevel::getSeaLevel, 4).constant(ServerLevel::getSkyDarken, 3)
                    .constant(ServerLevel::dimensionType, dimension).constant(ServerLevel::getMinSectionY, 0)
                    .constant(ServerLevel::getMinY, 0).constant(ServerLevel::getMaxY, 15)
                    .constant(ServerLevel::getChunkSource, cache).constant(ServerLevel::getLightEngine, lighting).instance();
            var bounds = new Bounds(0, 0, 0, 16, 16, 16);
            var prepared = PaperRollbackTerrainCapture.prepare(level, bounds, new PaperRollbackTerrainCapture.Limits(4096, 9, 1048576, 4096));
            var seed = prepared.terrain().get(10, java.util.concurrent.TimeUnit.SECONDS);
            var pos = new Position(2, 10, 3);
            assertEquals(Material.REDSTONE_BLOCK, seed.cell(pos).data().getMaterial());
            assertEquals(noise.value().getTemperature(new BlockPos(2, 10, 3), 4), seed.cell(pos).temperature());
            assertEquals(12, seed.cell(pos).light());
            assertEquals(15, prepared.light().sky(pos));
            assertEquals(6, prepared.light().block(pos));
            assertTrue(prepared.matches(level));
            block.set(2, 10, 3, 9); block.updateVisible();
            assertFalse(prepared.matches(level));
            assertEquals(6, prepared.light().block(pos));
            block.set(2, 10, 3, 6); block.updateVisible();
            assertTrue(prepared.matches(level));
            section.setBlockState(2, 10, 3, Blocks.GOLD_BLOCK.defaultBlockState());
            assertFalse(prepared.matches(level));
            assertEquals(Material.REDSTONE_BLOCK, seed.cell(pos).data().getMaterial());
            var changed = PaperRollbackTerrainCapture.prepare(level, bounds, new PaperRollbackTerrainCapture.Limits(4096, 9, 1048576, 4096));
            changed.terrain().get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(changed.matches(level));
            var desert = registry.lookupOrThrow(Registries.BIOME).get(net.minecraft.resources.Identifier.parse("minecraft:desert")).orElseThrow();
            var plains = registry.lookupOrThrow(Registries.BIOME).get(net.minecraft.resources.Identifier.parse("minecraft:plains")).orElseThrow();
            section.setBiome(0, 0, 0, noise.equals(desert) ? plains : desert);
            assertFalse(changed.matches(level));
            return null;
        });
    }
}
