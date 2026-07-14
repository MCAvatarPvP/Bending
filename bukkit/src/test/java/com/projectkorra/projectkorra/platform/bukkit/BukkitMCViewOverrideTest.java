package com.projectkorra.projectkorra.platform.bukkit;

import com.projectkorra.projectkorra.prediction.CapturedInputPose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BukkitMCViewOverrideTest {
    @Test
    void predictedEyeUsesCapturedSneakPoseBeforeVanillaPacketArrives() {
        CapturedInputPose captured = new CapturedInputPose(10.5, 81.27, -4.5, 100.0F, 14.5F);

        assertEquals(81.27, captured.locationY(80.0, true));
        assertEquals(100.0F, captured.yaw());
        assertEquals(14.5F, captured.pitch());
    }

    @Test
    void predictedFeetKeepNativeHeightButUseCapturedHorizontalPoseAndRotation() {
        CapturedInputPose captured = new CapturedInputPose(10.5, 81.27, -4.5, 100.0F, 14.5F);

        assertEquals(80.0, captured.locationY(80.0, false));
    }
}
