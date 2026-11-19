package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;

/** Captures the arena plus full-height lighting margin without loading chunks or changing live state. */
public final class PaperRollbackWorldSeed {
    private PaperRollbackWorldSeed() { }

    public static RollbackWorldSeed capture(ServerLevel world, Bounds bounds, long randomSeed, long soundSeed,
                                            PaperRollbackTerrainCapture.Limits limits) {
        return capture(world, bounds, randomSeed, soundSeed, limits, null);
    }

    static RollbackWorldSeed capture(ServerLevel world, Bounds bounds, long randomSeed, long soundSeed,
            PaperRollbackTerrainCapture.Limits limits, PaperRollbackTerrainCapture.Prepared prepared) {
        return capture(world, bounds, randomSeed, soundSeed, limits, prepared, RollbackDuelInteractions.STANDARD);
    }

    static RollbackWorldSeed capture(ServerLevel world, Bounds bounds, long randomSeed, long soundSeed,
            PaperRollbackTerrainCapture.Limits limits, PaperRollbackTerrainCapture.Prepared prepared, RollbackDuelInteractions interactions) {
        Objects.requireNonNull(world); Objects.requireNonNull(bounds); Objects.requireNonNull(limits);
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active()) {
            throw new IllegalStateException("Capture the match world on the live tick thread before replay");
        }
        var bukkit = world.getWorld();
        var scheduled = PaperRollbackLiveScheduledTicks.capture(world, bounds);
        bounds = com.projectkorra.projectkorra.prediction.rollback.world.RollbackLightingBounds.capture(
                bounds, bukkit.getMinHeight(), bukkit.getMaxHeight(), limits.maximumCells());
        long tick = world.getGameTime(), day = world.getDayTime();
        var structures = PaperRollbackStructureCapture.capture(world, bounds, limits.maximumLoadedChunks());
        var paper = world.paperConfig();
        var despawn = paper.entities.spawning.altItemDespawnRate;
        var overrides = new java.util.TreeMap<String, Integer>();
        if (despawn.enabled) despawn.items.forEach((item, ticks) ->
                overrides.put(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString(), ticks));
        var items = new RollbackItemRules(world.spigotConfig.itemDespawnRate, overrides, world.spigotConfig.itemMerge,
                paper.entities.behavior.onlyMergeItemsHorizontally, paper.fixes.fixItemsMergingThroughWalls);
        var settings = PaperRollbackWorldSettings.capture(world, randomSeed, soundSeed)
                .withStructures(structures).withInteractions(interactions.withItems(items));
        var border = PaperRollbackBorder.capture(world.getWorldBorder());
        var environment = PaperRollbackEnvironment.capture(world);
        boolean storm = bukkit.hasStorm();
        var terrain = prepared == null ? PaperRollbackTerrainCapture.capture(world, bounds, limits) : prepared.terrain().join();
        var light = prepared == null ? PaperRollbackLightCapture.capture(world, bounds, limits.maximumCells()) : prepared.light();
        if (!terrain.bounds().equals(bounds) || !light.bounds().equals(bounds)) throw new IllegalStateException("Prepared terrain bounds changed");
        if (world.getGameTime() != tick || world.getDayTime() != day || settings.gameTime() != tick) {
            throw new IllegalStateException("Match world advanced during bootstrap capture");
        }
        return new RollbackWorldSeed(bukkit.getUID(), bukkit.getName(), bounds.minY(), bounds.maxY(), day, storm,
                settings, border, environment, terrain, light, scheduled);
    }
}
