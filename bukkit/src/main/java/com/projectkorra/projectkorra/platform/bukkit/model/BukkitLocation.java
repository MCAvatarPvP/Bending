package com.projectkorra.projectkorra.platform.bukkit.model;

import com.projectkorra.projectkorra.platform.model.PKLocation;
import com.projectkorra.projectkorra.platform.model.PKVec3;
import com.projectkorra.projectkorra.platform.model.PKWorld;
import org.bukkit.Location;

public final class BukkitLocation implements PKLocation {
    private final Location location;

    public BukkitLocation(final Location location) {
        this.location = location;
    }

    @Override
    public Object handle() {
        return this.location;
    }

    @Override
    public PKWorld world() {
        return new BukkitWorld(this.location.getWorld());
    }

    @Override
    public double x() {
        return this.location.getX();
    }

    @Override
    public double y() {
        return this.location.getY();
    }

    @Override
    public double z() {
        return this.location.getZ();
    }

    @Override
    public float yaw() {
        return this.location.getYaw();
    }

    @Override
    public float pitch() {
        return this.location.getPitch();
    }

    @Override
    public PKVec3 direction() {
        return BukkitAdapter.fromVector(this.location.getDirection());
    }
}
