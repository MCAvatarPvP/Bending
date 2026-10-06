package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackLightingBounds;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackLightingBoundsTest {
    @Test void capturesFullHeightAndChunkMarginAcrossNegativeAndPositiveEdges() {
        var arena = new Bounds(-17, 0, -1, 16, 80, 17);
        var captured = RollbackLightingBounds.capture(arena, -64, 320, 4_000_000);
        assertEquals(new Bounds(-48, -64, -32, 32, 320, 48), captured);
        for (int x : new int[]{arena.minX(), arena.maxX() - 1})
            for (int z : new int[]{arena.minZ(), arena.maxZ() - 1}) {
                assertTrue(captured.contains(new Position(x - 16, -64, z - 16)));
                assertTrue(captured.contains(new Position(x + 16, 319, z + 16)));
            }
    }
    @Test void includesTheMarginInBudgetsAndRejectsInvalidWorldHeights() {
        var arena = new Bounds(0, 0, 0, 16, 16, 16);
        assertEquals(new Bounds(-16, 0, -16, 32, 16, 32), RollbackLightingBounds.capture(arena, 0, 16, 36_864));
        assertThrows(IllegalArgumentException.class, () -> RollbackLightingBounds.capture(arena, 0, 16, 36_863));
        assertThrows(IllegalArgumentException.class, () -> RollbackLightingBounds.capture(arena, 0, 15, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> RollbackLightingBounds.capture(arena, 16, 32, 1_000_000));
        assertThrows(ArithmeticException.class, () -> RollbackLightingBounds.capture(
                new Bounds(Integer.MAX_VALUE - 1, 0, 0, Integer.MAX_VALUE, 16, 1), 0, 16, 1_000_000));
    }
}
