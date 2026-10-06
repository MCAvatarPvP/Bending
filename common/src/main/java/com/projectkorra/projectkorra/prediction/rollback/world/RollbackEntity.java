package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.EntityType;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent;
import com.projectkorra.projectkorra.platform.mc.metadata.MetadataValue;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Base Entity API view. Specialized platform types share the same body delegate. */
public final class RollbackEntity extends Entity implements RollbackEntityBody.View, RollbackStateCell<Void> {
    private final RollbackEntityBody body;
    public RollbackEntity(RollbackEntityBody body) { this.body = body; body.bind(this); }
    @Override public RollbackEntityBody body() { return body; }
    @Override public UUID getUniqueId() { return body.identity().uuid(); }
    @Override public int getEntityId() { return body.identity().networkId(); }
    @Override public String getName() { return body.identity().name(); }
    @Override public EntityType getType() { return body.identity().type(); }
    @Override public Location getLocation() { return body.location(); }
    @Override public World getWorld() { return body.world(); }
    @Override public Vector getVelocity() { return body.velocity(); }
    @Override public void setVelocity(Vector value) { body.velocity(value); }
    @Override public boolean isDead() { return body.dead(); }
    @Override public boolean isValid() { return body.valid(); }
    @Override public void remove() { body.remove(); }
    @Override public int getFireTicks() { return body.fireTicks(); }
    @Override public void setFireTicks(int value) { body.fireTicks(value); }
    @Override public boolean teleport(Location value) { return body.teleport(value); }
    @Override public float getFallDistance() { return (float) body.kinematics().fallDistance(); }
    @Override public void setFallDistance(float value) { body.fallDistance(value); }
    @Override public void setSilent(boolean value) { body.silent(value); }
    @Override public void setInvulnerable(boolean value) { body.invulnerable(value); }
    @Override public boolean addPassenger(Entity value) { return body.addPassenger(value); }
    @Override public List<Entity> getPassengers() { return body.passengers(); }
    @Override public BoundingBox getBoundingBox() { return body.bounds(); }
    @Override public boolean isOnGround() { return body.kinematics().onGround(); }
    @Override public double getHeight() { return body.kinematics().height(); }
    @Override public void setMetadata(String key, MetadataValue value) { body.metadata(key, value); }
    @Override public boolean hasMetadata(String key) { return body.hasMetadata(key); }
    @Override public void removeMetadata(String key, Object owner) { body.removeMetadata(key, owner); }
    @Override public List<MetadataValue> getMetadata(String key) { return body.metadata(key); }
    @Override public void setGravity(boolean value) { body.gravity(value); }
    @Override public void setPersistent(boolean value) { body.persistent(value); }
    @Override public void setLastDamageCause(EntityDamageEvent value) { body.lastDamage(value); }
    @Override public Object handle() { return this; }
    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void ignored) { }
    @Override public Collection<?> rollbackReferences() { return List.of(body); }
}
