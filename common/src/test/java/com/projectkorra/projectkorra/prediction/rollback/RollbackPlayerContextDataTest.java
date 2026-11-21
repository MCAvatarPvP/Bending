package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.Vector;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackPlayerContextDataTest {
    private static final RollbackPlayerContext.Abilities ABILITIES = new RollbackPlayerContext.Abilities(true, true, true, false, false, .08F, .12F);
    private static final RollbackPlayerContext.Input INPUT = new RollbackPlayerContext.Input(true, false, false, true, true, false, true);
    private static final Vector ZERO = new Vector(0, 0, 0);

    @Test void detachedContactAndInputRoundTripWithClockOffsetsAndExplicitEatingSentinel() {
        var tags = new HashSet<>(Set.of("duel", "\u6c34")); var heights = new HashMap<>(Map.of("minecraft:water", .75));
        var value = new RollbackPlayerContext(ABILITIES, INPUT, new Vector(.15, -.05, .25), -100_000_000L, OptionalLong.of(-200_000_000L),
                tags, Set.of(new UUID(0, 999)), heights, Set.of("minecraft:water"), new Vector(.2, -.3, .4));
        tags.clear(); heights.clear();
        var decoded = RollbackPlayerContext.decode(value.encode());
        assertEquals(value, decoded); assertArrayEquals(value.encode(), decoded.encode());
        assertEquals(new RollbackPlayerContext.Timers(-100_000_000, -200_000_000), decoded.rebase(0));
        assertEquals(new RollbackPlayerContext.Timers(5_900_000_000L, 5_800_000_000L), decoded.rebase(6_000_000_000L));
        assertThrows(ArithmeticException.class, () -> decoded.rebase(Long.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> decoded.rebase(199_999_999));
        var idle = new RollbackPlayerContext(ABILITIES, INPUT, ZERO, -5_000_000_000L, OptionalLong.empty(), Set.of(), Set.of(), Map.of(), Set.of(), ZERO);
        assertEquals(new RollbackPlayerContext.Timers(-5_000_000_000L, -1), RollbackPlayerContext.decode(idle.encode()).rebase(0));
        assertThrows(UnsupportedOperationException.class, () -> decoded.tags().clear());
        assertThrows(UnsupportedOperationException.class, () -> decoded.fluidHeights().clear());
    }

    @Test void malformedFlagsUtf8OrderCountsAndNumbersNeverProduceAPartialContext() {
        var context = new RollbackPlayerContext(ABILITIES, INPUT, ZERO, 0, OptionalLong.empty(), Set.of("a", "b"), Set.of(), Map.of(), Set.of(), ZERO);
        byte[] bytes = context.encode();
        for (int i = 0; i < bytes.length; i++) {
            byte[] cut = Arrays.copyOf(bytes, i); assertThrows(IllegalArgumentException.class, () -> RollbackPlayerContext.decode(cut));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerContext.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        for (int offset : List.of(4, 13, 46)) {
            byte[] bad = bytes.clone(); bad[offset] = (byte) 128;
            assertThrows(IllegalArgumentException.class, () -> RollbackPlayerContext.decode(bad));
        }
        byte[] count = bytes.clone(); ByteBuffer.wrap(count).putInt(47, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerContext.decode(count));
        byte[] invalidUtf = bytes.clone(); invalidUtf[53] = (byte) 128;
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerContext.decode(invalidUtf));
        byte[] duplicate = bytes.clone(); duplicate[56] = 'a';
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerContext.decode(duplicate));
        byte[] nonfinite = bytes.clone(); ByteBuffer.wrap(nonfinite).putFloat(5, Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerContext.decode(nonfinite));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerContext(ABILITIES, INPUT, ZERO, 0, OptionalLong.empty(), Set.of("\uD800"), Set.of(), Map.of(), Set.of(), ZERO));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerContext(ABILITIES, INPUT, ZERO, 0, OptionalLong.empty(), Set.of(), Set.of(), Map.of("bad fluid", 0D), Set.of(), ZERO));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerContext(ABILITIES, INPUT, ZERO, 0, OptionalLong.empty(), Set.of(), Set.of(), Map.of("minecraft:water", Double.NaN), Set.of(), ZERO));
    }
}
