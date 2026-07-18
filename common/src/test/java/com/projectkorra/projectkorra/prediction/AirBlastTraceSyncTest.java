package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirBlastTraceSyncTest {
    @Test
    void identicalEndpointSamplesAreParityEvidence() {
        final AirBlastTraceSync.Trace local = trace(2.5, -0.9, 1.0);
        final AirBlastTraceSync.Trace paper = trace(2.5, -0.9, 1.0);

        assertEquals("", AirBlastTraceSync.firstDifference(local, paper));
    }

    @Test
    void comparisonNamesTheFirstActualLaunchDifference() {
        final AirBlastTraceSync.Trace local = trace(2.5, -0.9, 1.0);
        final AirBlastTraceSync.Trace paper = trace(2.5, -0.7, 1.0);

        assertTrue(AirBlastTraceSync.firstDifference(local, paper).startsWith("directionY "));
    }

    @Test
    void comparisonSeparatesStaminaScalingFromGeometry() {
        final AirBlastTraceSync.Trace local = trace(2.5, -0.9, 0.8);
        final AirBlastTraceSync.Trace paper = trace(2.5, -0.9, 1.0);

        assertTrue(AirBlastTraceSync.firstDifference(local, paper).startsWith("shotStamina "));
    }

    @Test
    void yawSeparatedByAWholeTurnIsTheSameInput() {
        final AirBlastTraceSync.Trace local = traceWithYaw(355.885589599609F);
        final AirBlastTraceSync.Trace paper = traceWithYaw(-4.114410400391F);

        assertEquals("", AirBlastTraceSync.firstDifference(local, paper));
        assertEquals(0.0, AirBlastTraceSync.signedAngleDelta(366.371490478516, 6.371490478516), 1.0E-12);
    }

    @Test
    void yawComparisonStillFindsARealShortestArcDifference() {
        final AirBlastTraceSync.Trace local = traceWithYaw(359.0F);
        final AirBlastTraceSync.Trace paper = traceWithYaw(1.0F);

        assertTrue(AirBlastTraceSync.firstDifference(local, paper).startsWith("yaw "));
        assertEquals(-2.0, AirBlastTraceSync.signedAngleDelta(359.0, 1.0), 1.0E-12);
    }

    @Test
    void terminalPhaseExposesAnEarlyClientExitInsteadOfOnlyAnEventCount() {
        final AirBlastTraceSync.Trace paper = trace(2.5, -0.9, 1.0);
        final AirBlastTraceSync.Trace local = new AirBlastTraceSync.Trace(
                paper.eventOrdinal(), AirBlastTraceSync.Phase.BLOCKED, paper.progressTick(),
                paper.eyeX(), paper.eyeY(), paper.eyeZ(), paper.yaw(), paper.pitch(),
                paper.originX(), paper.originY(), paper.originZ(),
                paper.targetX(), paper.targetY(), paper.targetZ(),
                paper.locationX(), paper.locationY(), paper.locationZ(),
                paper.directionX(), paper.directionY(), paper.directionZ(),
                paper.speed(), paper.range(), paper.radius(),
                paper.preShootStamina(), paper.shotStamina(),
                paper.blockX(), paper.blockY(), paper.blockZ(), paper.blockMaterial(), false);

        assertEquals("phase local=BLOCKED paper=LAUNCH",
                AirBlastTraceSync.firstDifference(local, paper));
    }

    private static AirBlastTraceSync.Trace traceWithYaw(final float yaw) {
        final AirBlastTraceSync.Trace trace = trace(2.5, -0.9, 1.0);
        return new AirBlastTraceSync.Trace(trace.eventOrdinal(), trace.phase(), trace.progressTick(),
                trace.eyeX(), trace.eyeY(), trace.eyeZ(), yaw, trace.pitch(),
                trace.originX(), trace.originY(), trace.originZ(),
                trace.targetX(), trace.targetY(), trace.targetZ(),
                trace.locationX(), trace.locationY(), trace.locationZ(),
                trace.directionX(), trace.directionY(), trace.directionZ(),
                trace.speed(), trace.range(), trace.radius(),
                trace.preShootStamina(), trace.shotStamina(),
                trace.blockX(), trace.blockY(), trace.blockZ(), trace.blockMaterial(), trace.removed());
    }

    private static AirBlastTraceSync.Trace trace(final double targetY, final double directionY,
                                                 final double shotStamina) {
        return new AirBlastTraceSync.Trace(1, AirBlastTraceSync.Phase.LAUNCH, 0,
                10.25, 65.62, -4.75, 180.0F, 90.0F,
                10.25, 64.2, -1.25,
                10.25, targetY, -1.25,
                10.25, 64.2, -1.25,
                0.0, directionY, 0.435889894,
                23.0, 11.0, 1.5, 1.2, shotStamina,
                10, 2, -1, "AIR", false);
    }
}
