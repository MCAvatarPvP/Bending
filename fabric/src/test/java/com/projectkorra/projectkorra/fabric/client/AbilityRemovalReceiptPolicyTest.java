package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilityRemovalReceiptPolicyTest {
    @Test
    void collisionRemovalSurvivesRetiredCreationAction() {
        assertTrue(ExactPredictionRuntime.removalReceiptMayResolve(true, false, false));
    }

    @Test
    void ordinaryPaperCloseStillCannotTruncateUnconfirmedPrediction() {
        assertFalse(ExactPredictionRuntime.removalReceiptMayResolve(false, false, false));
        assertFalse(ExactPredictionRuntime.removalReceiptMayResolve(false, true, false));
        assertTrue(ExactPredictionRuntime.removalReceiptMayResolve(false, true, true));
    }

    @Test
    void externalEmptyTypeFenceRemovesCorrelatedInstanceAlreadySeenByPaper() {
        assertTrue(ExactPredictionRuntime.authoritativeEmptyTypeFenceCoversCandidate(
                true, 0, 77L, 77L));
    }

    @Test
    void externalEmptyTypeFencePreservesInputNewerThanPaperSnapshot() {
        assertFalse(ExactPredictionRuntime.authoritativeEmptyTypeFenceCoversCandidate(
                true, 0, 77L, 78L));
        assertFalse(ExactPredictionRuntime.authoritativeEmptyTypeFenceCoversCandidate(
                true, 1, 77L, 77L));
        assertFalse(ExactPredictionRuntime.authoritativeEmptyTypeFenceCoversCandidate(
                false, 0, 77L, 77L));
        assertFalse(ExactPredictionRuntime.authoritativeEmptyTypeFenceCoversCandidate(
                true, 0, 77L, null),
                "an unknown local identity is never permission to delete a live prediction");
        assertFalse(ExactPredictionRuntime.authoritativeEmptyTypeFenceCoversCandidate(
                true, 0, 0L, 77L),
                "a raw Paper acknowledgement must be correlated before it fences local input");
    }
}
