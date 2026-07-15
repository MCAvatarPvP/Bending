package com.projectkorra.projectkorra.firebending;

import com.projectkorra.projectkorra.platform.mc.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WallOfFireHitGeometryTest {
    @Test
    void detectsAnEntitySweepingCompletelyThroughTheWallBetweenTicks() {
        assertTrue(WallOfFireHitGeometry.segmentIntersectsLocalAABB(
                new Vector(0, 0, -3), new Vector(0, 0, 3),
                -2, 2, -1, 1, -.5, .5));
    }

    @Test
    void detectsStationaryAndBoundaryContacts() {
        assertTrue(WallOfFireHitGeometry.segmentIntersectsLocalAABB(
                new Vector(0, 0, 0), new Vector(0, 0, 0),
                -2, 2, -1, 1, -.5, .5));
        assertTrue(WallOfFireHitGeometry.segmentIntersectsLocalAABB(
                new Vector(2, 1, -2), new Vector(2, 1, 2),
                -2, 2, -1, 1, -.5, .5));
    }

    @Test
    void rejectsAParallelSweepOutsideTheWall() {
        assertFalse(WallOfFireHitGeometry.segmentIntersectsLocalAABB(
                new Vector(3, 0, -3), new Vector(3, 0, 3),
                -2, 2, -1, 1, -.5, .5));
    }
}
