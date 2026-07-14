package com.projectkorra.projectkorra.platform.model;

/**
 * Immutable platform-neutral 3D vector.
 */
public record PKVec3(double x, double y, double z) {
    public PKVec3 add(final PKVec3 other) {
        return new PKVec3(x + other.x, y + other.y, z + other.z);
    }

    public PKVec3 subtract(final PKVec3 other) {
        return new PKVec3(x - other.x, y - other.y, z - other.z);
    }

    public PKVec3 multiply(final double factor) {
        return new PKVec3(x * factor, y * factor, z * factor);
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }
}
