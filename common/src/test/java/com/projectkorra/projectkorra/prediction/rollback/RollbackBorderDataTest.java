package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class RollbackBorderDataTest {
    @Test void staticAndMovingBordersRoundTripWithStrictBoundedDecoding() {
        var moving = new RollbackBorderData.Transition(41, 7, 11, Long.MAX_VALUE - 2, 8, 34);
        for (var transition : new RollbackBorderData.Transition[]{null, moving}) {
            var data = new RollbackBorderData(2.5, -3.25, 9, .3, 2, 4, 50, 31, transition);
            byte[] bytes = data.encode(); assertEquals(data, RollbackBorderData.decode(bytes));
            for (int length = 0; length < bytes.length; length++) {
                var truncated = Arrays.copyOf(bytes, length); assertThrows(IllegalArgumentException.class, () -> RollbackBorderData.decode(truncated));
            }
            assertThrows(IllegalArgumentException.class, () -> RollbackBorderData.decode(Arrays.copyOf(bytes, bytes.length + 1)));
            for (int offset : new int[]{0, 56}) {
                var invalid = bytes.clone(); invalid[offset] = (byte) 255; assertThrows(IllegalArgumentException.class, () -> RollbackBorderData.decode(invalid));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackBorderData.decode(new byte[RollbackBorderData.MAXIMUM_BYTES + 1]));
    }
    @Test void invalidNumbersAndResizeProgressReject() {
        assertThrows(IllegalArgumentException.class, () -> new RollbackBorderData(Double.NaN, 0, 9, .2, 5, 5, 15, 30, null));
        assertThrows(IllegalArgumentException.class, () -> new RollbackBorderData(0, 0, -1, .2, 5, 5, 15, 30, null));
        assertThrows(IllegalArgumentException.class, () -> new RollbackBorderData.Transition(30, 7, 11, 0, 12, 30));
        assertThrows(IllegalArgumentException.class, () -> new RollbackBorderData.Transition(30, 7, 11, 0, 8, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new RollbackBorderData.Transition(7, 7, 11, 0, 8, 7));
    }
}
