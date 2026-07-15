package com.projectkorra.projectkorra.airbending;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AirBlastTargetingTest {
    @Test
    void unobstructedAirSourceStaysInsideSelectionRange() {
        assertEquals(12.6, AirBlastTargeting.targetDistance(14.0, 12.7), 1.0E-9);
    }

    @Test
    void nearbyBlockStillUsesLegacyBlockAdjustedDistance() {
        assertEquals(4.25, AirBlastTargeting.targetDistance(4.25, 12.7), 1.0E-9);
    }
}
