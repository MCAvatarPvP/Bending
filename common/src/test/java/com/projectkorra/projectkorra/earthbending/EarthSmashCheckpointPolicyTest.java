package com.projectkorra.projectkorra.earthbending;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarthSmashCheckpointPolicyTest {
    @Test
    void authoritativeAnchorCorrectionPreservesNewerLocalDisplacement() {
        final EarthSmashCheckpointPolicy.Position localAnchor = position(10, 64, 10);
        final EarthSmashCheckpointPolicy.Position current = position(14.5, 65, 7);
        final EarthSmashCheckpointPolicy.Position paperAnchor = position(11, 64.25, 9.5);
        final EarthSmashCheckpointPolicy.Position paperDestination = position(30, 70, -4);

        final EarthSmashCheckpointPolicy.Kinematics reconciled =
                EarthSmashCheckpointPolicy.reconcile(
                        current, localAnchor, paperAnchor, paperDestination, 12.75);
        final EarthSmashCheckpointPolicy.Position corrected = reconciled.center();

        assertEquals(position(15.5, 65.25, 6.5), corrected);
        assertEquals(current.x() - localAnchor.x(), corrected.x() - paperAnchor.x());
        assertEquals(current.y() - localAnchor.y(), corrected.y() - paperAnchor.y());
        assertEquals(current.z() - localAnchor.z(), corrected.z() - paperAnchor.z());
        assertEquals(paperDestination, reconciled.destination());
        assertEquals(12.75, reconciled.grabbedDistance());
    }

    @Test
    void delayedLiftCheckpointKeepsMotionRenderedInTheNextState() {
        final EarthSmashCheckpointPolicy.Position liftingTransition = position(5, 60, 5);
        final EarthSmashCheckpointPolicy.Position locallyLifted = position(5, 63, 5);
        final EarthSmashCheckpointPolicy.Position paperLiftingTransition = position(5, 60.25, 5);

        assertEquals(position(5, 63.25, 5), EarthSmashCheckpointPolicy.rebase(
                locallyLifted, liftingTransition, paperLiftingTransition));
    }

    @Test
    void newerTransitionWinsEvenWhenItsMotionCountersRestartLower() {
        final EarthSmashCheckpointPolicy.CheckpointOrder accepted = order(41, 80, 20, 5);
        final EarthSmashCheckpointPolicy.CheckpointOrder nextTransition = order(42, 1, 0, 0);
        final EarthSmashCheckpointPolicy.CheckpointOrder delayedOldTransition = order(40, 500, 500, 500);

        assertTrue(EarthSmashCheckpointPolicy.isNewer(nextTransition, accepted));
        assertFalse(EarthSmashCheckpointPolicy.isNewer(delayedOldTransition, accepted));
    }

    @Test
    void checkpointsWithinOneTransitionUseMonotonicProgressAndFrameOrder() {
        final EarthSmashCheckpointPolicy.CheckpointOrder accepted = order(17, 12, 3, 1);

        assertTrue(EarthSmashCheckpointPolicy.isNewer(order(17, 13, 3, 1), accepted));
        assertTrue(EarthSmashCheckpointPolicy.isNewer(order(17, 12, 4, 1), accepted));
        assertTrue(EarthSmashCheckpointPolicy.isNewer(order(17, 12, 3, 2), accepted));
        assertFalse(EarthSmashCheckpointPolicy.isNewer(order(17, 12, 3, 1), accepted));
        assertFalse(EarthSmashCheckpointPolicy.isNewer(order(17, 11, 99, 99), accepted));
    }

    @Test
    void firstCorrelatedCheckpointIsAccepted() {
        assertTrue(EarthSmashCheckpointPolicy.isNewer(order(1, 0, 0, 0), null));
        assertFalse(EarthSmashCheckpointPolicy.isNewer(null, order(1, 0, 0, 0)));
    }

    @Test
    void flightPitchOffsetIsContinuousAtTheFormerBranchBoundaries() {
        assertEquals(-3.2, EarthSmashCheckpointPolicy.flightVerticalOffset(-1.0));
        assertEquals(-3.2, EarthSmashCheckpointPolicy.flightVerticalOffset(-0.35));
        assertEquals(-2.2, EarthSmashCheckpointPolicy.flightVerticalOffset(0.0));
        assertEquals(-1.7, EarthSmashCheckpointPolicy.flightVerticalOffset(0.35));
        assertEquals(-1.7, EarthSmashCheckpointPolicy.flightVerticalOffset(1.0));

        assertTrue(Math.abs(EarthSmashCheckpointPolicy.flightVerticalOffset(-0.350001)
                - EarthSmashCheckpointPolicy.flightVerticalOffset(-0.349999)) < 0.00001);
        assertTrue(Math.abs(EarthSmashCheckpointPolicy.flightVerticalOffset(0.349999)
                - EarthSmashCheckpointPolicy.flightVerticalOffset(0.350001)) < 0.00001);
        assertEquals(-2.2, EarthSmashCheckpointPolicy.flightVerticalOffset(Double.NaN));
    }

    private static EarthSmashCheckpointPolicy.Position position(
            final double x, final double y, final double z) {
        return new EarthSmashCheckpointPolicy.Position(x, y, z);
    }

    private static EarthSmashCheckpointPolicy.CheckpointOrder order(
            final long action, final int progress, final long frame, final int animation) {
        return new EarthSmashCheckpointPolicy.CheckpointOrder(
                action, progress, frame, animation);
    }
}
