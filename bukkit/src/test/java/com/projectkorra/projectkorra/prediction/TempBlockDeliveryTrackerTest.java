package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempBlockDeliveryTrackerTest {
    @Test
    void revertFollowsCreateOutsideViewDistance() {
        TempBlockDeliveryTracker tracker = new TempBlockDeliveryTracker();
        assertTrue(tracker.route(41L, false, true));
        assertTrue(tracker.tracks(41L));

        assertTrue(tracker.route(41L, true, false));
        assertFalse(tracker.tracks(41L));
    }

    @Test
    void unrelatedOutOfViewLayersAreNotSent() {
        TempBlockDeliveryTracker tracker = new TempBlockDeliveryTracker();
        assertFalse(tracker.route(99L, false, false));
        assertFalse(tracker.route(99L, true, false));
    }

    @Test
    void handshakeSnapshotRequiresLaterRevert() {
        TempBlockDeliveryTracker tracker = new TempBlockDeliveryTracker();
        tracker.markActive(7L);
        assertTrue(tracker.route(7L, true, false));
        assertFalse(tracker.tracks(7L));
    }

    @Test
    void worldBoundaryForgetsClosuresFromTheDepartedWorld() {
        TempBlockDeliveryTracker tracker = new TempBlockDeliveryTracker();
        tracker.markActive(7L);

        tracker.clear();

        assertFalse(tracker.tracks(7L));
        assertFalse(tracker.route(7L, true, false),
                "an old-world close must not be routed into a same-dimension destination");
    }
}
