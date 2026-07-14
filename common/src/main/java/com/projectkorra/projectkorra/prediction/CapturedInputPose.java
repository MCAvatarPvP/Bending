package com.projectkorra.projectkorra.prediction;

/**
 * Immutable player view carried by an exact-prediction input frame.
 */
public record CapturedInputPose(double eyeX, double eyeY, double eyeZ, float yaw, float pitch) {
    /**
     * Eye locations use the captured height; feet locations retain their authoritative server height.
     */
    public double locationY(double authoritativeFeetY, boolean eyeLocation) {
        return eyeLocation ? this.eyeY : authoritativeFeetY;
    }
}
