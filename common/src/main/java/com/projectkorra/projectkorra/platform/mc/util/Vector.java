package com.projectkorra.projectkorra.platform.mc.util;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;

public class Vector implements Cloneable {
    public double x, y, z;

    public Vector() {
    }

    public Vector(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double getX() {
        return x;
    }

    public Vector setX(double x) {
        this.x = x;
        return this;
    }

    public double getY() {
        return y;
    }

    public Vector setY(double y) {
        this.y = y;
        return this;
    }

    public double getZ() {
        return z;
    }

    public Vector setZ(double z) {
        this.z = z;
        return this;
    }

    public Vector add(Vector v) {
        x += v.x;
        y += v.y;
        z += v.z;
        return this;
    }

    public Vector subtract(Vector v) {
        x -= v.x;
        y -= v.y;
        z -= v.z;
        return this;
    }

    public Vector multiply(double m) {
        x *= m;
        y *= m;
        z *= m;
        return this;
    }

    public Vector multiply(Vector v) {
        x *= v.x;
        y *= v.y;
        z *= v.z;
        return this;
    }

    public Vector divide(Vector v) {
        x /= v.x;
        y /= v.y;
        z /= v.z;
        return this;
    }

    public Vector normalize() {
        double length = length();
        if (length != 0) multiply(1 / length);
        return this;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public double distance(Vector v) {
        return Math.sqrt(distanceSquared(v));
    }

    public double dot(Vector v) {
        return x * v.x + y * v.y + z * v.z;
    }

    public Vector crossProduct(Vector v) {
        double nx = y * v.z - z * v.y, ny = z * v.x - x * v.z, nz = x * v.y - y * v.x;
        x = nx;
        y = ny;
        z = nz;
        return this;
    }

    public double distanceSquared(Vector v) {
        double dx = x - v.x, dy = y - v.y, dz = z - v.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double angle(Vector other) {
        double denominator = Math.sqrt(lengthSquared() * other.lengthSquared());
        return denominator == 0 ? 0 : Math.acos(Math.max(-1, Math.min(1, dot(other) / denominator)));
    }

    public Vector zero() {
        x = y = z = 0;
        return this;
    }

    public Vector rotateAroundX(double angle) {
        double c = Math.cos(angle), s = Math.sin(angle), ny = y * c - z * s, nz = y * s + z * c;
        y = ny;
        z = nz;
        return this;
    }

    public Vector rotateAroundY(double angle) {
        double c = Math.cos(angle), s = Math.sin(angle), nx = x * c + z * s, nz = -x * s + z * c;
        x = nx;
        z = nz;
        return this;
    }

    public Location toLocation(World world) {
        return new Location(world, x, y, z);
    }

    public Vector toBlockVector() {
        x = Math.floor(x);
        y = Math.floor(y);
        z = Math.floor(z);
        return this;
    }

    public Vector copy(Vector other) {
        this.x = other.x;
        this.y = other.y;
        this.z = other.z;
        return this;
    }

    @Override
    public Vector clone() {
        return new Vector(x, y, z);
    }
}
