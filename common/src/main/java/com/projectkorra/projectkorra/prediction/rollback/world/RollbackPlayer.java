package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.chat.ChatMessageType;
import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.attribute.AttributeInstance;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent;
import com.projectkorra.projectkorra.platform.mc.inventory.EntityEquipment;
import com.projectkorra.projectkorra.platform.mc.inventory.MainHand;
import com.projectkorra.projectkorra.platform.mc.inventory.PlayerInventory;
import com.projectkorra.projectkorra.platform.mc.metadata.MetadataValue;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState.Flag.*;

/** Player API over the same rewindable body, living state and inventory used by replay. */
public final class RollbackPlayer extends Player implements RollbackEntityBody.View, RollbackStateCell<Void> {
    private final RollbackPlayerState state;
    private final RollbackLivingState living;
    private final RollbackEntityBody body;
    public RollbackPlayer(RollbackPlayerState state) {
        this.state = state;
        living = state.living();
        body = living.body();
        if (body.identity().type() != EntityType.PLAYER) throw new IllegalArgumentException("Player body has another entity type");
        body.bind(this);
    }
    public RollbackPlayerState state() { return state; }
    @Override public RollbackEntityBody body() { return body; }
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
    @Override public boolean hasPermission(String permission) { return state.permission(permission); }
    @Override public void sendMessage(String message) { state.message(ChatMessageType.CHAT, message); }
    @Override public CommandSender.Spigot spigot() { return new CommandSender.Spigot(state::message); }
    @Override public PlayerInventory getInventory() { return state.inventory(); }
    @Override public GameMode getGameMode() { return GameMode.valueOf(state.profile().gameMode()); }
    @Override public boolean isOnline() { return state.profile().online(); }
    @Override public boolean hasPlayedBefore() { return state.profile().playedBefore(); }
    @Override public Player getPlayer() { return isOnline() ? this : null; }
    @Override public String getDisplayName() { return state.profile().displayName(); }
    @Override public void setDisplayName(String name) { state.displayName(name); }
    @Override public boolean isFlying() { return state.controls().has(FLYING); }
    @Override public void setFlying(boolean value) { state.flag(FLYING, value); }
    @Override public boolean getAllowFlight() { return state.controls().has(ALLOW_FLIGHT); }
    @Override public void setAllowFlight(boolean value) { state.flag(ALLOW_FLIGHT, value); }
    @Override public Block getTargetBlock(Set<Material> transparent, int range) { return state.targetBlock(transparent, range); }
    @Override public boolean isSneaking() { return state.controls().has(SNEAKING); }
    @Override public void setSneaking(boolean value) { state.flag(SNEAKING, value); }
    @Override public boolean isSprinting() { return state.controls().has(SPRINTING); }
    @Override public void setSprinting(boolean value) { state.flag(SPRINTING, value); }
    @Override public void playSound(Location location, Sound sound, float volume, float pitch) { state.sound(location, sound, volume, pitch); }
    @Override public void playNote(Location location, Instrument instrument, Note note) { state.note(location, instrument, note); }
    // Inputs already execute on their agreed simulation tick. Legacy abilities and
    // shared lag compensators must not shift hit geometry/timing by transport RTT
    // a second time. Actual network RTT remains available in the session/profile;
    // this private view does not change the live player's tab ping.
    @Override public int getPing() { return 0; }
    @Override public double getAbsorptionAmount() { return living.vitals().absorption(); }
    @Override public void setAbsorptionAmount(double amount) { living.absorption(amount); }
    @Override public boolean isOp() { return state.profile().operator(); }
    @Override public MainHand getMainHand() { return state.profile().mainHand().equals("LEFT") ? MainHand.LEFT : MainHand.RIGHT; }
    @Override public void setRotation(float yaw, float pitch) { body.rotation(yaw, pitch); }
    @Override public boolean eject() { return body.eject(); }
    @Override public boolean isInsideVehicle() { return body.insideVehicle(); }
    @Override public boolean isGliding() { return state.controls().has(GLIDING); }
    @Override public void setGliding(boolean value) { state.flag(GLIDING, value); }
    @Override public float getExp() { return state.controls().experience(); }
    @Override public void setExp(float value) { state.experience(value); }
    @Override public float getFlySpeed() { return state.controls().flySpeed(); }
    @Override public void setFlySpeed(float value) { state.flySpeed(value); }
    @Override public boolean getCanPickupItems() { return state.controls().has(PICKUP_ITEMS); }
    @Override public void setCanPickupItems(boolean value) { state.flag(PICKUP_ITEMS, value); }
    @Override public boolean isSwimming() { return state.controls().has(SWIMMING); }
    @Override public boolean isGlowing() { return state.controls().has(GLOWING); }
    @Override public void setGlowing(boolean value) { state.flag(GLOWING, value); }
    @Override public Scoreboard getScoreboard() { return state.scoreboard(); }
    @Override public void setScoreboard(Scoreboard value) { state.scoreboard(value); }
    @Override public boolean canSee(Player player) { return state.canSee(player); }
    @Override public boolean hasLineOfSight(Entity entity) { return state.lineOfSight(entity); }
    @Override public Block getTargetBlockExact(int range) { return state.exactTarget(range); }
    @Override public List<Block> getLastTwoTargetBlocks(Set<Material> transparent, int range) { return state.targetBlocks(transparent, range); }
    @Override public List<Entity> getNearbyEntities(double x, double y, double z) { return state.nearby(x, y, z); }
    @Override public <T extends Projectile> T launchProjectile(Class<T> type) { return state.launch(type); }
    @Override public void sendBlockChange(Location location, BlockData data) { state.blockChange(location, data); }
    @Override public <T> void spawnParticle(Particle particle, Location location, int count, double x, double y, double z, double extra, T data, boolean force) {
        state.particle(particle, location, count, x, y, z, extra, data, force);
    }
    @Override public void spawnParticle(Particle particle, Location location, int count, double x, double y, double z) {
        state.particle(particle, location, count, x, y, z, 1, null, false);
    }
    @Override public float getExhaustion() { return state.controls().exhaustion(); }
    @Override public void setExhaustion(float value) { state.exhaustion(value); }
    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void ignored) { }
    @Override public Collection<?> rollbackReferences() { return List.of(state); }
}
