package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackLightSeed;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import java.util.Objects;

/** Captures native light layers without loading chunks or deriving sunlight from combined brightness. */
public final class PaperRollbackLightCapture {
    private PaperRollbackLightCapture() { }
    public static RollbackLightSeed capture(ServerLevel world, Bounds bounds, int maximumCells) {
        Objects.requireNonNull(world); Objects.requireNonNull(bounds);
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Capture light on the live server tick thread");
        long count = ((long) bounds.maxX() - bounds.minX()) * ((long) bounds.maxY() - bounds.minY()) * ((long) bounds.maxZ() - bounds.minZ());
        if (maximumCells < 1 || count > maximumCells) throw new IllegalArgumentException("Light cell budget");
        for (int x = Math.floorDiv(bounds.minX(), 16); x <= Math.floorDiv(bounds.maxX() - 1, 16); x++)
            for (int z = Math.floorDiv(bounds.minZ(), 16); z <= Math.floorDiv(bounds.maxZ() - 1, 16); z++)
                if (world.getChunkSource().getChunkAtIfLoadedImmediately(x, z) == null)
                    throw new IllegalStateException("Light capture requires loaded chunk " + x + "," + z);
        long tick = world.getGameTime();
        var captured = RollbackLightSeed.capture(bounds, maximumCells,
                position -> world.getBrightness(LightLayer.SKY, new BlockPos(position.x(), position.y(), position.z())),
                position -> world.getBrightness(LightLayer.BLOCK, new BlockPos(position.x(), position.y(), position.z())));
        if (world.getGameTime() != tick) throw new IllegalStateException("World advanced during light capture");
        return captured;
    }
}
