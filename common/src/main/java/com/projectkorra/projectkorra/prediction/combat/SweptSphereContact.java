package com.projectkorra.projectkorra.prediction.combat;

/**
 * Continuous collision test for two spheres moving over the same simulation
 * interval. The returned fraction is the first contact in {@code [0, 1]}.
 */
public final class SweptSphereContact {
    private static final double EPSILON = 1.0E-12;

    private SweptSphereContact() {
    }

    public static double firstContact(final double firstStartX, final double firstStartY,
                                      final double firstStartZ, final double firstEndX,
                                      final double firstEndY, final double firstEndZ,
                                      final double secondStartX, final double secondStartY,
                                      final double secondStartZ, final double secondEndX,
                                      final double secondEndY, final double secondEndZ,
                                      final double combinedRadius) {
        final double px = firstStartX - secondStartX;
        final double py = firstStartY - secondStartY;
        final double pz = firstStartZ - secondStartZ;
        final double vx = (firstEndX - firstStartX) - (secondEndX - secondStartX);
        final double vy = (firstEndY - firstStartY) - (secondEndY - secondStartY);
        final double vz = (firstEndZ - firstStartZ) - (secondEndZ - secondStartZ);
        final double radius = Math.max(0.0, combinedRadius);
        final double c = px * px + py * py + pz * pz - radius * radius;
        if (c <= EPSILON) return 0.0;

        final double a = vx * vx + vy * vy + vz * vz;
        if (a <= EPSILON) return Double.NaN;
        final double b = 2.0 * (px * vx + py * vy + pz * vz);
        final double discriminant = b * b - 4.0 * a * c;
        if (discriminant < -EPSILON) return Double.NaN;

        final double contact = (-b - Math.sqrt(Math.max(0.0, discriminant))) / (2.0 * a);
        if (contact < -EPSILON || contact > 1.0 + EPSILON) return Double.NaN;
        return Math.max(0.0, Math.min(1.0, contact));
    }
}
