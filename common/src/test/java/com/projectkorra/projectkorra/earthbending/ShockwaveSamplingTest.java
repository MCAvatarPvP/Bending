package com.projectkorra.projectkorra.earthbending;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShockwaveSamplingTest {
    @Test
    void samplesApproximatelyOneDirectionPerOuterCircumferenceBlock() {
        assertEquals(95, ShockwaveExecutionPolicy.directionCount(15.0));
    }

    @Test
    void nonPositiveOrInvalidRangesCannotCreateAnUnboundedLoop() {
        assertEquals(0, ShockwaveExecutionPolicy.directionCount(0.0));
        assertEquals(0, ShockwaveExecutionPolicy.directionCount(-1.0));
        assertEquals(0, ShockwaveExecutionPolicy.directionCount(Double.NaN));
    }
}
