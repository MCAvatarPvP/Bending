package com.projectkorra.projectkorra.prediction.movement;

import com.projectkorra.projectkorra.platform.mc.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class SpoutMovementPolicyTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void waterSpoutUsesObservedMovementDuringLocalPrediction() {
        final Vector movement = new Vector(0.18, 0.31, -0.12);
        final Vector velocity = new Vector(0.04, -0.08, 0.02);

        final Vector selected = SpoutMovementPolicy.initialVelocity(
                true, true, movement, velocity);

        assertVector(movement, selected);
        assertNotSame(movement, selected);
    }

    @Test
    void localAirSpoutUsesItsLiveVelocity() {
        final Vector movement = new Vector(0.18, 0.31, -0.12);
        final Vector velocity = new Vector(0.04, -0.08, 0.02);

        final Vector selected = SpoutMovementPolicy.initialVelocity(
                false, true, movement, velocity);

        assertVector(velocity, selected);
        assertNotSame(velocity, selected);
    }

    @Test
    void paperUsesObservedMovementForAirSpout() {
        final Vector movement = new Vector(0.18, 0.31, -0.12);
        final Vector velocity = new Vector(0.04, -0.08, 0.02);

        assertVector(movement, SpoutMovementPolicy.initialVelocity(
                false, false, movement, velocity));
    }

    private static void assertVector(final Vector expected, final Vector actual) {
        assertEquals(expected.getX(), actual.getX(), EPSILON);
        assertEquals(expected.getY(), actual.getY(), EPSILON);
        assertEquals(expected.getZ(), actual.getZ(), EPSILON);
    }
}
