package com.projectkorra.projectkorra.prediction.action;

/**
 * Immutable player view carried by an exact-prediction input frame.
 */
public record CapturedInputPose(double eyeX, double eyeY, double eyeZ, float yaw, float pitch) {
    /** Smallest signed angular difference in degrees, accounting for wraparound. */
    public static double signedAngleDelta(final double first, final double second) {
        double delta = (first - second) % 360.0;
        if (delta >= 180.0) delta -= 360.0;
        if (delta < -180.0) delta += 360.0;
        return delta;
    }

    /**
     * Eye locations use the captured height; feet locations retain their authoritative server height.
     */
    public double locationY(double authoritativeFeetY, boolean eyeLocation) {
        return eyeLocation ? this.eyeY : authoritativeFeetY;
    }
}
