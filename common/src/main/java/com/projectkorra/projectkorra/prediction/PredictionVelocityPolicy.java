package com.projectkorra.projectkorra.prediction;

/**
 * Distinguishes a delayed echo of local movement from unrelated server knockback.
 */
public final class PredictionVelocityPolicy {
    private PredictionVelocityPolicy() {
    }

    public static boolean isSameImpulse(double px, double py, double pz, double ax, double ay, double az) {
        double dx = px - ax, dy = py - ay, dz = pz - az;
        if (dx * dx + dy * dy + dz * dz <= 0.08 * 0.08) return true;
        double predictedLength = Math.sqrt(px * px + py * py + pz * pz);
        double authoritativeLength = Math.sqrt(ax * ax + ay * ay + az * az);
        if (predictedLength < 0.05 || authoritativeLength < 0.05) return false;
        double alignment = (px * ax + py * ay + pz * az) / (predictedLength * authoritativeLength);
        double allowedMagnitudeDelta = Math.max(0.12, Math.max(predictedLength, authoritativeLength) * 0.35);
        return alignment >= 0.92 && Math.abs(predictedLength - authoritativeLength) <= allowedMagnitudeDelta;
    }
}
