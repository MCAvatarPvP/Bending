package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackPlayerItemsTest {
    @Test void aliasesAndEqualButDistinctItemsKeepTheirRootsAndExactCooldownIntervals() {
        var item = new RollbackPlayerItems.Item(new RollbackItemData(new byte[]{10, 0}), 4);
        var table = new ArrayList<>(List.of(item, item));
        var slots = new ArrayList<>(List.of(0, 1, 0));
        var seed = new RollbackPlayerItems(table, slots, List.of(1), 2, 32, 0, 1, -1,
                Map.of("MAINHAND", 0), Integer.MAX_VALUE, Map.of("test:group", new RollbackPlayerItems.Cooldown(Integer.MAX_VALUE - 1, Integer.MIN_VALUE + 20)));
        table.clear(); slots.clear();
        var decoded = RollbackPlayerItems.decode(seed.encode());
        assertArrayEquals(seed.encode(), decoded.encode());
        assertEquals(List.of(0, 1, 0), decoded.inventory()); assertEquals(List.of(1), decoded.enderChest());
        assertEquals(2, decoded.items().size()); assertEquals(decoded.items().get(0), decoded.items().get(1));
        assertEquals(0, decoded.useItem()); assertEquals(1, decoded.lastItem()); assertEquals(-1, decoded.spinItem());
        assertEquals(32, decoded.maximumStack());
        assertThrows(UnsupportedOperationException.class, () -> decoded.inventory().clear());
        assertThrows(UnsupportedOperationException.class, () -> decoded.cooldowns().clear());
    }

    @Test void invalidReferencesLayoutsUnusedObjectsAndMalformedWireRejectBeforeNativeDecode() {
        var item = new RollbackPlayerItems.Item(RollbackItemData.EMPTY, 0);
        var seed = new RollbackPlayerItems(List.of(item), List.of(0), List.of(0), 0, 64, 0, 0, -1, Map.of(), 18, Map.of());
        byte[] bytes = seed.encode();
        for (int i = 0; i < bytes.length; i++) {
            var truncated = Arrays.copyOf(bytes, i);
            assertThrows(IllegalArgumentException.class, () -> RollbackPlayerItems.decode(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerItems.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        byte[] badCount = bytes.clone(); ByteBuffer.wrap(badCount).putInt(4, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerItems.decode(badCount));
        byte[] badBlob = bytes.clone(); ByteBuffer.wrap(badBlob).putInt(8, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerItems.decode(badBlob));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerItems(List.of(item), List.of(1), List.of(), 0, 64, 0, 0, -1, Map.of(), 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerItems(List.of(item, item), List.of(0), List.of(), 0, 64, 0, 0, -1, Map.of(), 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerItems(List.of(item), List.of(0), List.of(), 1, 64, 0, 0, -1, Map.of(), 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerItems(List.of(item), List.of(0), List.of(), 0, 64, 0, 0, -2, Map.of(), 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerItems(List.of(item), List.of(0), List.of(), 0, 0, 0, 0, -1, Map.of(), 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerItems(List.of(item), List.of(0), List.of(), 0, 64, 0, 0, -1, Map.of(), 0, Map.of("bad key", new RollbackPlayerItems.Cooldown(0, 1))));
        var large = new RollbackPlayerItems.Item(new RollbackItemData(new byte[RollbackItemData.MAXIMUM_BYTES]), 0);
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerItems(Collections.nCopies(5, large), List.of(0, 1, 2, 3, 4), List.of(), 0, 64, 0, 0, -1, Map.of(), 0, Map.of()));
    }
}
