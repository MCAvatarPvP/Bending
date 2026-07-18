package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompletedTempBlockRestorePolicyTest {
    @Test
    void unpairedWaterCloseUsesLiveWaterOnlyWhileThatClientLayerStillExists() {
        assertEquals("water", ExactPredictionRuntime.completedTempBlockRestoreState(
                true, "water", "stone"));
        assertEquals("stone", ExactPredictionRuntime.completedTempBlockRestoreState(
                true, null, "stone"));
    }

    @Test
    void aNonTrackingFenceUsesItsRecordedViewerState() {
        assertEquals("server-view", ExactPredictionRuntime.completedTempBlockRestoreState(
                false, "unrelated-client-layer", "server-view"));
    }
}
