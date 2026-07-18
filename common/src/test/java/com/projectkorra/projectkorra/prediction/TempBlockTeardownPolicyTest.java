package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TempBlockTeardownPolicyTest {
    @Test
    void detachedWaterSpoutCannotUseItsLeakedWaterAsTheFinalUnderlay() {
        assertEquals("air", TempBlockTeardownPolicy.select(
                null, null, null, null, "air", "water"));
    }

    @Test
    void anOwnedPaperLayerRendersItsViewerUnderlayWhileStillHidden() {
        assertEquals("air", TempBlockTeardownPolicy.select(
                null, "air", null, null, "stone", "water"));
    }

    @Test
    void anotherLiveLocalLayerRemainsAboveTheRemovedAbility() {
        assertEquals("ice", TempBlockTeardownPolicy.select(
                "ice", "air", null, null, "stone", "water"));
    }
}
