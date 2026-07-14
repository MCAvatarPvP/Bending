package com.jedk1.jedcore.ability.airbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.collision.CollisionDetector;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.colliders.Sphere;

import java.util.Collections;
import java.util.List;

public class AirPunch extends AirAbility implements AddonAbility {

    private Location location;
    private Vector direction;
    private double travelled;
    private double projectileSpeed;

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.RANGE)
    private double range;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute("CollisionRadius")
    private double entityCollisionRadius;
    @Attribute("MinProjectileSpeed")
    private double minProjectileSpeed;
    @Attribute("MaxProjectileSpeed")
    private double maxProjectileSpeed;
    @Attribute("MovementSpeedMultiplier")
    private double movementSpeedMultiplier;
    @Attribute("Velocity")
    private boolean applyVelocity;
    @Attribute("VelocityMultiplier")
    private double velocityMultiplier;

    public AirPunch(Player player) {
        super(player);

        if (!bPlayer.canBend(this)) {
            return;
        }

        if (hasAbility(player, AirPunch.class)) {
            return;
        }

        setFields();

        start();
        if (!isRemoved()) {
            createShot();
        }
    }

    public void setFields() {
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Air.AirPunch.Cooldown");
        range = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.AirPunch.Range");
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.AirPunch.Damage");
        entityCollisionRadius = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.AirPunch.EntityCollisionRadius");
        minProjectileSpeed = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.AirPunch.MinProjectileSpeed");
        maxProjectileSpeed = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.AirPunch.MaxProjectileSpeed");
        movementSpeedMultiplier = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.AirPunch.MovementSpeedMultiplier");
        applyVelocity = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Air.AirPunch.ApplyVelocity");
        velocityMultiplier = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.AirPunch.VelocityMultiplier");
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (!bPlayer.canBendIgnoreBindsCooldowns(this)) {
            prepareRemove();
            return;
        }

        if (location == null || travelled >= range) {
            prepareRemove();
            return;
        }

        progressShot();
    }

    private void prepareRemove() {
        if (player.isOnline() && !bPlayer.isOnCooldown(this)) {
            bPlayer.addCooldown(this);
        }

        remove();
    }

    private void createShot() {
        this.direction = player.getEyeLocation().getDirection().clone().normalize();
        this.location = player.getEyeLocation().clone().add(direction.clone().multiply(1.5));
        this.projectileSpeed = calculateProjectileSpeed();
    }

    private double calculateProjectileSpeed() {
        double movementSpeed = player.getVelocity().clone().setY(0).length();
        return Math.max(minProjectileSpeed, Math.min(maxProjectileSpeed, movementSpeed * movementSpeedMultiplier));
    }

    private void progressShot() {
        double remaining = projectileSpeed;

        while (remaining > 0) {
            double step = Math.min(1.0, remaining);
            travelled += step;

            if (travelled >= range) {
                prepareRemove();
                return;
            }

            location = location.add(direction.clone().multiply(step));
            if (GeneralMethods.isSolid(location.getBlock()) || isWater(location.getBlock()) || RegionProtection.isRegionProtected(player, location, this)) {
                prepareRemove();
                return;
            }

            displayCompressedAir(location);
            playAirbendingSound(location);

            boolean hit = CollisionDetector.checkEntityCollisions(player, new Sphere(location, entityCollisionRadius), (entity) -> {
                if (applyVelocity) {
                    GeneralMethods.setVelocity(this, entity, direction.clone().multiply(projectileSpeed * velocityMultiplier));
                }
                DamageHandler.damageEntity(entity, damage, this);
                return true;
            });

            if (hit) {
                prepareRemove();
                return;
            }

            remaining -= step;
        }
    }

    private void displayCompressedAir(Location center) {
        playAirbendingParticles(center, 3, 0.03, 0.03, 0.03, 0.0);

        Vector axis = direction.clone().normalize();
        Vector reference = Math.abs(axis.getY()) > 0.9 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector right = axis.clone().crossProduct(reference).normalize();
        Vector up = right.clone().crossProduct(axis).normalize();
        double radius = Math.max(0.1, entityCollisionRadius);

        for (int angle = 0; angle < 360; angle += 20) {
            double radians = Math.toRadians(angle);
            Vector ringOffset = right.clone().multiply(Math.cos(radians) * radius)
                    .add(up.clone().multiply(Math.sin(radians) * radius));
            playAirbendingParticles(center.clone().add(ringOffset), 1, 0.01, 0.01, 0.01, 0.0);
        }
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public double getCollisionRadius() {
        return JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.AirPunch.AbilityCollisionRadius");
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public void handleCollision(Collision collision) {
        if (collision.isRemovingFirst()) {
            prepareRemove();
        }
    }

    @Override
    public List<Location> getLocations() {
        if (location == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(location);
    }

    @Override
    public String getName() {
        return "AirPunch";
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public String getAuthor() {
        return JedCore.dev;
    }

    @Override
    public String getVersion() {
        return JedCore.version;
    }

    @Override
    public String getDescription() {
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Air.AirPunch.Description");
    }

    public double getRange() {
        return range;
    }

    public void setRange(double range) {
        this.range = range;
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    public double getEntityCollisionRadius() {
        return entityCollisionRadius;
    }

    public void setEntityCollisionRadius(double entityCollisionRadius) {
        this.entityCollisionRadius = entityCollisionRadius;
    }

    public double getProjectileSpeed() {
        return projectileSpeed;
    }

    public void setProjectileSpeed(double projectileSpeed) {
        this.projectileSpeed = projectileSpeed;
    }

    public double getTravelled() {
        return travelled;
    }

    public void setTravelled(double travelled) {
        this.travelled = travelled;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Air.AirPunch.Enabled");
    }
}
