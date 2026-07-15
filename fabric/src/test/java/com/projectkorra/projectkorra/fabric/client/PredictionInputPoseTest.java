package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class PredictionInputPoseTest {
    @Test
    void inputUsesLastPoseSentToServerInsteadOfNewerLocalLook() {
        PredictionClient.ServerPose serverVisible =
                new PredictionClient.ServerPose(1, 64, 2, 35, -10, 1.62);
        PredictionClient.ServerPose latestLocal =
                new PredictionClient.ServerPose(1, 64, 2, 120, 40, 1.62);

        assertSame(serverVisible, PredictionClient.poseForInput(serverVisible, latestLocal));
    }

    @Test
    void inputFallsBackToLocalPoseBeforeFirstMovementPacket() {
        PredictionClient.ServerPose latestLocal =
                new PredictionClient.ServerPose(1, 64, 2, 120, 40, 1.62);

        assertSame(latestLocal, PredictionClient.poseForInput(null, latestLocal));
    }
}
