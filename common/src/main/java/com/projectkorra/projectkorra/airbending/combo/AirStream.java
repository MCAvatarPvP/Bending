package com.projectkorra.projectkorra.airbending.combo;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.ability.util.ComboUtil;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.ArrayList;

public class AirStream extends AirAbility implements ComboAbility {

    private static final double DIRECTION_EPSILON = 1.0E-6;

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;

    private long time;

    @Attribute(Attribute.SPEED)
    private double speed;

    @Attribute(Attribute.RANGE)
    private double range;

    @Attribute("Max" + Attribute.DURATION)
    private long maxDuration;

    @Attribute("EntityCarry" + Attribute.HEIGHT)
    private double airStreamMaxEntityHeight;

    @Attribute("EntityCarry" + Attribute.DURATION)
    private double airStreamEntityCarryDuration;

    private Location origin;
    private Location currentLoc;
    private Location previousLoc;
    private Location destination;

    private Vector direction;
    private Vector previousDirection;
    private boolean movementPendingCollision;
    private boolean movementRolledBack;

    private final AirStreamVisualTrail visualTrail = new AirStreamVisualTrail();

    private ArrayList<Entity> affectedEntities;
    private ArrayList<BukkitRunnable> tasks;

    public AirStream(final Player player) {
        super(player);

        this.affectedEntities = new ArrayList<>();
        this.tasks = new ArrayList<>();

        if (!this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
            return;
        }

        if (this.bPlayer.isOnCooldown(this)) {
            return;
        }

        this.range = getConfig().getDouble("Abilities.Air.AirStream.Range");
        this.speed = getConfig().getDouble("Abilities.Air.AirStream.Speed");
        this.cooldown = getConfig().getLong("Abilities.Air.AirStream.Cooldown");
        this.maxDuration = getConfig().getLong("Abilities.Air.AirStream.MaxDuration");
        this.airStreamMaxEntityHeight = getConfig().getDouble("Abilities.Air.AirStream.EntityCarry.Height");
        this.airStreamEntityCarryDuration = getConfig().getLong("Abilities.Air.AirStream.EntityCarry.Duration");

        this.bPlayer.addCooldown(this);
        this.start();
    }

    @Override
    public String getName() {
        return "AirStream";
    }

    @Override
    public boolean isCollidable() {
        return true;
    }

    @Override
    public void handleCollision(final Collision collision) {
        if (collision.isRemovingFirst() && this.movementPendingCollision) {
            this.rollbackMovement();
        }
    }

    @Override
    public void progress() {
        if (this.player.isDead() || !this.player.isOnline()) {
            this.remove();
            return;
        }

        if (this.maxDuration > 0 && System.currentTimeMillis() - this.getStartTime() >= this.maxDuration) {

            this.remove();
            return;
        }

        if (this.currentLoc == null) {
            this.origin = this.player.getEyeLocation();
            this.currentLoc = this.origin.clone();
            this.previousLoc = this.currentLoc.clone();
        }

        if (this.player.getWorld() != this.currentLoc.getWorld()) {
            this.remove();
            return;
        }

        if (!this.player.isSneaking()) {
            this.remove();
            return;
        }

        if (GeneralMethods.isRegionProtectedFromBuild(this, this.currentLoc)) {
            this.remove();
            return;
        }

        if (!this.affectedEntities.isEmpty() && System.currentTimeMillis() - this.time >= this.airStreamEntityCarryDuration) {

            this.remove();
            return;
        }

        if (this.currentLoc.getY() - this.origin.getY() > this.airStreamMaxEntityHeight) {

            this.remove();
            return;
        }

        // Collision detection runs after progressAll(), so this renders the last
        // resolved position. A rejected movement leaves the entire trail frozen.
        this.renderStream(this.movementRolledBack);
        this.movementRolledBack = false;
        this.movementPendingCollision = false;

        this.clampToRange();

        final Entity target = GeneralMethods.getTargetedEntity(this.player, this.range);

        if (target != null && target.getLocation().distanceSquared(this.currentLoc) > 49) {

            this.destination = target.getLocation();
        } else {

            this.destination = GeneralMethods.getTargetedLocation(this.player, this.range, getTransparentMaterials());
        }

        if (this.destination != null) {

            final double distanceSquared = this.currentLoc.distanceSquared(this.destination);

            if (distanceSquared > DIRECTION_EPSILON) {

                final Vector newDirection = GeneralMethods.getDirection(this.currentLoc, this.destination);

                if (newDirection.lengthSquared() > DIRECTION_EPSILON) {

                    this.previousLoc = this.currentLoc.clone();
                    this.previousDirection = this.direction == null ? null : this.direction.clone();
                    this.direction = newDirection.clone().normalize();

                    final double distance = Math.sqrt(distanceSquared);

                    final double movementDistance = Math.min(this.speed, distance);

                    this.currentLoc.add(this.direction.clone().multiply(movementDistance));
                    this.movementPendingCollision = true;

                    if (this.isOutsideRange()) {
                        this.rollbackMovement();
                    } else if (!this.isTransparent(this.currentLoc.getBlock())) {

                        this.rollbackMovement();
                    }
                }
            }
        }

        if (GeneralMethods.isRegionProtectedFromBuild(this, this.currentLoc)) {
            this.remove();
            return;
        }

        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.currentLoc, 2.8)) {

            if (entity.equals(this.player)) {
                continue;
            }

            if (this.affectedEntities.contains(entity)) {
                continue;
            }

            if (this.affectedEntities.isEmpty()) {
                this.time = System.currentTimeMillis();
            }

            this.affectedEntities.add(entity);
        }

        for (final Entity entity : this.affectedEntities) {

            if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) {
                continue;
            }

            if (entity instanceof Player && Commands.invincible.contains(((Player) entity).getName())) {

                continue;
            }

            final Vector force = GeneralMethods.getDirection(entity.getLocation(), this.currentLoc);

            if (force.lengthSquared() > DIRECTION_EPSILON) {

                GeneralMethods.setVelocity(this, entity, force.clone().normalize().multiply(this.speed));
            }

            entity.setFallDistance(0F);
        }
    }

    private void renderStream(final boolean preserveTrail) {
        if (this.direction == null || this.direction.lengthSquared() <= DIRECTION_EPSILON) {
            return;
        }

        this.visualTrail.advance(this.currentLoc, this.direction, preserveTrail);

        for (final AirStreamVisualTrail.Frame frame : this.visualTrail.visibleFrames()) {
            final Location loc = frame.location();
            final Vector dir = frame.direction();
            for (int angle = -180; angle <= 180; angle += 45) {
                final Vector orthog = GeneralMethods.getOrthogonalVector(dir.clone(), angle, 0.5);
                playAirbendingParticles(loc.clone().add(orthog), 1, 0F, 0F, 0F);
            }
        }
    }

    private void rollbackMovement() {

        if (this.previousLoc == null) {
            return;
        }

        if (this.currentLoc == null) {
            return;
        }

        if (this.previousLoc.getWorld() != this.currentLoc.getWorld()) {
            return;
        }

        this.currentLoc = this.previousLoc.clone();
        if (this.previousDirection != null && this.previousDirection.lengthSquared() > DIRECTION_EPSILON) {
            this.direction = this.previousDirection.clone();
        }
        this.movementRolledBack = true;
        this.movementPendingCollision = false;
    }

    /**
     * Whether the stream currently exceeds the player's control range.
     */
    private boolean isOutsideRange() {

        if (this.currentLoc == null) {
            return false;
        }

        if (this.player.getWorld() != this.currentLoc.getWorld()) {
            return true;
        }

        return this.player.getLocation().distanceSquared(this.currentLoc) > this.range * this.range;
    }

    private void clampToRange() {
        if (this.currentLoc == null) {
            return;
        }

        if (this.player.getWorld() != this.currentLoc.getWorld()) {
            return;
        }

        final Location playerLocation = this.player.getLocation();

        final double distanceSquared = playerLocation.distanceSquared(this.currentLoc);

        if (distanceSquared <= this.range * this.range) {
            return;
        }

        final Vector radialDirection = GeneralMethods.getDirection(playerLocation, this.currentLoc);

        if (radialDirection.lengthSquared() <= DIRECTION_EPSILON) {
            return;
        }

        radialDirection.normalize().multiply(this.range);

        final Location clamped = playerLocation.clone().add(radialDirection);

        if (this.isTransparent(clamped.getBlock())) {
            this.currentLoc = clamped;
            this.previousLoc = clamped.clone();
        }
    }

    @Override
    public void remove() {

        super.remove();

        for (final BukkitRunnable task : new ArrayList<>(this.tasks)) {

            task.cancel();
        }

        this.tasks.clear();
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

    public void setCooldown(final long cooldown) {
        this.cooldown = cooldown;
    }

    @Override
    public Location getLocation() {
        return this.currentLoc;
    }

    @Override
    public Object createNewComboInstance(final Player player) {
        return new AirStream(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {

        return ComboUtil.generateCombinationFromList(this, ConfigManager.defaultConfig.get().getStringList("Abilities.Air.AirStream.Combination"));
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

    public Location getPreviousLoc() {
        return this.previousLoc;
    }

    public void setPreviousLoc(final Location previousLoc) {
        this.previousLoc = previousLoc;
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

    public long getTime() {
        return this.time;
    }

    public void setTime(final long time) {
        this.time = time;
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

    public long getMaxDuration() {
        return this.maxDuration;
    }

    public void setMaxDuration(final long maxDuration) {
        this.maxDuration = maxDuration;
    }

    public double getAirStreamMaxEntityHeight() {
        return this.airStreamMaxEntityHeight;
    }

    public void setAirStreamMaxEntityHeight(final double airStreamMaxEntityHeight) {
        this.airStreamMaxEntityHeight = airStreamMaxEntityHeight;
    }

    public double getAirStreamEntityCarryDuration() {
        return this.airStreamEntityCarryDuration;
    }

    public void setAirStreamEntityCarryDuration(final double airStreamEntityCarryDuration) {
        this.airStreamEntityCarryDuration = airStreamEntityCarryDuration;
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
