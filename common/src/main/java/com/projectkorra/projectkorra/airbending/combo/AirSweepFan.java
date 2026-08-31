package com.projectkorra.projectkorra.airbending.combo;

import com.projectkorra.projectkorra.platform.mc.util.Vector;

final class AirSweepFan {

    private AirSweepFan() {
    }

    static Vector interpolateDirection(
            final Vector startDirection,
            final Vector endDirection,
            final double progress,
            final double widthMultiplier
    ) {
        final Vector start = startDirection.clone().normalize();
        final Vector end = endDirection.clone().normalize();
        final double widenedProgress = 0.5
                + (progress - 0.5) * widthMultiplier;
        final double dot = Math.max(-1.0, Math.min(1.0, start.dot(end)));
        final double angle = Math.acos(dot);

        if (angle <= 1.0E-9) {
            return start;
        }

        Vector axis = start.clone().crossProduct(end);
        if (axis.lengthSquared() <= 1.0E-12) {
            final Vector reference = Math.abs(start.getY()) < 0.9
                    ? new Vector(0, 1, 0)
                    : new Vector(1, 0, 0);
            axis = start.clone().crossProduct(reference);
        }
        axis.normalize();

        final double rotation = angle * widenedProgress;
        final double cosine = Math.cos(rotation);
        final double sine = Math.sin(rotation);

        return start.clone().multiply(cosine)
                .add(axis.clone().crossProduct(start).multiply(sine))
                .add(axis.clone().multiply(axis.dot(start) * (1.0 - cosine)))
                .normalize();
    }
}
