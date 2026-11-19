package com.projectkorra.projectkorra.prediction.rollback.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackLightSeedTest {
    @Test void sectionNibblesMatchIndividualCellsAcrossChunkAndSectionBoundaries() {
        var bounds = new RollbackBlockStore.Bounds(-32, -16, -16, 16, 16, 16);
        var seed = RollbackLightSeed.capture(bounds, 49152, p -> (p.x() + p.y()) & 15, p -> (p.x() - p.z()) & 15);
        for (int x = -32; x < 16; x += 16) for (int y = -16; y < 16; y += 16) for (int z = -16; z < 16; z += 16) {
            var section = seed.section(x, y, z);
            assertTrue(section.hasSkyLight());
            for (int i = 0; i < 4096; i++) {
                var p = new RollbackBlockStore.Position(x + (i & 15), y + (i >> 8), z + ((i >> 4) & 15));
                int shift = (i & 1) * 4;
                assertEquals(seed.sky(p), (section.sky()[i >> 1] >> shift) & 15);
                assertEquals(seed.block(p), (section.block()[i >> 1] >> shift) & 15);
            }
            section.sky()[0] ^= 1;
            assertNotEquals(section.sky()[0], seed.section(x, y, z).sky()[0]);
        }
        assertThrows(IllegalArgumentException.class, () -> seed.section(-31, -16, -16));
        assertThrows(IllegalArgumentException.class, () -> seed.section(16, 0, 0));
        var dark = RollbackLightSeed.capture(new RollbackBlockStore.Bounds(0, 0, 0, 16, 16, 16), 4096, p -> 0, p -> 15);
        assertFalse(dark.section(0, 0, 0).hasSkyLight());
    }

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
