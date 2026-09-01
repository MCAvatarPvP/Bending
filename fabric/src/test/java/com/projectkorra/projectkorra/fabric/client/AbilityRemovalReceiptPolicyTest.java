package com.projectkorra.projectkorra.fabric.client;

import com.projectkorra.projectkorra.earthbending.RaiseEarth;
import com.projectkorra.projectkorra.earthbending.RaiseEarthWall;
import com.projectkorra.projectkorra.prediction.hit.HitRegistrationPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilityRemovalReceiptPolicyTest {
    @Test
    void paperAlwaysClosesReactiveClientLifecycles() {
        assertFalse(ExactPredictionRuntime.retainsAcceptedPredictedLifecycle(
                HitRegistrationPolicy.SERVER_CURRENT, false, true, true));
        assertTrue(ExactPredictionRuntime.retainsAcceptedPredictedLifecycle(
                HitRegistrationPolicy.REWIND_ASSISTED, false, true, true));
        assertFalse(ExactPredictionRuntime.retainsAcceptedPredictedLifecycle(
                HitRegistrationPolicy.REWIND_ASSISTED, true, true, true));
    }

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

    @Test
    void finalRaiseEarthChildOrEmptyWallCompletesTheDirectFrame() {
        final String raiseType = RaiseEarth.class.getName();
        assertTrue(ExactPredictionRuntime.completesRaiseEarthFrame(
                raiseType, 0));
        assertFalse(ExactPredictionRuntime.completesRaiseEarthFrame(
                raiseType, 1));
        assertTrue(ExactPredictionRuntime.completesRaiseEarthFrame(
                RaiseEarthWall.class.getName(), 0));
    }
}
