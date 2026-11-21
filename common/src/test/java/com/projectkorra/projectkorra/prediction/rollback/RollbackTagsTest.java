package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class RollbackTagsTest {
    @Test void capturedTagsAreDetachedCanonicalAndPreserveFacadeSemantics() {
        var values = new ArrayList<>(List.of(Material.STONE, Material.AIR, Material.DIRT, Material.STONE));
        var input = new HashMap<String, List<Material>>(); input.put("bendable", values);
        var source = new RollbackTags(input); values.clear(); input.clear();
        assertEquals(List.of(Material.DIRT, Material.STONE), source.values("BLOCKS", "bendable", Material.class));
        assertTrue(source.values("blocks", "missing", Material.class).isEmpty());
        assertTrue(source.values("items", "bendable", Material.class).isEmpty());
        assertTrue(source.values("blocks", "bendable", String.class).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> source.blocks().clear());
        assertThrows(UnsupportedOperationException.class, () -> source.blocks().get("bendable").clear());
        assertEquals(source, RollbackTags.decode(source.encode()));
        assertArrayEquals(source.encode(), new RollbackTags(Map.of("bendable", List.of(Material.STONE, Material.DIRT))).encode());
    }
    @Test void malformedAndUnboundedCatalogsReject() {
        var bytes = new RollbackTags(Map.of("stone", List.of(Material.STONE))).encode();
        for (int n = 0; n < bytes.length; n++) {
            byte[] truncated = Arrays.copyOf(bytes, n);
            assertThrows(IllegalArgumentException.class, () -> RollbackTags.decode(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackTags.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        byte[] count = ByteBuffer.allocate(8).putInt(1).putInt(Integer.MAX_VALUE).array();
        assertThrows(IllegalArgumentException.class, () -> RollbackTags.decode(count));
        assertThrows(IllegalArgumentException.class, () -> new RollbackTags(Map.of("minecraft:stone", List.of())));
        assertThrows(IllegalArgumentException.class, () -> new RollbackTags(Map.of("", List.of())));
    }
}
