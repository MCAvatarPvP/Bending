package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.*;
import net.minecraft.world.ticks.*;
import org.bukkit.craftbukkit.CraftWorld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;
import java.util.*;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackLiveScheduledTicksTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void returnsOnlyOwnedTicksAtTheConfirmedRemainingDelayAndPreservesForeignWork() throws Exception {
        onTickThread(() -> {
            var pos = new BlockPos(1, 2, 3); var outside = new BlockPos(12, 2, 3);
            var region = new RollbackBlockStore.Bounds(0, 0, 0, 8, 16, 8);
            var blockQueue = new LevelChunkTicks<Block>(); var fluidQueue = new LevelChunkTicks<Fluid>();
            var blocks = new LevelTicks<Block>(chunk -> true); var fluids = new LevelTicks<Fluid>(chunk -> true);
            var chunkPos = new ChunkPos(0, 0);
            blocks.addContainer(chunkPos, blockQueue); fluids.addContainer(chunkPos, fluidQueue);
            blocks.schedule(new ScheduledTick<>(Blocks.STONE, pos, 26, TickPriority.HIGH, 5));
            blocks.schedule(new ScheduledTick<>(Blocks.STONE, outside, 27, TickPriority.NORMAL, 6));
            var bukkit = RollbackNativeQueryShell.create(CraftWorld.class).constant(CraftWorld::getUID, new UUID(0, 41)).instance();
            var chunk = RollbackNativeQueryShell.create(LevelChunk.class)
                    .constant(LevelChunk::getBlockTicks, blockQueue).constant(LevelChunk::getFluidTicks, fluidQueue).instance();
            var cache = RollbackNativeQueryShell.create(ServerChunkCache.class)
                    .query(value -> value.getChunkAtIfLoadedImmediately(0, 0), null, args -> chunk).instance();
            var allocate = new ObjenesisStd(false);
            var config = allocate.newInstance(io.papermc.paper.configuration.WorldConfiguration.class);
            config.environment = allocate.newInstance(io.papermc.paper.configuration.WorldConfiguration.Environment.class);
            config.environment.maxBlockTicks = 23; config.environment.maxFluidTicks = 9;
            long[] time = {20}, sequence = {100};
            var world = RollbackNativeQueryShell.create(ServerLevel.class).constant(ServerLevel::isHandlingTick, false)
                    .constant(ServerLevel::getBlockTicks, blocks).constant(ServerLevel::getFluidTicks, fluids)
                    .constant(ServerLevel::paperConfig, config).constant(ServerLevel::getWorld, bukkit)
                    .constant(ServerLevel::getChunkSource, cache).query(ServerLevel::getGameTime, 0L, args -> time[0])
                    .query(ServerLevel::nextSubTickCount, 0L, args -> sequence[0]++).instance();
            var original = PaperRollbackLiveScheduledTicks.capture(world, region);
            assertEquals(1, original.entries().size()); assertEquals(23, original.maximumBlockTicks());
            int count = 8 * 16 * 8;
            var terrain = new RollbackTerrainSeed.Builder(region, 8_192);
            var air = Material.AIR.createBlockData(); air.setExactState("minecraft:air");
            terrain.append(new RollbackBlockStore.Cell(air, null, Biome.DESERT, (byte) 0, .8, .4), count);
            var seed = new RollbackWorldSeed(bukkit.getUID(), "duel", 0, 16, 0, false,
                    PaperRollbackWorldServicesNativeTest.settings(), new RollbackBorderData(0, 0, 60, .2, 5, 5, 15, 60, null),
                    PaperRollbackEnvironmentNativeTest.seed(), terrain.finish(), RollbackLightSeed.capture(region, count, p -> 0, p -> 0), original);
            var lease = PaperRollbackLiveScheduledTicks.prepare(world, seed); lease.acquire();
            assertFalse(blocks.hasScheduledTick(pos, Blocks.STONE)); assertTrue(blocks.hasScheduledTick(outside, Blocks.STONE));
            var target = new RollbackScheduledTicks.Entry(RollbackScheduledTicks.Kind.FLUID,
                    new RollbackBlockStore.Position(1, 2, 3), "minecraft:water", 32, -1, 6);
            var outgoing = new RollbackScheduledTicks(region, 23, 9, 7, List.of(target));
            lease.stage(outgoing, 28);
            time[0] = 100;
            fluids.schedule(new ScheduledTick<>(Fluids.WATER, pos, 105, TickPriority.LOW, 7));
            assertThrows(IllegalStateException.class, lease::restore);
            assertEquals(105, fluidQueue.peek().triggerTick());
            fluidQueue.removeIf(tick -> tick.type() == Fluids.WATER && tick.pos().equals(pos));
            lease.restore(); lease.restore();
            assertTrue(blocks.hasScheduledTick(outside, Blocks.STONE));
            assertFalse(blocks.hasScheduledTick(pos, Blocks.STONE));
            assertEquals(104, fluidQueue.peek().triggerTick());
            assertEquals(TickPriority.HIGH, fluidQueue.peek().priority());
            assertEquals(1, fluidQueue.count());
            return null;
        });
    }
}
