package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.*;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.Vector;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RollbackPlayerValuesTest {
    @Test void exactTypedFieldsNullsOptionalPositionsAndAttachmentsRoundTripWithoutMutableAliases() {
        var points = new ArrayList<>(List.of(new Vector(1, 2, 3)));
        Map<String, List<Vector>> attachments = new HashMap<>(Map.of("PASSENGER", points));
        var dimensions = new Dimensions(.6f, 1.5f, 1.27f, false, attachments);
        var fields = new HashMap<String, Cell>();
        Object[] values = {true, (byte) -7, (short) 230, 123, 9999999999L, -0f, -0d, '\uFFFF', "OFF_HAND",
                new Vector(.05, .3, .08), new Box(-1, 0, -1, 1, 1.5, 1), new Block(-2, 6, 7), new Chunk(-2, 8), dimensions, Optional.empty()};
        for (Kind kind : Kind.values()) fields.put("field." + kind.name(), new Cell(kind, values[kind.ordinal()]));
        fields.put("null.position", new Cell(Kind.VECTOR, null));
        fields.put("present.block", new Cell(Kind.OPTIONAL_BLOCK, Optional.of(new Block(1, 2, 3))));
        var original = new RollbackPlayerValues(fields);
        byte[] bytes = original.encode();
        var copy = RollbackPlayerValues.decode(bytes);
        assertEquals(original.fields(), copy.fields());
        assertArrayEquals(bytes, copy.encode());
        points.clear(); attachments.clear(); fields.clear(); Arrays.fill(bytes, (byte) 0);
        assertEquals(List.of(new Vector(1, 2, 3)), dimensions.attachments().get("PASSENGER"));
        assertEquals(original.fields(), copy.fields());
        assertThrows(UnsupportedOperationException.class, () -> copy.fields().clear());
    }

    @Test void malformedWireAndNonfiniteOrUnboundedValuesFail() {
        byte[] bytes = new RollbackPlayerValues(Map.of("x", new Cell(Kind.BOOLEAN, true))).encode();
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThrows(IllegalArgumentException.class, () -> RollbackPlayerValues.decode(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerValues.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        byte[] count = bytes.clone(); ByteBuffer.wrap(count).putInt(4, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerValues.decode(count));
        byte[] badFlag = bytes.clone(); badFlag[12] = 2;
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerValues.decode(badFlag));
        byte[] badBoolean = bytes.clone(); badBoolean[13] = 2;
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerValues.decode(badBoolean));
        byte[] nullPrimitive = bytes.clone(); nullPrimitive[12] = 0;
        assertThrows(IllegalArgumentException.class, () -> RollbackPlayerValues.decode(nullPrimitive));
        assertThrows(IllegalArgumentException.class, () -> new Cell(Kind.INT, 1L));
        assertThrows(IllegalArgumentException.class, () -> new Cell(Kind.FLOAT, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Vector(Double.POSITIVE_INFINITY, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Cell(Kind.OPTIONAL_BLOCK, Optional.of(new Object())));
        assertNull(RollbackPlayerValues.requireField("living.swingingArm", new Cell(Kind.ENUM, null)).value());
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerValues(Map.of("bad/name", new Cell(Kind.BOOLEAN, true))));
        assertThrows(IllegalArgumentException.class, () -> new Dimensions(1, 2, 1, false, Map.of("PASSENGER", Collections.nCopies(129, new Vector(0, 0, 0)))));
    }
}
