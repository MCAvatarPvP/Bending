package com.projectkorra.projectkorra.platform.mc;

import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.Objects;

public class Location implements Cloneable {
    private World world;
    private double x, y, z;
    private float yaw, pitch;

    public Location() {
    }

    public Location(World world, double x, double y, double z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public World getWorld() {
        return world;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getZ() {
        return z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public int getBlockX() {
        return (int) Math.floor(getX());
    }

    public int getBlockY() {
        return (int) Math.floor(getY());
    }

    public int getBlockZ() {
        return (int) Math.floor(getZ());
    }

    public Vector getDirection() {
        double yawRadians = Math.toRadians(yaw), pitchRadians = Math.toRadians(pitch), horizontal = Math.cos(pitchRadians);
        return new Vector(-horizontal * Math.sin(yawRadians), -Math.sin(pitchRadians), horizontal * Math.cos(yawRadians));
    }

    public Location setDirection(Vector direction) {
        if (direction == null || direction.lengthSquared() == 0) return this;
        Vector normal = direction.clone().normalize();
        pitch = (float) Math.toDegrees(Math.asin(-normal.getY()));
        yaw = (float) Math.toDegrees(Math.atan2(-normal.getX(), normal.getZ()));
        return this;
    }

    public Block getBlock() {
        return getWorld() == null ? new Block() : getWorld().getBlockAt(this);
    }

    public Location add(double x, double y, double z) {
        setX(getX() + x);
        setY(getY() + y);
        setZ(getZ() + z);
        return this;
    }

    public Location add(Vector vector) {
        return add(vector.getX(), vector.getY(), vector.getZ());
    }

    public Location add(Location other) {
        return add(other.getX(), other.getY(), other.getZ());
    }

    public Location subtract(double x, double y, double z) {
        return add(-x, -y, -z);
    }

    public Location subtract(Location other) {
        return subtract(other.getX(), other.getY(), other.getZ());
    }

    public Location subtract(Vector vector) {
        return subtract(vector.getX(), vector.getY(), vector.getZ());
    }

    public Location multiply(double amount) {
        setX(getX() * amount);
        setY(getY() * amount);
        setZ(getZ() * amount);
        return this;
    }

    public double distanceSquared(Location other) {
        double dx = getX() - other.getX(), dy = getY() - other.getY(), dz = getZ() - other.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(Location other) {
        return Math.sqrt(distanceSquared(other));
    }

    public Vector toVector() {
        return new Vector(getX(), getY(), getZ());
    }

    public Location toBlockLocation() {
        return new Location(getWorld(), getBlockX(), getBlockY(), getBlockZ());
    }

    public void checkFinite() {
        if (!Double.isFinite(getX()) || !Double.isFinite(getY()) || !Double.isFinite(getZ()))
            throw new IllegalArgumentException("non-finite location");
    }

    @Override
    public Location clone() {
        Location copy = new Location(getWorld(), getX(), getY(), getZ());
        copy.setYaw(getYaw());
        copy.setPitch(getPitch());
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Location location)) return false;
        return Objects.equals(getWorld(), location.getWorld())
                && Double.compare(getX(), location.getX()) == 0
                && Double.compare(getY(), location.getY()) == 0
                && Double.compare(getZ(), location.getZ()) == 0
                && Float.compare(getYaw(), location.getYaw()) == 0
                && Float.compare(getPitch(), location.getPitch()) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getWorld(), getX(), getY(), getZ(), getYaw(), getPitch());
    }

    public Object handle() {
        return this;
    }
}
