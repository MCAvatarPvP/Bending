package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.attribute.AttributeInstance;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.EntityType;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent;
import com.projectkorra.projectkorra.platform.mc.inventory.EntityEquipment;
import com.projectkorra.projectkorra.platform.mc.metadata.MetadataValue;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Full LivingEntity API view with logical storage and mandatory native-behavior callbacks. */
public final class RollbackLivingEntity extends LivingEntity implements RollbackEntityBody.View, RollbackStateCell<Void> {
    private final RollbackEntityBody body;
    private final RollbackLivingState living;
    public RollbackLivingEntity(RollbackLivingState living) {
        this.living = living;
        body = living.body();
        body.bind(this);
    }
    @Override public RollbackEntityBody body() { return body; }
    public RollbackLivingState living() { return living; }
    @Override public UUID getUniqueId() { return body.identity().uuid(); }
    @Override public int getEntityId() { return body.identity().networkId(); }
    @Override public String getName() { return body.identity().name(); }
    @Override public EntityType getType() { return body.identity().type(); }
    @Override public Location getLocation() { return body.location(); }
    @Override public World getWorld() { return body.world(); }
    @Override public Vector getVelocity() { return body.velocity(); }
    @Override public void setVelocity(Vector value) { body.velocity(value); }
    @Override public boolean isDead() { return living.dead(); }
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
    @Override public Location getEyeLocation() { return living.eyeLocation(); }
    @Override public double getEyeHeight() { return living.vitals().eyeHeight(); }
    @Override public double getHealth() { return living.vitals().health(); }
    @Override public void setHealth(double value) { living.health(value); }
    @Override public double getMaxHealth() { return living.maxHealth(); }
    @Override public void damage(double amount) { living.damage(amount, null); }
    @Override public void damage(double amount, Entity source) { living.damage(amount, source); }
    @Override public boolean hasPotionEffect(PotionEffectType type) { return living.hasPotion(type); }
    @Override public void addPotionEffect(PotionEffect effect) { living.addPotion(effect, false); }
    @Override public void addPotionEffects(Collection<PotionEffect> effects) { if (effects != null) effects.forEach(this::addPotionEffect); }
    @Override public boolean addPotionEffect(PotionEffect effect, boolean force) { return living.addPotion(effect, force); }
    @Override public PotionEffect getPotionEffect(PotionEffectType type) { return living.potion(type); }
    @Override public void removePotionEffect(PotionEffectType type) { living.removePotion(type); }
    @Override public int getNoDamageTicks() { return living.vitals().noDamageTicks(); }
    @Override public void setNoDamageTicks(int value) { living.noDamageTicks(value); }
    @Override public int getMaximumNoDamageTicks() { return living.vitals().maximumNoDamageTicks(); }
    @Override public double getLastDamage() { return living.vitals().lastDamage(); }
    @Override public AttributeInstance getAttribute(Attribute attribute) { return living.attribute(attribute); }
    @Override public Collection<PotionEffect> getActivePotionEffects() { return living.potions(); }
    @Override public EntityEquipment getEquipment() { return living.equipment(); }
    @Override public void setAI(boolean value) { living.ai(value); }
    @Override public int getRemainingAir() { return living.vitals().remainingAir(); }
    @Override public int getMaximumAir() { return living.vitals().maximumAir(); }
    @Override public void setRemainingAir(int value) { living.remainingAir(value); }
    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void ignored) { }
    @Override public Collection<?> rollbackReferences() { return List.of(body, living); }
}
