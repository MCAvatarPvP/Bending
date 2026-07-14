package com.projectkorra.projectkorra.airbending.combo;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.firebending.combo.FireComboStream;
import com.projectkorra.projectkorra.object.HorizontalVelocityTracker;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;

import java.util.ArrayList;
import java.util.List;

public class AirSlash extends AirAbility {

    private static final String AIR_SLASH_SOUND = "minecraft:entity.breeze.slide";

    private int progressCounter;
    private int activationDelayTicks;
    private int streamCount;
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
    @Attribute(Attribute.WIDTH)
    private double width;
    private double hitRadius;
    private double maxWidth;
    private boolean goThroughWater;
    private Location hand;
    private Location origin;
    private Location destination;
    private Vector initialDirection;
    private ArrayList<Entity> affectedEntities;
    private ArrayList<Entity> knockedEntities;
    private ArrayList<BukkitRunnable> tasks;
    private Vector knockbackDirection;

    public AirSlash(final Player player) {
        super(player);

        this.affectedEntities = new ArrayList<>();
        this.knockedEntities = new ArrayList<>();
        this.tasks = new ArrayList<>();

        if (CoreAbility.hasAbility(player, AirSlash.class)) {
            return;
        }

        if (!this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
            return;
        }

        if (this.bPlayer.isOnCooldown(this)) {
            return;
        }

        this.damage = getConfig().getDouble("Abilities.Air.AirSlash.Damage");
        this.range = getConfig().getDouble("Abilities.Air.AirSlash.Range");
        this.speed = getConfig().getDouble("Abilities.Air.AirSlash.Speed");
        this.knockback = getConfig().getDouble("Abilities.Air.AirSlash.Knockback");
        this.heightOffset = getConfig().getDouble("Abilities.Air.AirSlash.HeightOffset");
        this.width = getConfig().getDouble("Abilities.Air.AirSlash.Width");
        this.hitRadius = getConfig().getDouble("Abilities.Air.AirSlash.HitRadius");
        this.maxWidth = getConfig().getDouble("Abilities.Air.AirSlash.MaxWidth");
        this.cooldown = getConfig().getLong("Abilities.Air.AirSlash.Cooldown");
        this.activationDelayTicks = getConfig().getInt("Abilities.Air.AirSlash.ActivationDelayTicks");
        this.streamCount = Math.max(3, getConfig().getInt("Abilities.Air.AirSlash.StreamCount"));
        this.goThroughWater = getConfig().getBoolean("Abilities.Air.AirSlash.GoThroughWater");

        this.bPlayer.addCooldown(this);
        this.start();
    }

    @Override
    public String getName() {
        return "AirSlash";
    }

    @Override
    public boolean isCollidable() {
        return true;
    }

    @Override
    public void handleCollision(final Collision collision) {
        if (collision.isRemovingFirst()) {
            final ArrayList<BukkitRunnable> newTasks = new ArrayList<>();
            final double collisionDistanceSquared = Math.pow(this.getCollisionRadius() + collision.getAbilitySecond().getCollisionRadius(), 2);
            for (final BukkitRunnable task : this.getTasks()) {
                if (task instanceof FireComboStream) {
                    final FireComboStream stream = (FireComboStream) task;
                    if (stream.getLocation().distanceSquared(collision.getLocationSecond()) > collisionDistanceSquared) {
                        newTasks.add(stream);
                    } else {
                        stream.cancel();
                    }
                } else {
                    newTasks.add(task);
                }
            }
            this.setTasks(newTasks);
        }
    }

    @Override
    public List<Location> getLocations() {
        final ArrayList<Location> locations = new ArrayList<>();
        for (final BukkitRunnable task : this.getTasks()) {
            if (task instanceof FireComboStream) {
                locations.add(((FireComboStream) task).getLocation());
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
        }

        if (this.origin == null) {
            this.initialDirection = this.player.getEyeLocation().getDirection().normalize();
            this.hand = GeneralMethods.getMainHandLocation(this.player).add(0, this.heightOffset, 0);
            this.origin = this.hand.clone().add(this.initialDirection.clone().multiply(10));
        }

        if (this.progressCounter < this.activationDelayTicks) {
            return;
        }

        if (this.tasks.isEmpty()) {
            this.launchBlade();
        }

        this.manageBladeStreams();
    }

    private void launchBlade() {
        this.hand.getWorld().playSound(this.hand, AIR_SLASH_SOUND, 1.0f, 2.0f);

        final Vector finalDirection = this.player.getEyeLocation().getDirection().normalize();
        this.destination = this.hand.clone().add(finalDirection.clone().multiply(10));

        Vector blade = this.destination.toVector().subtract(this.origin.toVector());
        if (blade.lengthSquared() < 1.0E-4) {
            Vector fallbackAxis = this.initialDirection.clone().crossProduct(new Vector(0, 1, 0));
            if (fallbackAxis.lengthSquared() < 1.0E-4) {
                fallbackAxis = GeneralMethods.getOrthogonalVector(this.initialDirection.clone(), 0, 1);
            }
            blade = fallbackAxis.normalize().multiply(Math.max(0.1, Math.min(this.width, this.maxWidth)));
            this.origin.subtract(blade.clone().multiply(0.5));
            this.destination.add(blade.clone().multiply(0.5));
        } else if (blade.length() > this.maxWidth) {
            final Vector clampedBlade = blade.normalize().multiply(this.maxWidth);
            final Location midpoint = this.origin.clone().add(blade.clone().multiply(0.5));
            this.origin = midpoint.clone().subtract(clampedBlade.clone().multiply(0.5));
            this.destination = midpoint.clone().add(clampedBlade.clone().multiply(0.5));
            blade = clampedBlade;
        }

        final Location midpoint = this.origin.clone().add(this.destination.toVector().subtract(this.origin.toVector()).multiply(0.5));
        this.knockbackDirection = GeneralMethods.getDirection(this.hand, midpoint);
        if (this.knockbackDirection.lengthSquared() < 1.0E-4) {
            this.knockbackDirection = finalDirection.clone();
        } else {
            this.knockbackDirection.normalize();
        }

        final Vector originToDestination = GeneralMethods.getDirection(this.origin, this.destination);

        for (int i = 0; i < this.streamCount; i++) {
            final double percent = this.streamCount <= 1 ? 0 : (double) i / (this.streamCount - 1);
            final Location endLoc = this.origin.clone().add(originToDestination.clone().multiply(percent));
            if (GeneralMethods.locationEqualsIgnoreDirection(this.hand, endLoc)) {
                continue;
            }
            final Vector streamDirection = GeneralMethods.getDirection(this.hand, endLoc);

            final FireComboStream stream = new FireComboStream(this.player, this, streamDirection, this.hand.clone(), this.range, this.speed);
            stream.setDensity(1);
            stream.setSpread(0F);
            stream.setSubLocations(2);
            stream.setUseNewParticles(true);
            stream.setGoThroughWater(this.goThroughWater);
            stream.setParticleEffect(getAirbendingParticles());
            stream.setCollides(false);
            stream.runTaskTimer(ProjectKorra.plugin, 0L, 1L);
            this.tasks.add(stream);
        }
    }

    private void manageBladeStreams() {
        for (int i = 0; i < this.tasks.size(); i++) {
            if (((FireComboStream) this.tasks.get(i)).isCancelled()) {
                this.tasks.remove(i);
                i--;
            }
        }

        if (this.tasks.isEmpty()) {
            this.remove();
            return;
        }

        for (int i = 0; i < this.tasks.size(); i++) {
            final FireComboStream stream = (FireComboStream) this.tasks.get(i);
            final Location loc = stream.getLocation();

            if (GeneralMethods.isRegionProtectedFromBuild(this, loc)) {
                stream.remove();
                continue;
            }

            if (!this.isTransparent(loc.getBlock()) && !this.isTransparent(loc.clone().add(0, 0.2, 0).getBlock())) {
                stream.remove();
                continue;
            }

            if (i % 2 != 0) {
                continue;
            }

            for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(loc, this.hitRadius)) {
                if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) {
                    continue;
                }
                if (entity.equals(this.player) || entity instanceof Player && Commands.invincible.contains(((Player) entity).getName())) {
                    continue;
                }

                if (this.knockback != 0 && !this.knockedEntities.contains(entity)) {
                    GeneralMethods.setVelocity(this, entity, this.knockbackDirection.clone().multiply(this.knockback));
                    new HorizontalVelocityTracker(entity, this.player, 200L, this);
                    entity.setFallDistance(0);
                    this.knockedEntities.add(entity);
                }

                if (this.affectedEntities.contains(entity)) {
                    continue;
                }

                this.affectedEntities.add(entity);
                if (this.damage != 0 && entity instanceof LivingEntity) {
                    DamageHandler.damageEntity(entity, this.damage, this);
                }
            }
        }
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

    public ArrayList<BukkitRunnable> getTasks() {
        return this.tasks;
    }

    public void setTasks(final ArrayList<BukkitRunnable> tasks) {
        this.tasks = tasks;
    }
}
