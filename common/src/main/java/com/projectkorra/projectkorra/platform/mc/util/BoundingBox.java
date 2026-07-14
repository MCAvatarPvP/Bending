package com.projectkorra.projectkorra.platform.mc.util;

import com.projectkorra.projectkorra.platform.mc.Location;

public class BoundingBox {
    private final Vector min, max;

    public BoundingBox() {
        this(new Vector(), new Vector());
    }

    public BoundingBox(Vector min, Vector max) {
        this.min = min;
        this.max = max;
    }

    public static BoundingBox of(Location min, Location max) {
        return new BoundingBox(min.toVector(), max.toVector());
    }

    public Vector getMin() {
        return min;
    }

    public Vector getMax() {
        return max;
    }

    public boolean overlaps(Vector min, Vector max) {
        return this.max.x >= min.x && this.min.x <= max.x && this.max.y >= min.y && this.min.y <= max.y && this.max.z >= min.z && this.min.z <= max.z;
    }

    public boolean overlaps(BoundingBox other) {
        return other != null && overlaps(other.min, other.max);
    }

    public Vector getCenter() {
        return new Vector((min.x + max.x) / 2.0, (min.y + max.y) / 2.0, (min.z + max.z) / 2.0);
    }

    public BoundingBox expand(double amount) {
        return new BoundingBox(new Vector(min.x - amount, min.y - amount, min.z - amount), new Vector(max.x + amount, max.y + amount, max.z + amount));
    }

    public BoundingBox shift(double x, double y, double z) {
        return new BoundingBox(new Vector(min.x + x, min.y + y, min.z + z), new Vector(max.x + x, max.y + y, max.z + z));
    }

    public double getMinX() {
        return min.x;
    }

    public double getMinY() {
        return min.y;
    }

    public double getMinZ() {
        return min.z;
    }

    public double getMaxX() {
        return max.x;
    }

    public double getMaxY() {
        return max.y;
    }

    public double getMaxZ() {
        return max.z;
    }
}
