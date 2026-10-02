package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerCombatData.*;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.Vector;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackPlayerCombatDataTest {
    private static final UUID A = new UUID(0, 7001), B = new UUID(0, 7002);
    private static final Tracker TRACKER = new Tracker(100, 100, 100, true, true);
    @Test void exactTrackerLinksAndAliasedOrEqualSourcesSurviveDetachedRoundTrip() {
        var source = new Source("minecraft:generic", null, null, B, new Vector(1.2, 3.4, 5.6), "CUSTOM", true);
        var sources = new ArrayList<>(List.of(source, source));
        var kinetic = new HashMap<>(Map.of(A, 18L));
        var seed = new RollbackPlayerCombatData(B, A, A, A, A, true, kinetic, TRACKER, sources, 0,
                List.of(new Entry(0, 3, null, 0), new Entry(0, 5, "generic", 7), new Entry(1, 5, "generic", 7)));
        sources.clear(); kinetic.clear();
        var decoded = RollbackPlayerCombatData.decode(seed.encode());
        assertEquals(seed, decoded); assertArrayEquals(seed.encode(), decoded.encode());
        assertEquals(List.of(0, 0, 1), decoded.entries().stream().map(Entry::source).toList());
        assertEquals(2, decoded.sources().size()); assertEquals(Set.of(A, B), decoded.references());
        assertDoesNotThrow(() -> decoded.requireRoster(Set.of(A, B)));
        assertThrows(IllegalArgumentException.class, () -> decoded.requireRoster(Set.of(B)));
        assertThrows(UnsupportedOperationException.class, () -> decoded.sources().clear());
        assertThrows(UnsupportedOperationException.class, () -> decoded.kinetic().clear());
    }
    @Test void missingSourcesUnreachableObjectsAndMalformedCountsFlagsAndNumbersReject() {
        var empty = new RollbackPlayerCombatData(A, null, null, null, null, false, Map.of(), TRACKER, List.of(), -1, List.of());
        byte[] bytes = empty.encode();
        for (int i = 0; i < bytes.length; i++) {
            byte[] truncated = Arrays.copyOf(bytes, i); assertThrows(IllegalArgumentException.class, () -> RollbackPlayerCombatData.decode(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerCombatData.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        byte[] flag = bytes.clone(); flag[20] = 2;
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerCombatData.decode(flag));
        byte[] count = bytes.clone(); ByteBuffer.wrap(count).putInt(25, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerCombatData.decode(count));
        byte[] reference = bytes.clone(); ByteBuffer.wrap(reference).putInt(47, 0);
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerCombatData.decode(reference));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerCombatData(A, null, null, null, null, false, Map.of(B, 0L), TRACKER, List.of(), -1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerCombatData(A, null, null, null, null, false, Map.of(), TRACKER, List.of(), -1, List.of(new Entry(0, 1, null, 0))));
        var source = new Source("minecraft:generic", null, null, null, null, null, false);
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerCombatData(A, null, null, null, null, false, Map.of(), TRACKER, List.of(source), -1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Source("bad type", null, null, null, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new Source("minecraft:generic", A, A, B, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new Entry(0, Float.NaN, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new Entry(0, 1, null, Float.POSITIVE_INFINITY));
    }
}
