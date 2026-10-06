package com.projectkorra.projectkorra.prediction.rollback.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackLightSeedTest {
    @Test void layersStayDistinctAndDetachedAcrossTransfer() {
        var bounds = new RollbackBlockStore.Bounds(-2, -1, -1, 2, 2, 1);
        var seed = RollbackLightSeed.capture(bounds, 24, position -> position.x() + 2, position -> position.y() + 12);
        byte[] bytes = seed.encode(); var copy = RollbackLightSeed.decode(bytes, 24);
        java.util.Arrays.fill(bytes, (byte) 0);
        for (int y = -1; y < 2; y++) for (int z = -1; z < 1; z++) for (int x = -2; x < 2; x++) {
            var position = new RollbackBlockStore.Position(x, y, z);
            assertEquals(x + 2, copy.sky(position)); assertEquals(y + 12, copy.block(position));
        }
        assertArrayEquals(seed.encode(), copy.encode());
        assertThrows(IllegalArgumentException.class, () -> copy.sky(new RollbackBlockStore.Position(2, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> RollbackLightSeed.decode(seed.encode(), 23));
        assertThrows(IllegalArgumentException.class, () -> RollbackLightSeed.decode(java.util.Arrays.copyOf(seed.encode(), 51), 24));
        assertThrows(IllegalArgumentException.class, () -> RollbackLightSeed.capture(bounds, 24, position -> 16, position -> 0));
        assertThrows(IllegalArgumentException.class, () -> RollbackLightSeed.capture(bounds, 24, position -> 0, position -> -1));
    }
}
