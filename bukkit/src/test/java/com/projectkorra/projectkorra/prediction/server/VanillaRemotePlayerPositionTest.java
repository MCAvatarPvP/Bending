package com.projectkorra.projectkorra.prediction.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaRemotePlayerPositionTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void tenBlockPositionPacketUsesVanillasThreeInterpolationSteps() {
        VanillaRemotePlayerPosition position = new VanillaRemotePlayerPosition(0.0, 0.0, 0.0);
        position.positionSync(10.0, 0.0, 0.0);

        position.tick();
        assertEquals(10.0 / 3.0, position.x(), EPSILON);
        assertEquals(0.0, position.previousX(), EPSILON);
        assertEquals(2, position.interpolationSteps());

        position.tick();
        assertEquals(20.0 / 3.0, position.x(), EPSILON);
        assertEquals(10.0 / 3.0, position.sweptMinX(), EPSILON);
        assertEquals(20.0 / 3.0, position.sweptMaxX(), EPSILON);

        position.tick();
        assertEquals(10.0, position.x(), EPSILON);
        assertEquals(20.0 / 3.0, position.previousX(), EPSILON);
        assertEquals(0, position.interpolationSteps());
    }

    @Test
    void relativePacketsAccumulateFromPacketBaseRatherThanRenderedPosition() {
        VanillaRemotePlayerPosition position = new VanillaRemotePlayerPosition(0.0, 0.0, 0.0);

        position.relativeMove(4.0, 0.0, 0.0);
        position.tick();
        assertEquals(4.0 / 3.0, position.x(), EPSILON);

        position.relativeMove(4.0, 0.0, 0.0);
        position.tick();
        assertEquals(32.0 / 9.0, position.x(), EPSILON,
                "the second packet target is 8, even though the entity is still rendered near 1.33");
    }

    @Test
    void relativePacketUsesVecDeltaCodecRoundingAroundSpawnBase() {
        VanillaRemotePlayerPosition position = new VanillaRemotePlayerPosition(0.12345, 0.0, 0.0);
        position.relativeMove(1.0 / 4096.0, 0.0, 0.0);
        position.tick();
        position.tick();
        position.tick();

        assertEquals((Math.round(0.12345 * 4096.0) + 1.0) / 4096.0,
                position.x(), EPSILON);
    }

    @Test
    void positionSyncBeyondSixtyFourBlocksSnapsLikeTheClient() {
        VanillaRemotePlayerPosition position = new VanillaRemotePlayerPosition(0.0, 0.0, 0.0);
        position.positionSync(65.0, 0.0, 0.0);

        assertEquals(65.0, position.x(), EPSILON);
        assertEquals(65.0, position.previousX(), EPSILON,
                "a client snap must not manufacture a swept collision through 65 blocks");
        assertEquals(0, position.interpolationSteps());
    }

    @Test
    void relativeTeleportUsesCurrentRenderedPositionLikeClientPacketHandler() {
        VanillaRemotePlayerPosition position = new VanillaRemotePlayerPosition(10.0, 0.0, 0.0);
        position.teleport(2.0, 0.0, 0.0, true, false, false);
        position.tick();

        assertEquals(10.0 + 2.0 / 3.0, position.x(), EPSILON);
    }

    @Test
    void diagonalSweepFollowsTheSegmentInsteadOfItsLargeAabbHull() {
        VanillaRemotePlayerPosition position = new VanillaRemotePlayerPosition(0.0, 0.0, 0.0);
        position.positionSync(10.0, 0.0, 10.0);
        position.tick();

        assertTrue(position.sweptIntersects(
                0.9, -0.1, 0.9, 1.1, 0.1, 1.1,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        assertFalse(position.sweptIntersects(
                0.0, -0.1, 3.0, 0.2, 0.1, 3.2,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                "a diagonal move must not collide with empty corners of its enclosing AABB");
    }
}
