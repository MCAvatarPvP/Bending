package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackGameplayCatalogTest {
    @Test void localInventoryIncludesGameplayAcrossElementsAndBuildsAStableSchema() {
        var types = RollbackGameplayCatalog.installed(getClass().getClassLoader());
        assertTrue(types.contains(com.projectkorra.projectkorra.airbending.AirBlast.class));
        assertTrue(types.contains(com.projectkorra.projectkorra.earthbending.EarthBlast.class));
        assertTrue(types.contains(com.projectkorra.projectkorra.waterbending.WaterManipulation.class));
        assertTrue(types.contains(com.projectkorra.projectkorra.firebending.FireBlast.class));
        assertFalse(types.contains(getClass()), "Tests are not part of the installed gameplay inventory");
        var first = RollbackGameplayCatalog.create(types, List.of());
        var reversed = new ArrayList<>(types); Collections.reverse(reversed);
        assertArrayEquals(first.fingerprint(), RollbackGameplayCatalog.create(reversed, List.of()).fingerprint());
    }
    private static int initializations;
    private enum AUnused { VALUE; static { initializations++; } }
    private enum Empty { }
    private enum Key { VALUE }

    @Test void catalogAndEmptyMapCaptureDoNotInitializeUnrelatedEnums() {
        var catalog = RollbackGameplayCatalog.create(List.of(AUnused.class, Empty.class, Key.class), List.of());
        assertEquals(0, initializations);
        var codec = new RollbackGraphCodec(catalog,
                new RollbackGraphCodec.Limits(100, 100, 10000, 1000));
        var source = new EnumMap<Key, Integer>(Key.class);
        var empty = new EnumMap<Empty, Integer>(Empty.class);
        var copy = codec.decode(codec.encode(List.of(source, empty)));
        assertEquals(Key.class, RollbackEnumSchema.keyType((EnumMap<?, ?>) copy.get(0)));
        assertEquals(Empty.class, RollbackEnumSchema.keyType((EnumMap<?, ?>) copy.get(1)));
        assertTrue(source.isEmpty());
        assertTrue(empty.isEmpty());
        assertEquals(Key.class, RollbackEnumSchema.keyType(source));
        assertEquals(Empty.class, RollbackEnumSchema.keyType(empty));
        assertEquals(0, initializations);
    }
}
