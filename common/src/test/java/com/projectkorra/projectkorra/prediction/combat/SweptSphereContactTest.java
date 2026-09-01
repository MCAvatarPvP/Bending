package com.projectkorra.projectkorra.prediction.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweptSphereContactTest {
    @Test
    void findsCrossingProjectilesThatDoNotOverlapAtEitherEndpoint() {
        final double contact = SweptSphereContact.firstContact(
                -2, 0, 0, 2, 0, 0,
                2, 0, 0, -2, 0, 0, 0.5);

        assertEquals(0.4375, contact, 1.0E-9);
    }

    @Test
    void movingBothSpheresUsesRelativeMotion() {
        final double contact = SweptSphereContact.firstContact(
                0, 0, 0, 4, 0, 0,
                3, 0, 0, 5, 0, 0, 1.0);

        assertEquals(1.0, contact, 1.0E-9);
    }

    @Test
    void reportsInitialOverlapBeforeMovement() {
        assertEquals(0.0, SweptSphereContact.firstContact(
                0, 0, 0, 1, 0, 0,
                0.5, 0, 0, 4, 0, 0, 1.0));
    }

    @Test
    void rejectsAClosestApproachOutsideTheTick() {
        assertTrue(Double.isNaN(SweptSphereContact.firstContact(
                0, 0, 0, 1, 0, 0,
                4, 0, 0, 5, 0, 0, 1.0)));
    }
}
