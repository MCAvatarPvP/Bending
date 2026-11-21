package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class RollbackEnvironmentDataTest {
    @Test void nativeEnvironmentInputsRoundTripWithStrictBoundedDecoding() {
        var data = new RollbackEnvironmentData("minecraft:overworld", true, new RollbackEnvironmentData.Weather(.6F, .2F));
        var bytes = data.encode(); assertEquals(data, RollbackEnvironmentData.decode(bytes));
        for (int length = 0; length < bytes.length; length++) {
            var truncated = Arrays.copyOf(bytes, length); assertThrows(IllegalArgumentException.class, () -> RollbackEnvironmentData.decode(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackEnvironmentData.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        for (int offset : new int[]{0, 4, 6 + data.dimensionType().length()}) {
            var invalid = bytes.clone(); invalid[offset] = (byte) 255; assertThrows(IllegalArgumentException.class, () -> RollbackEnvironmentData.decode(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackEnvironmentData.decode(new byte[RollbackEnvironmentData.MAXIMUM_BYTES + 1]));
    }
    @Test void invalidKeysAndNativeGradientRangesReject() {
        assertThrows(IllegalArgumentException.class, () -> new RollbackEnvironmentData("not an id", true, new RollbackEnvironmentData.Weather(0, 0)));
        for (float[] pair : new float[][]{{Float.NaN, 0}, {1.1F, 0}, {-1, 0}, {0, .1F}, {1, -1}}) {
            assertThrows(IllegalArgumentException.class, () -> new RollbackEnvironmentData.Weather(pair[0], pair[1]));
        }
    }
}
