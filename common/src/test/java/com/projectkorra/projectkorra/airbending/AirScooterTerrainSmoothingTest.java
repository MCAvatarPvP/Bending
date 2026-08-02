package com.projectkorra.projectkorra.airbending;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirScooterTerrainSmoothingTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void oneBlockEdgesBecomeGradualTerrainRamps() {
        assertEquals(0.0, AirScooterTerrain.averageHeight(0, 0, 0, 0, 0), EPSILON);
        assertEquals(0.2, AirScooterTerrain.averageHeight(0, 0, 0, 0, 1), EPSILON);
        assertEquals(0.4, AirScooterTerrain.averageHeight(0, 0, 0, 1, 1), EPSILON);
        assertEquals(-0.2, AirScooterTerrain.averageHeight(0, 0, 0, 0, -1), EPSILON);
        assertEquals(-0.4, AirScooterTerrain.averageHeight(0, 0, 0, -1, -1), EPSILON);
    }

    @Test
    void modernSpringProducesSmallSymmetricStepCorrections() {
        final double flat = AirScooterTerrain.verticalVelocity(2.4, 0, 2.4, 0.15);
        final double uphill = AirScooterTerrain.verticalVelocity(2.4, 0.2, 2.4, 0.15);
        final double downhill = AirScooterTerrain.verticalVelocity(2.4, -0.2, 2.4, 0.15);

        assertEquals(0.0, flat, EPSILON);
        assertEquals(0.03, uphill, EPSILON);
        assertEquals(-0.03, downhill, EPSILON);
        assertTrue(uphill < 0.7, "modern uphill correction must not recreate the legacy jolt");
        assertTrue(downhill > -0.1, "modern downhill correction must not recreate the legacy jolt");
    }

    @Test
    void modernSpringStillHonorsItsSafetyCaps() {
        assertEquals(0.5,
                AirScooterTerrain.verticalVelocity(0, 100, 2.4, 1), EPSILON);
        assertEquals(-1.0,
                AirScooterTerrain.verticalVelocity(100, 0, 2.4, 1), EPSILON);
    }
}
