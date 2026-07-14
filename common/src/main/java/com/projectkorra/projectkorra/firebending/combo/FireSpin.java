package com.projectkorra.projectkorra.firebending.combo;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.ability.util.ComboUtil;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.attribute.markers.DayNightFactor;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.firebending.FireJet;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;

import java.util.ArrayList;
import java.util.List;

public class FireSpin extends FireAbility implements ComboAbility {
    private static final int STREAM_ANGLE_STEP = 5;

    @Attribute(Attribute.COOLDOWN)
    @DayNightFactor(invert = true)
    private long cooldown;
    @Attribute(Attribute.DAMAGE)
    @DayNightFactor
    private double damage;
    @Attribute(Attribute.SPEED)
    @DayNightFactor
    private double speed;
    @Attribute(Attribute.RANGE)
    @DayNightFactor
    private double range;
    @Attribute(Attribute.KNOCKBACK)
    @DayNightFactor
    private double knockback;
    @Attribute(Attribute.HEIGHT)
    @DayNightFactor
    private double height;
    private double radius;
    private double collisionRadius;
    private Location origin;
    private Location destination;
    private ArrayList<LivingEntity> affectedEntities;
    private ArrayList<BukkitRunnable> tasks;

    public FireSpin(final Player player) {
        super(player);

        if (!this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
            return;
        }

        if (player.getLocation().getBlock().getType() == Material.WATER && !canPassThroughWater(player.getLocation().getBlock())) {
            return;
        }

        this.affectedEntities = new ArrayList<>();
        this.tasks = new ArrayList<>();

        this.damage = getConfig().getDouble("Abilities.Fire.FireSpin.Damage");
        this.range = getConfig().getDouble("Abilities.Fire.FireSpin.Range");
        this.cooldown = getConfig().getLong("Abilities.Fire.FireSpin.Cooldown");
        this.knockback = getConfig().getDouble("Abilities.Fire.FireSpin.Knockback");
        this.height = getConfig().getDouble("Abilities.Fire.FireSpin.Height");
        this.radius = getConfig().getDouble("Abilities.Fire.FireSpin.Radius");
        this.collisionRadius = getConfig().getDouble("Abilities.Fire.FireSpin.CollisionRadius");
        this.speed = getConfig().getDouble("Abilities.Fire.FireSpin.Speed");

        this.start();
    }

    private static double distanceToInterval(final double value, final double minimum, final double maximum) {
        if (value < minimum) {
            return minimum - value;
        }
        return value > maximum ? value - maximum : 0;
    }

    @Override
    public void progress() {
        for (int i = 0; i < this.tasks.size(); i++) {
            final BukkitRunnable br = this.tasks.get(i);
            if (br instanceof FireComboStream) {
                final FireComboStream fs = (FireComboStream) br;
                if (fs.isCancelled()) {
                    this.tasks.remove(fs);
                    i--;
                }
            }
        }

        if (!this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
            this.remove();
            return;
        }

        if (this.destination == null) {
            if (this.bPlayer.isOnCooldown("FireSpin")) {
                this.remove();
                return;
            }
            this.bPlayer.addCooldown("FireSpin", this.cooldown);
            this.origin = this.player.getLocation().clone().add(0, 1, 0);
            this.destination = this.player.getEyeLocation().add(this.range, 0, this.range);
            this.player.getWorld().playSound(this.player.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 0.5f, 0.5f);

            for (int i = 0; i <= 360; i += STREAM_ANGLE_STEP) {
                Vector vec = GeneralMethods.getDirection(this.player.getLocation(), this.destination.clone());
                vec = GeneralMethods.rotateXZ(vec, i - 180);
                vec.setY(0);

                final FireComboStream fs = new FireComboStream(this.player, this, vec, this.origin.clone(), this.range, this.speed);
                fs.setSpread(0.1F);
                fs.setDensity(1);
                fs.setUseNewParticles(true);
                fs.setCollisionRadius(this.collisionRadius);
                fs.setDamage(this.damage);
                fs.setKnockback(this.knockback);
                fs.setCollides(false);
                fs.runTaskTimer(ProjectKorra.plugin, 0, 1L);
                this.tasks.add(fs);
            }
        }

        this.applyDynamicCollisions();

        if (this.tasks.size() == 0) {
            this.remove();
            return;
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
    public void handleCollision(final Collision collision) {
        if (collision.isRemovingFirst()) {
            final ArrayList<BukkitRunnable> newTasks = new ArrayList<>();
            final double collisionDistanceSquared = Math.pow(this.getCollisionRadius() + collision.getAbilitySecond().getCollisionRadius(), 2);
            // Remove all of the streams that are by this specific ourLocation.
            // Don't just do a single stream at a time or this algorithm becomes O(n^2) with Collision's detection algorithm.
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
                final FireComboStream stream = (FireComboStream) task;
                locations.add(stream.getLocation());
            }
        }
        return locations;
    }

    @Override
    public Object createNewComboInstance(final Player player) {
        return new FireSpin(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        return ComboUtil.generateCombinationFromList(this, ConfigManager.defaultConfig.get().getStringList("Abilities.Fire.FireSpin.Combination"));
    }

    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    @Override
    public String getName() {
        return "FireSpin";
    }

    @Override
    public Location getLocation() {
        return this.origin != null ? this.origin.clone() : this.player.getLocation();
    }

    @Override
    public double getCollisionRadius() {
        return collisionRadius;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    public ArrayList<LivingEntity> getAffectedEntities() {
        return this.affectedEntities;
    }

    public ArrayList<BukkitRunnable> getTasks() {
        return this.tasks;
    }

    public void setTasks(final ArrayList<BukkitRunnable> tasks) {
        this.tasks = tasks;
    }

    public void affectEntity(final LivingEntity entity, final Vector direction) {
        if (entity.equals(this.player) || entity.isDead() || this.affectedEntities.contains(entity) || RegionProtection.isRegionProtected(this, entity.getLocation())) {
            return;
        }

        if (entity instanceof Player && Commands.invincible.contains(((Player) entity).getName())) {
            return;
        }

        this.affectedEntities.add(entity);

        if (entity instanceof Player) {
            final FireJet fireJet = getAbility((Player) entity, FireJet.class);
            if (fireJet != null) {
                fireJet.remove();
            }
        }

        DamageHandler.damageEntity(entity, this.damage, this);

        final Vector knockbackDirection = direction.clone().setY(0);
        if (knockbackDirection.lengthSquared() > 0) {
            final double newKnockback = this.bPlayer.isAvatarState() ? this.knockback + 0.5 : this.knockback;
            GeneralMethods.setVelocity(this, entity, knockbackDirection.normalize().multiply(newKnockback));
        }
    }

    private void applyDynamicCollisions() {
        if (this.origin == null) {
            return;
        }

        final ArrayList<FireComboStream> activeStreams = new ArrayList<>(this.tasks.size());
        double furthestStreamDistanceSquared = 0;
        for (final BukkitRunnable task : this.tasks) {
            if (task instanceof FireComboStream) {
                final FireComboStream stream = (FireComboStream) task;
                if (!stream.isCancelled()) {
                    activeStreams.add(stream);
                    furthestStreamDistanceSquared = Math.max(furthestStreamDistanceSquared, this.origin.distanceSquared(stream.getLocation()));
                }
            }
        }

        if (activeStreams.isEmpty()) {
            return;
        }

        final double halfHeight = this.height / 2.0;
        final double searchRadius = Math.sqrt(furthestStreamDistanceSquared) + Math.max(this.collisionRadius, halfHeight);
        final double collisionRadiusSquared = this.collisionRadius * this.collisionRadius;

        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.origin, searchRadius)) {
            if (!(entity instanceof LivingEntity) || entity.equals(this.player) || entity.isDead() || this.affectedEntities.contains(entity)) {
                continue;
            }

            final LivingEntity livingEntity = (LivingEntity) entity;
            final BoundingBox bounds = livingEntity.getBoundingBox();
            if (bounds.getMaxY() < this.origin.getY() - halfHeight || bounds.getMinY() > this.origin.getY() + halfHeight) {
                continue;
            }

            for (final FireComboStream stream : activeStreams) {
                final Location streamLocation = stream.getLocation();
                final double deltaX = distanceToInterval(streamLocation.getX(), bounds.getMinX(), bounds.getMaxX());
                final double deltaZ = distanceToInterval(streamLocation.getZ(), bounds.getMinZ(), bounds.getMaxZ());
                if (deltaX * deltaX + deltaZ * deltaZ > collisionRadiusSquared) {
                    continue;
                }

                final Vector direction = stream.getDirection().setY(0);
                final double streamDistance = GeneralMethods.getHorizontalDistance(this.origin, streamLocation);
                if (!this.isBlockedByWall(direction, streamDistance)) {
                    this.affectEntity(livingEntity, direction);
                    break;
                }
            }
        }
    }

    private boolean isBlockedByWall(final Vector horizontalDirection, final double horizontalDistance) {
        if (horizontalDirection.lengthSquared() == 0) {
            return false;
        }

        final Vector direction = horizontalDirection.clone().normalize();
        final double checkDistance = Math.max(0, horizontalDistance);
        final Location checkLocation = this.origin.clone();
        final double stepDistance = 0.25;
        final Vector step = direction.clone().multiply(stepDistance);
        for (double distance = stepDistance; distance <= checkDistance; distance += stepDistance) {
            checkLocation.add(step);
            if (GeneralMethods.checkDiagonalWall(checkLocation, direction)) {
                return true;
            }

            final Block block = checkLocation.getBlock();
            if (!this.isTransparent(block) && !canPassThroughWater(block)) {
                return true;
            }
        }

        return false;
    }
}
