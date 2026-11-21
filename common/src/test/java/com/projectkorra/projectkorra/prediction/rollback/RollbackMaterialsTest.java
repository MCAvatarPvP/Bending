package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackMaterialsTest {
    @Test void capturesEveryMaterialOnceAndRetainsNoLiveService() {
        var calls = EnumSet.noneOf(Material.class);
        var live = EnumSet.of(Material.STONE, Material.DIRT);
        var captured = RollbackMaterials.capture(material -> { assertTrue(calls.add(material)); return live.contains(material); });
        assertEquals(EnumSet.allOf(Material.class), calls);
        live.clear();
        assertTrue(captured.isSolid(Material.STONE)); assertFalse(captured.isSolid(Material.AIR));
        assertThrows(UnsupportedOperationException.class, () -> captured.solid().clear());
        var copy = RollbackMaterials.decode(captured.encode());
        assertEquals(captured, copy); assertArrayEquals(captured.encode(), copy.encode());
        var snapshot = new RollbackStateGraph(value -> false, field -> true, 100).capture(List.of(copy), List.of());
        snapshot.restore(); assertTrue(copy.isSolid(Material.DIRT));
    }
    @Test void malformedAndIncompleteDefinitionsReject() {
        byte[] bytes = new RollbackMaterials(Set.of(Material.STONE)).encode();
        for (int i = 0; i < bytes.length; i++) {
            byte[] truncated = Arrays.copyOf(bytes, i);
            assertThrows(IllegalArgumentException.class, () -> RollbackMaterials.decode(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackMaterials.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        var count = bytes.clone(); ByteBuffer.wrap(count).putInt(4, Material.values().length - 1);
        assertThrows(IllegalArgumentException.class, () -> RollbackMaterials.decode(count));
        var name = bytes.clone(); name[10] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> RollbackMaterials.decode(name));
        var flag = bytes.clone(); flag[10 + Material.values()[0].name().length()] = 2;
        assertThrows(IllegalArgumentException.class, () -> RollbackMaterials.decode(flag));
    }
}
