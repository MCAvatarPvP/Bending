package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

/** Sandstorm's layered dust-devil silhouette, with a fixed budget regardless of tornado size. */
final class TornadoVisuals {
    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

    record Settings(int ribbons, int points, int lobes, int tendrils) {
        Settings {
            ribbons = Math.max(2, Math.min(6, ribbons));
            points = Math.max(12, Math.min(48, points));
            lobes = Math.max(3, Math.min(16, lobes));
            tendrils = Math.max(2, Math.min(12, tendrils));
        }
    }

    @FunctionalInterface
    interface ParticleSink { void spawn(Location location, int count, double spread); }

    static void render(Location base, Vector facing, double height, double radius, double phase,
                       Settings settings, ParticleSink sink) {
        if (base == null || base.getWorld() == null) return;
        Vector forward = facing.clone().setY(0);
        if (forward.lengthSquared() < 1.0E-9) forward = new Vector(0, 0, 1);
        forward.normalize();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        for (int ribbon = 0; ribbon < settings.ribbons; ribbon++) {
            for (int i = 0; i < settings.points; i++) {
                double progress = i / (double) (settings.points - 1);
                double angle = phase * (1.28 + ribbon * 0.1)
                        + progress * Math.PI * (4.1 + ribbon * 0.34) + ribbon * 2.17;
                double breathing = 0.88 + 0.12 * Math.sin(phase * 0.72 + progress * Math.PI * 5 + ribbon * 1.4);
                double radial = radius * radiusAt(progress) * breathing;
                point(base, forward, right, angle, radial, height * progress + 0.05, 1,
                        Math.min(0.18, radius * 0.025), sink);
            }
        }
        for (int lobe = 0; lobe < settings.lobes; lobe++) {
            double progress = ((lobe + 0.5) / settings.lobes + phase * (0.022 + (lobe % 2) * 0.005)) % 1;
            double angle = phase * (0.7 + (lobe % 2) * 0.1) + lobe * GOLDEN_ANGLE + progress * Math.PI * 2.2;
            point(base, forward, right, angle, radius * radiusAt(progress) * (0.42 + lobe % 3 * 0.1),
                    0.12 + progress * height, 4, Math.min(0.7, radius * 0.13), sink);
        }
        for (int arm = 0; arm < Math.max(3, settings.ribbons); arm++) {
            for (int i = 0; i < 6; i++) {
                double progress = i / 5.0;
                point(base, forward, right, phase * 1.05 + arm * GOLDEN_ANGLE + progress * 0.9,
                        radius * (0.48 + progress * 0.46), height * (0.82 + progress * 0.16),
                        1, Math.min(0.2, radius * 0.04), sink);
            }
        }
        for (int tendril = 0; tendril < settings.tendrils; tendril++) {
            for (int i = 0; i < 7; i++) {
                double progress = i / 6.0;
                point(base, forward, right, -phase * 1.22 + tendril * GOLDEN_ANGLE + progress * 1.18,
                        radius * (0.1 + progress * 0.58), 0.035 + Math.sin(progress * Math.PI) * 0.075,
                        1, Math.min(0.2, radius * 0.04), sink);
            }
        }
    }

    static double radiusAt(double progress) {
        return 0.16 + Math.pow(Math.max(0, Math.min(1, progress)), 0.72) * 0.74;
    }

    private static void point(Location base, Vector forward, Vector right, double angle,
                              double radius, double y, int count, double spread, ParticleSink sink) {
        double along = Math.sin(angle) * radius * 0.82;
        double side = Math.cos(angle) * radius;
        sink.spawn(base.clone().add(forward.getX() * along + right.getX() * side, y,
                forward.getZ() * along + right.getZ() * side), count, spread);
    }
}
