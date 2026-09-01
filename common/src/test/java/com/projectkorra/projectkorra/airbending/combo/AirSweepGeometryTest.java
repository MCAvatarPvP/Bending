package com.projectkorra.projectkorra.airbending.combo;

import com.projectkorra.projectkorra.platform.mc.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirSweepGeometryTest {

    private static final double WIDTH_MULTIPLIER = 1.15;

    @Test
    void wideSweepKeepsStreamsEvenlySpaced() {
        final Vector start = new Vector(1, 0, 0);
        final double sweepAngle = Math.toRadians(170);
        final Vector end = new Vector(
                Math.cos(sweepAngle),
                0,
                Math.sin(sweepAngle)
        );

        Vector previous = AirSweepFan.interpolateDirection(
                start,
                end,
                0,
                WIDTH_MULTIPLIER
        );
        double minimumGap = Double.POSITIVE_INFINITY;
        double maximumGap = 0;

        for (int index = 1; index < 40; index++) {
            final double progress = index / 39.0;
            final Vector current = AirSweepFan.interpolateDirection(
                    start,
                    end,
                    progress,
                    WIDTH_MULTIPLIER
            );
            final double gap = previous.angle(current);
            minimumGap = Math.min(minimumGap, gap);
            maximumGap = Math.max(maximumGap, gap);
            previous = current;
        }

        assertTrue(maximumGap < Math.toRadians(6));
        assertEquals(minimumGap, maximumGap, 1.0E-9);
    }

    @Test
    void oppositeDirectionsDoNotCollapseAtTheMiddle() {
        final Vector start = new Vector(1, 0, 0);
        final Vector end = new Vector(-1, 0, 0);

        final Vector middle = AirSweepFan.interpolateDirection(
                start,
                end,
                0.5,
                WIDTH_MULTIPLIER
        );

        assertEquals(1.0, middle.length(), 1.0E-9);
        assertEquals(Math.PI / 2.0, start.angle(middle), 1.0E-9);
    }
}
