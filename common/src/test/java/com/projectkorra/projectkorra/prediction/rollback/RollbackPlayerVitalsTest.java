package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerVitals.*;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RollbackPlayerVitalsTest {
    private static final Modifier PERMANENT = new Modifier("test:permanent", .02, Operation.ADD_VALUE, true);
    private static final Modifier TEMPORARY = new Modifier("test:temporary", .4, Operation.ADD_MULTIPLIED_TOTAL, false);

    @Test void portableComponentsAreDetachedCanonicalAndKeepModifierSemantics() {
        byte[] tracked = {2, 3, 4}, effects = {9, 8, 7};
        var modifiers = new ArrayList<>(List.of(TEMPORARY, PERMANENT));
        var attributes = new ArrayList<>(List.of(new Attribute("minecraft:movement_speed", .1, modifiers), new Attribute("minecraft:armor", 3, List.of())));
        var seed = new RollbackPlayerVitals(tracked, effects, attributes);
        Arrays.fill(tracked, (byte) 0); Arrays.fill(effects, (byte) 0); modifiers.clear(); attributes.clear();
        var copy = RollbackPlayerVitals.decode(seed.encode());
        assertEquals(seed.attributes(), copy.attributes());
        assertArrayEquals(new byte[]{2, 3, 4}, copy.tracked()); assertArrayEquals(new byte[]{9, 8, 7}, copy.effects());
        copy.tracked()[0] = 99; copy.effects()[0] = 99;
        assertArrayEquals(seed.encode(), copy.encode());
        assertEquals(List.of("minecraft:armor", "minecraft:movement_speed"), copy.attributes().stream().map(Attribute::id).toList());
        assertEquals(List.of(PERMANENT, TEMPORARY), copy.attributes().getLast().modifiers());
        assertThrows(UnsupportedOperationException.class, () -> copy.attributes().clear());
    }

    @Test void malformedCountsKeysNumbersDuplicatesAndTruncationFail() {
        var attribute = new Attribute("minecraft:movement_speed", .1, List.of(PERMANENT, TEMPORARY));
        byte[] bytes = new RollbackPlayerVitals(new byte[]{1}, new byte[]{9}, List.of(attribute)).encode();
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThrows(IllegalArgumentException.class, () -> RollbackPlayerVitals.decode(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerVitals.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        byte[] badLength = bytes.clone(); ByteBuffer.wrap(badLength).putInt(4, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerVitals.decode(badLength));
        byte[] badFlag = bytes.clone(); badFlag[badFlag.length - 1] = 2;
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerVitals.decode(badFlag));
        byte[] badOperation = bytes.clone(); badOperation[badOperation.length - 2] = 3;
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerVitals.decode(badOperation));
        assertThrows(IllegalArgumentException.class, () -> new Attribute("minecraft:speed", 0, List.of(PERMANENT, PERMANENT)));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerVitals(new byte[]{1}, new byte[]{9}, List.of(attribute, attribute)));
        assertThrows(IllegalArgumentException.class, () -> new Attribute("bad key", 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Modifier("test:bad", Double.NaN, Operation.ADD_VALUE, false));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerVitals(new byte[0], new byte[]{1}, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerVitals(new byte[RollbackPlayerVitals.MAXIMUM_BLOB + 1], new byte[]{1}, List.of()));
        var output = new RollbackPlayerVitals.Output(3); output.writeBytes(new byte[]{1, 2});
        assertThrows(IllegalArgumentException.class, () -> output.writeBytes(new byte[]{3, 4}));
        assertArrayEquals(new byte[]{1, 2}, output.toByteArray());
    }
}
