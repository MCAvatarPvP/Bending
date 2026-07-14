package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictionVisibilityTest {
    @Test
    void rejectsOtherWorldsAndDistantChunks() {
        assertFalse(PredictionVisibility.tracksBlock("world", "other", 0, 0, 0, 0, 10));
        assertFalse(PredictionVisibility.tracksBlock("world", "world", 0, 0, 1_000, 0, 10));
    }

    @Test
    void acceptsBlocksInsideTrackedChunkRadius() {
        assertTrue(PredictionVisibility.tracksBlock("world", "world", 0, 0, 16 * 10, -16 * 10, 10));
    }
}
