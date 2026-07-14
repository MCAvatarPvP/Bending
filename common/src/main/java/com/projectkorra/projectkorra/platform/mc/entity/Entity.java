package com.projectkorra.projectkorra.platform.mc.entity;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent;
import com.projectkorra.projectkorra.platform.mc.metadata.MetadataValue;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.List;
import java.util.UUID;

public class Entity {
    public UUID getUniqueId() {
        return new UUID(0, 0);
    }

    public Location getLocation() {
        return new Location();
    }

    public World getWorld() {
        return new World();
    }

    public Vector getVelocity() {
        return new Vector();
    }

    public void setVelocity(Vector v) {
    }

    public boolean isDead() {
        return false;
    }

    public boolean isValid() {
        return true;
    }

    public void remove() {
    }

    public int getFireTicks() {
        return 0;
    }

    public void setFireTicks(int ticks) {
    }

    public boolean teleport(Location location) {
        return true;
    }

    public int getEntityId() {
        return 0;
    }

    public String getName() {
        return getClass().getSimpleName();
    }

    public float getFallDistance() {
        return 0;
    }

    public void setFallDistance(float distance) {
    }

    public void setSilent(boolean value) {
    }

    public void setInvulnerable(boolean value) {
    }

    public boolean addPassenger(Entity entity) {
        return true;
    }

    public List<Entity> getPassengers() {
        return List.of();
    }

    public BoundingBox getBoundingBox() {
        return new BoundingBox();
    }

    public boolean isOnGround() {
        return false;
    }

    public double getHeight() {
        return 1.8;
    }

    public EntityType getType() {
        return EntityType.valueOf(getClass().getSimpleName().toUpperCase());
    }

    public void setMetadata(String key, MetadataValue value) {
    }

    public boolean hasMetadata(String key) {
        return false;
    }

    public void removeMetadata(String key, Object owner) {
    }

    public void setGravity(boolean value) {
    }

    public void setPersistent(boolean value) {
    }

    public List<MetadataValue> getMetadata(String key) {
        return List.of();
    }

    public void setLastDamageCause(EntityDamageEvent event) {
    }

    public Object handle() {
        return this;
    }
}
