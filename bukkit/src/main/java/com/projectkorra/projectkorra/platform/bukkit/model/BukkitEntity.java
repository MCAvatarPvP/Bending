package com.projectkorra.projectkorra.platform.bukkit.model;

import com.projectkorra.projectkorra.platform.model.PKEntity;
import com.projectkorra.projectkorra.platform.model.PKLocation;
import com.projectkorra.projectkorra.platform.model.PKVec3;
import com.projectkorra.projectkorra.platform.model.PKWorld;
import org.bukkit.entity.Entity;

import java.util.UUID;

public class BukkitEntity implements PKEntity {
    protected final Entity entity;

    public BukkitEntity(final Entity entity) {
        this.entity = entity;
    }

    @Override
    public Object handle() {
        return this.entity;
    }

    @Override
    public UUID uuid() {
        return this.entity.getUniqueId();
    }

    @Override
    public PKWorld world() {
        return new BukkitWorld(this.entity.getWorld());
    }

    @Override
    public PKLocation location() {
        return new BukkitLocation(this.entity.getLocation());
    }

    @Override
    public PKVec3 velocity() {
        return BukkitAdapter.fromVector(this.entity.getVelocity());
    }

    @Override
    public void velocity(final PKVec3 velocity) {
        this.entity.setVelocity(BukkitAdapter.toVector(velocity));
    }

    @Override
    public boolean valid() {
        return this.entity.isValid();
    }

    @Override
    public void remove() {
        this.entity.remove();
    }
}
