package com.projectkorra.projectkorra.airbending.combo;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.ability.util.ComboUtil;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.firebending.combo.FireComboStream;
import com.projectkorra.projectkorra.object.HorizontalVelocityTracker;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.CooldownSync;
import com.projectkorra.projectkorra.prediction.ConfirmedHitEffects;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.colliders.AABB;
import com.projectkorra.projectkorra.util.colliders.Ray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AirSweep extends AirAbility implements ComboAbility {

    private int progressCounter;
    private int activationDelayTicks;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.SPEED)
    private double speed;
    @Attribute(Attribute.RANGE)
    private double range;
    @Attribute(Attribute.KNOCKBACK)
    private double knockback;
    @Attribute(Attribute.HEIGHT)
    private double heightOffset;
    private boolean oldKnockback;
    private boolean oldBehavior;
    private boolean goThroughWater;
    private Location origin;
    private Location currentLoc;
    private Location destination;
    private Vector direction;
    private ArrayList<Entity> affectedEntities;
    private ArrayList<BukkitRunnable> tasks;
    private Map<FireComboStream, Location> previousStreamLocations;
    private double radius;
    private double regenAmount;

    public AirSweep(final Player player) {
        super(player);

        this.affectedEntities = new ArrayList<>();
        this.tasks = new ArrayList<>();
        this.previousStreamLocations = new HashMap<>();

        if (!this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
            return;
        }

        if (this.bPlayer.isOnCooldown(this)) {
            return;
        }

        this.damage = getConfig().getDouble("Abilities.Air.AirSweep.Damage");
        this.range = getConfig().getDouble("Abilities.Air.AirSweep.Range");
        this.speed = getConfig().getDouble("Abilities.Air.AirSweep.Speed");
        this.knockback = getConfig().getDouble("Abilities.Air.AirSweep.Knockback");
        this.heightOffset = getConfig().getDouble("Abilities.Air.AirSweep.HeightOffset");
        this.oldKnockback = getConfig().getBoolean("Abilities.Air.AirSweep.OldKnockback");
        this.oldBehavior = getConfig().getBoolean("Abilities.Air.AirSweep.OldBehavior");
        this.goThroughWater = getConfig().getBoolean("Abilities.Air.AirSweep.GoThroughWater");
        this.cooldown = getConfig().getLong("Abilities.Air.AirSweep.Cooldown");
        this.radius = getConfig().getDouble("Abilities.Air.AirSweep.Radius");
        this.activationDelayTicks = getConfig().getInt("Abilities.Air.AirSweep.ActivationDelayTicks");
        this.regenAmount = getConfig().getDouble("Abilities.Air.AirSweep.RegenAmount", 0.33);

        this.bPlayer.addCooldown(this);
        this.start();
    }

    @Override
    public String getName() {
        return "AirSweep";
    }

    @Override
    public boolean isCollidable() {
        return true;
    }

    @Override
    public void handleCollision(final Collision collision) {
        if (collision.isRemovingFirst()) {
            remove();
        }
    }

    private void playSound(final Location loc, final Sound sound, final float volume, final float pitch) {
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().playSound(loc, sound, volume, pitch);
        }
    }

    @Override
    public List<Location> getLocations() {
        final ArrayList<Location> locations = new ArrayList<>();
        for (final BukkitRunnable task : this.getTasks()) {
            if (task instanceof FireComboStream) {
                final FireComboStream stream = (FireComboStream) task;
                locations.add(stream.getLocation());
            }
        }
        return locations;
    }

    @Override
    public void progress() {
        this.progressCounter++;
        if (this.player.isDead() || !this.player.isOnline()) {
            this.remove();
            return;
        } else if (this.currentLoc != null && GeneralMethods.isRegionProtectedFromBuild(this, this.currentLoc)) {
            this.remove();
            return;
        }

        if (this.origin == null) {
            this.direction = this.player.getEyeLocation().getDirection().normalize();
            this.origin = GeneralMethods.getMainHandLocation(player).add(this.direction.clone().multiply(10));
            if (oldBehavior) this.origin = this.player.getLocation().add(this.direction.clone().multiply(10));
        }
        if (this.progressCounter < activationDelayTicks) {
            return;
        }
        if (this.destination == null) {
            this.destination = GeneralMethods.getMainHandLocation(player).add(GeneralMethods.getMainHandLocation(player).getDirection().normalize().multiply(10));
            Location hand = GeneralMethods.getMainHandLocation(player).add(0, heightOffset, 0);
            if (oldBehavior) {
                this.destination = this.player.getLocation().add(player.getEyeLocation().getDirection().normalize().multiply(10));
                hand = this.player.getLocation();
            }

            playSound(hand, Sound.ENTITY_BREEZE_IDLE_GROUND, 1.0f, 1.6f);

            final Vector origToDest = GeneralMethods.getDirection(this.origin, this.destination);
            final int streamCount = Math.max(30, (int) Math.ceil(origToDest.length() / Math.max(this.radius * 0.35, 0.2)));
            for (int i = 0; i < streamCount; i++) {
                final Location endLoc = this.origin.clone().add(origToDest.clone().multiply((double) i / streamCount));
                if (GeneralMethods.locationEqualsIgnoreDirection(hand, endLoc)) {
                    continue;
                }
                final Vector vec = GeneralMethods.getDirection(hand, endLoc);

                final FireComboStream fs = new FireComboStream(this.player, this, vec, hand, this.range, this.speed);
                fs.setDensity(1);
                fs.setParticlesVisible(i % 5 != 1 && i % 5 != 3);
                fs.setSpread(0F);
                fs.setSubLocations(2);
                fs.setUseNewParticles(true);
                fs.setGoThroughWater(goThroughWater);
                fs.setParticleEffect(getAirbendingParticles());
                fs.setCollides(false);
                fs.runTaskTimer(ProjectKorra.plugin, (long) (i / Math.max(streamCount / 12.0, 1.0)), 1L);
                this.tasks.add(fs);
            }
        }
        this.manageAirVectors();
    }

    public void manageAirVectors() {
        for (int i = 0; i < this.tasks.size(); i++) {
            final FireComboStream stream = (FireComboStream) this.tasks.get(i);
            if (stream.isCancelled()) {
                this.previousStreamLocations.remove(stream);
                this.tasks.remove(i);
                i--;
            }
        }
        if (this.tasks.size() == 0) {
            this.remove();
            return;
        }
        for (int i = 0; i < this.tasks.size(); i++) {
            final FireComboStream fstream = (FireComboStream) this.tasks.get(i);
            final Location loc = fstream.getLocation();
            final Location previousLoc = this.previousStreamLocations.getOrDefault(fstream, loc).clone();

            if (GeneralMethods.isRegionProtectedFromBuild(this, loc)) {
                fstream.remove();
                this.previousStreamLocations.remove(fstream);
                continue;
            }

            if (this.isBlockedBetween(previousLoc, loc, fstream.getDirection())) {
                fstream.remove();
                this.previousStreamLocations.remove(fstream);
                continue;
            }

            for (final Entity entity : this.getEntitiesAlongSegment(previousLoc, loc)) {
                if (this.hitEntity(entity, fstream)) {
                    return;
                }
            }

            this.previousStreamLocations.put(fstream, loc.clone());
        }
    }

    private boolean isBlockedBetween(final Location previousLoc, final Location loc, final Vector direction) {
        final double distance = previousLoc.distance(loc);
        final int steps = Math.max(1, (int) Math.ceil(distance / 0.25));
        final Vector step = loc.toVector().subtract(previousLoc.toVector()).multiply(1.0 / steps);
        final Location check = previousLoc.clone();

        for (int i = 0; i <= steps; i++) {
            if (GeneralMethods.checkDiagonalWall(check, direction)) {
                return true;
            }
            if (this.isBlocked(check.getBlock())) {
                return true;
            }
            check.add(step);
        }

        return false;
    }

    private boolean isBlocked(final Block block) {
        return block == null || !block.isPassable() || block.isLiquid() || !this.isTransparent(block);
    }

    private List<Entity> getEntitiesAlongSegment(final Location previousLoc, final Location loc) {
        final ArrayList<Entity> entities = new ArrayList<>();
        final double distance = previousLoc.distance(loc);

        if (distance < 0.0001) {
            entities.addAll(GeneralMethods.getEntitiesAroundPoint(loc, this.radius));
            return entities;
        }

        final Vector direction = loc.toVector().subtract(previousLoc.toVector()).normalize();
        final Ray movement = new Ray(previousLoc, direction, distance);
        final AABB searchBox = new AABB(previousLoc, loc).expand(this.radius + 1.0);

        for (final Entity entity : searchBox.getEntities(this::canHitEntity)) {
            final BoundingBox expandedBox = entity.getBoundingBox().expand(this.radius);
            if (movement.intersects(new AABB(entity.getWorld(), expandedBox))) {
                entities.add(entity);
            }
        }

        return entities;
    }

    private boolean canHitEntity(final Entity entity) {
        return !entity.equals(this.player)
                && !entity.isDead()
                && !(entity instanceof Player && Commands.invincible.contains(((Player) entity).getName()));
    }

    private boolean hitEntity(final Entity entity, final FireComboStream stream) {
        if (!this.canHitEntity(entity)) {
            return false;
        }

        if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) {
            this.remove();
            return true;
        }
        if (this.affectedEntities.contains(entity)) {
            return false;
        }
        this.affectedEntities.add(entity);

        if (this.knockback != 0) {
            Vector force = stream.getLocation().getDirection();
            if (this.oldKnockback) {
                force = stream.getDirection();
            }
            GeneralMethods.setVelocity(this, entity, force.clone().multiply(this.knockback));
            new HorizontalVelocityTracker(entity, this.player, 200L, this);
            entity.setFallDistance(0);
        }

        if (this.damage == 0 || !(entity instanceof LivingEntity)) {
            return false;
        }

        DamageHandler.damageEntity(entity, this.damage, this);
        final Location hitLocation = entity.getLocation().clone();
        ConfirmedHitEffects.sound(this, entity,
                () -> playSound(hitLocation, Sound.ENTITY_BREEZE_WIND_BURST, 1, 0.5f));

        if (entity instanceof Player entityPlayer) {
            final BendingPlayer otherBP = BendingPlayer.getBendingPlayer(entityPlayer);
            if (otherBP != null && otherBP.hasElement(Element.AIR)) {
                CooldownSync.regenerateAirBlastOnConfirmedHit(
                        this, entity, this.bPlayer, this.regenAmount, 1.0);
            }
        }

        return false;
    }

    @Override
    public void remove() {
        super.remove();
        for (final BukkitRunnable task : this.tasks) {
            task.cancel();
        }
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    @Override
    public Location getLocation() {
        return this.origin;
    }

    @Override
    public Object createNewComboInstance(final Player player) {
        return new AirSweep(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        return ComboUtil.generateCombinationFromList(this, ConfigManager.defaultConfig.get().getStringList("Abilities.Air.AirSweep.Combination"));
    }


    public Location getOrigin() {
        return this.origin;
    }

    public void setOrigin(final Location origin) {
        this.origin = origin;
    }

    public Location getCurrentLoc() {
        return this.currentLoc;
    }

    public void setCurrentLoc(final Location currentLoc) {
        this.currentLoc = currentLoc;
    }

    public Location getDestination() {
        return this.destination;
    }

    public void setDestination(final Location destination) {
        this.destination = destination;
    }

    public Vector getDirection() {
        return this.direction;
    }

    public void setDirection(final Vector direction) {
        this.direction = direction;
    }

    public int getProgressCounter() {
        return this.progressCounter;
    }

    public void setProgressCounter(final int progressCounter) {
        this.progressCounter = progressCounter;
    }

    public double getDamage() {
        return this.damage;
    }

    public void setDamage(final double damage) {
        this.damage = damage;
    }

    public double getSpeed() {
        return this.speed;
    }

    public void setSpeed(final double speed) {
        this.speed = speed;
    }

    public double getRange() {
        return this.range;
    }

    public void setRange(final double range) {
        this.range = range;
    }

    public double getKnockback() {
        return this.knockback;
    }

    public void setKnockback(final double knockback) {
        this.knockback = knockback;
    }

    public ArrayList<Entity> getAffectedEntities() {
        return this.affectedEntities;
    }

    public ArrayList<BukkitRunnable> getTasks() {
        return this.tasks;
    }

    public void setTasks(final ArrayList<BukkitRunnable> tasks) {
        this.tasks = tasks;
    }
}
