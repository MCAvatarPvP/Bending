package com.projectkorra.projectkorra.firebending;

import com.projectkorra.projectkorra.platform.mc.util.Vector;

final class WallOfFireHitGeometry {
    private WallOfFireHitGeometry() {
    }

    static boolean segmentIntersectsLocalAABB(Vector P0, Vector P1, double minX, double maxX,
                                              double minY, double maxY, double minZ, double maxZ) {
        double tmin = 0.0, tmax = 1.0;
        double[] a0 = {P0.getX(), P0.getY(), P0.getZ()};
        double[] a1 = {P1.getX(), P1.getY(), P1.getZ()};
        double[] mn = {minX, minY, minZ};
        double[] mx = {maxX, maxY, maxZ};
        for (int i = 0; i < 3; i++) {
            double d = a1[i] - a0[i];
            if (Math.abs(d) < 1e-9) {
                if (a0[i] < mn[i] || a0[i] > mx[i]) return false;
            } else {
                double inv = 1.0 / d;
                double t1 = (mn[i] - a0[i]) * inv, t2 = (mx[i] - a0[i]) * inv;
                if (t1 > t2) {
                    double tmp = t1;
                    t1 = t2;
                    t2 = tmp;
                }
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                if (tmin > tmax) return false;
            }
        }
        return true;
    }
}
