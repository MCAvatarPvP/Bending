package com.projectkorra.projectkorra.prediction.rollback.world;

import java.util.Objects;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;

/** Complete-height chunk capture with a full chunk of light-propagation margin around the arena. */
public final class RollbackLightingBounds {
    private RollbackLightingBounds() { }
    public static Bounds capture(Bounds arena, int minimumY, int maximumY, int maximumCells) {
        Objects.requireNonNull(arena);
        if (minimumY >= maximumY || ((minimumY | maximumY) & 15) != 0
                || arena.minY() < minimumY || arena.maxY() > maximumY)
            throw new IllegalArgumentException("Arena must fit the world's complete build sections");
        if (maximumCells < 1 || maximumCells > 16_777_216) throw new IllegalArgumentException("Lighting capture cell budget");
        long minX = Math.floorDiv((long) arena.minX(), 16) * 16 - 16;
        long minZ = Math.floorDiv((long) arena.minZ(), 16) * 16 - 16;
        long maxX = (Math.floorDiv((long) arena.maxX() - 1, 16) + 2) * 16;
        long maxZ = (Math.floorDiv((long) arena.maxZ() - 1, 16) + 2) * 16;
        long volume = Math.multiplyExact(Math.multiplyExact(maxX - minX, maxZ - minZ), (long) maximumY - minimumY);
        if (volume > maximumCells) throw new IllegalArgumentException("Arena and lighting margin exceed capture budget");
        return new Bounds(Math.toIntExact(minX), minimumY, Math.toIntExact(minZ),
                Math.toIntExact(maxX), maximumY, Math.toIntExact(maxZ));
    }
}
