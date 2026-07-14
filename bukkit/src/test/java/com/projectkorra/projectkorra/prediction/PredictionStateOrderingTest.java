package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictionStateOrderingTest {
    @Test
    void firstClickSnapshotCannotOverwritePendingSecondClick() {
        assertFalse(PredictionStateOrdering.snapshotCoversLatestInput(41, 42));
    }

    @Test
    void acknowledgedRapidToggleCanReconcileImmediately() {
        assertTrue(PredictionStateOrdering.snapshotCoversLatestInput(42, 42));
        assertTrue(PredictionStateOrdering.snapshotCoversLatestInput(43, 42));
    }
}
