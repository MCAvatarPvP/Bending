package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;

/** Captures the actual match world synchronously without loading chunks or changing live state. */
public final class PaperRollbackWorldSeed {
    private PaperRollbackWorldSeed() { }

    public static RollbackWorldSeed capture(ServerLevel world, Bounds bounds, long randomSeed, long soundSeed,
                                            PaperRollbackTerrainCapture.Limits limits) {
        Objects.requireNonNull(world); Objects.requireNonNull(bounds); Objects.requireNonNull(limits);
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active()) {
            throw new IllegalStateException("Capture the match world on the live tick thread before replay");
        }
        var bukkit = world.getWorld();
        if (bounds.minY() != bukkit.getMinHeight() || bounds.maxY() != bukkit.getMaxHeight()) {
            throw new IllegalArgumentException("Match capture must include the world's full build height");
        }
        long tick = world.getGameTime(), day = world.getDayTime();
        var settings = PaperRollbackWorldSettings.capture(world, randomSeed, soundSeed);
        var border = PaperRollbackBorder.capture(world.getWorldBorder());
        var environment = PaperRollbackEnvironment.capture(world);
        boolean storm = bukkit.hasStorm();
        var terrain = PaperRollbackTerrainCapture.capture(world, bounds, limits);
        var light = PaperRollbackLightCapture.capture(world, bounds, limits.maximumCells());
        if (world.getGameTime() != tick || world.getDayTime() != day || settings.gameTime() != tick) {
            throw new IllegalStateException("Match world advanced during bootstrap capture");
        }
        return new RollbackWorldSeed(bukkit.getUID(), bukkit.getName(), bounds.minY(), bounds.maxY(), day, storm,
                settings, border, environment, terrain, light);
    }
}
