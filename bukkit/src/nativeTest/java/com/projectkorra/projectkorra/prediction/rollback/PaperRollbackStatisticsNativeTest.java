package com.projectkorra.projectkorra.prediction.rollback;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spigotmc.SpigotConfig;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackStatisticsNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void nativeCounterEventsCoverCustomItemBlockAndEntityStatisticsAndRewindWithPlayer() throws Exception {
        onTickThread(() -> {
            var deaths = Stats.CUSTOM.get(Stats.DEATHS);
            var scene = new Scene(new PaperRollbackStatistics.Seed(false, Map.of(), Map.of(deaths, 5)));
            var stats = scene.player.getStats();
            var saved = scene.snapshot();
            stats.increment(scene.player, deaths, 2);
            stats.increment(scene.player, Stats.ITEM_USED.get(Items.DIAMOND_SWORD), 3);
            stats.increment(scene.player, Stats.BLOCK_MINED.get(Blocks.STONE), 4);
            stats.increment(scene.player, Stats.ENTITY_KILLED.get(EntityType.ZOMBIE), 1);
            assertEquals(7, stats.getValue(deaths));
            assertEquals(3, stats.getValue(Stats.ITEM_USED, Items.DIAMOND_SWORD));
            assertEquals(4, stats.getValue(Stats.BLOCK_MINED, Blocks.STONE));
            assertEquals(1, stats.getValue(Stats.ENTITY_KILLED, EntityType.ZOMBIE));
            assertTrue(scene.combat.events.contains("stat:DEATHS:5:7:null:null"));
            assertTrue(scene.combat.events.contains("stat:USE_ITEM:0:3:DIAMOND_SWORD:null"));
            assertTrue(scene.combat.events.contains("stat:MINE_BLOCK:0:4:STONE:null"));
            assertTrue(scene.combat.events.contains("stat:KILL_ENTITY:0:1:null:ZOMBIE"));
            var events = List.copyOf(scene.combat.events);
            scene.player.setHealth(8);
            saved.restore();
            assertEquals(5, stats.getValue(deaths));
            assertEquals(0, stats.getValue(Stats.ITEM_USED, Items.DIAMOND_SWORD));
            assertEquals(0, stats.getValue(Stats.BLOCK_MINED, Blocks.STONE));
            assertEquals(0, stats.getValue(Stats.ENTITY_KILLED, EntityType.ZOMBIE));
            assertEquals(20, scene.player.getHealth());
            assertTrue(scene.combat.events.isEmpty());
            stats.increment(scene.player, deaths, 2);
            stats.increment(scene.player, Stats.ITEM_USED.get(Items.DIAMOND_SWORD), 3);
            stats.increment(scene.player, Stats.BLOCK_MINED.get(Blocks.STONE), 4);
            stats.increment(scene.player, Stats.ENTITY_KILLED.get(EntityType.ZOMBIE), 1);
            assertEquals(events, scene.combat.events);
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void capturedForcedAndDisabledPoliciesNeverReadLaterGlobalSettings() throws Exception {
        onTickThread(() -> {
            var jump = Stats.CUSTOM.get(Stats.JUMP);
            var deaths = Stats.CUSTOM.get(Stats.DEATHS);
            var forced = new Scene(new PaperRollbackStatistics.Seed(false, Map.of(Stats.DEATHS, 7), Map.of(deaths, 2)));
            var disabled = new Scene(new PaperRollbackStatistics.Seed(true, Map.of(), Map.of(jump, 9)));
            boolean oldDisabled = SpigotConfig.disableStatSaving;
            var oldForced = SpigotConfig.forcedStats;
            try {
                SpigotConfig.disableStatSaving = true;
                SpigotConfig.forcedStats = Map.of(Stats.JUMP, 1000);
                forced.player.getStats().increment(forced.player, deaths, 3);
                forced.player.getStats().increment(forced.player, jump, 1);
                assertEquals(7, forced.player.getStats().getValue(deaths));
                assertEquals(1, forced.player.getStats().getValue(jump));
                assertTrue(forced.combat.events.contains("stat:DEATHS:7:10:null:null"));
                SpigotConfig.disableStatSaving = false;
                SpigotConfig.forcedStats = Map.of();
                disabled.player.getStats().increment(disabled.player, jump, 4);
                disabled.player.getStats().setValue(disabled.player, jump, 0);
                assertEquals(9, disabled.player.getStats().getValue(jump));
                assertTrue(disabled.combat.events.contains("stat:JUMP:9:13:null:null"));
            } finally {
                SpigotConfig.disableStatSaving = oldDisabled;
                SpigotConfig.forcedStats = oldForced;
            }
            return null;
        });
    }

    @Test void cancellationOverflowAndNativeHighFrequencyEventSuppressionArePreserved() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(PaperRollbackStatistics.Seed.fresh());
            var stats = scene.player.getStats();
            var jump = Stats.CUSTOM.get(Stats.JUMP);
            var saved = scene.snapshot();
            scene.combat.cancelStatistics = true;
            stats.increment(scene.player, jump, 4);
            assertEquals(0, stats.getValue(jump));
            saved.restore();
            assertFalse(scene.combat.cancelStatistics);
            assertTrue(scene.combat.events.isEmpty());
            stats.increment(scene.player, jump, 4);
            assertEquals(4, stats.getValue(jump));
            int eventCount = scene.combat.events.size();
            stats.increment(scene.player, Stats.CUSTOM.get(Stats.PLAY_TIME), 10);
            assertEquals(10, stats.getValue(Stats.CUSTOM, Stats.PLAY_TIME));
            assertEquals(eventCount, scene.combat.events.size());
            stats.setValue(null, jump, Integer.MAX_VALUE - 1);
            stats.increment(scene.player, jump, 10);
            assertEquals(Integer.MAX_VALUE, stats.getValue(jump));
            stats.setValue(null, jump, 0);
            assertEquals(0, stats.getValue(jump));
            return null;
        });
    }

    @Test void pendingUpdatesRewindAndForeignPlayersThreadsAndPersistenceAreRejected() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(PaperRollbackStatistics.Seed.fresh());
            var foreign = new Scene(PaperRollbackStatistics.Seed.fresh());
            var jump = Stats.CUSTOM.get(Stats.JUMP);
            var adapter = new PaperRollbackStatistics(scene.world, () -> scene.player,
                    new PaperRollbackStatistics.Seed(false, Map.of(), Map.of(jump, 2)));
            var stats = adapter.counter();
            assertTrue(adapter.pendingValues().isEmpty());
            var clean = adapter.captureRollbackState();
            stats.markAllDirty();
            assertEquals(Map.of(jump, 2), adapter.pendingValues());
            var pending = adapter.captureRollbackState();
            stats.increment(scene.player, jump, 3);
            assertEquals(Map.of(jump, 5), adapter.pendingValues());
            adapter.restoreRollbackState(pending);
            assertEquals(Map.of(jump, 2), adapter.pendingValues());
            adapter.restoreRollbackState(clean);
            assertTrue(adapter.pendingValues().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> stats.increment(foreign.player, jump, 1));
            assertThrows(IllegalArgumentException.class, () -> stats.setValue(foreign.player, jump, 1));
            assertThrows(IllegalStateException.class, stats::save);
            assertThrows(IllegalStateException.class, () -> stats.sendStats(scene.player));
            var failure = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> stats.increment(scene.player, jump, 1)).join());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(2, stats.getValue(jump));
            return null;
        });
    }

    private static final class Scene {
        final PaperRollbackDamageNativeTest.Combat combat = new PaperRollbackDamageNativeTest.Combat();
        final PaperRollbackWorldAccess world = new PaperRollbackWorldAccess(new PaperRollbackWorldAccessNativeTest.Queries(), combat, 4);
        final PaperRollbackNativePlayerState state;
        final ServerPlayer player;
        Scene(PaperRollbackStatistics.Seed statistics) {
            state = PaperRollbackNativePlayerState.serverPlayer(world, new GameProfile(new UUID(0, 95), "rollback_stats"),
                    ClientInformation.createDefault(), GameType.SURVIVAL, 91, 100_000, statistics);
            player = state.use(value -> (ServerPlayer) value);
        }
        RollbackStateGraph.Snapshot snapshot() { return new RollbackStateGraph(value -> false, field -> true, 100_000).capture(List.of(state), List.of()); }
    }
}
