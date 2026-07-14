package com.projectkorra.projectkorra.firebending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class FireManipulationStream extends FireAbility {

    private long streamCooldown;
    private double streamRange;
    private double streamDamage;
    private long damageInterval;
    private long streamMaxDuration;
    private double streamSpeed, streamSideSpeed;
    private double streamRadius;
    private double streamCollisionRadius;
    private int streamParticles;
    private boolean streamSneaking = true;
    private boolean streamRangeEnabled;
    private long streamRemoveTime = 0;
    private Vector streamSneakDirection;

    private double shieldRange;
    private int shieldParticles;

    private boolean firing;
    private Map<Location, Long> points;
    private Location shotPoint;
    private Location origin;
    private Location focalPoint;
    private int damageTick;
    private long time;

    private FireManipulation parentAbility;

    public FireManipulationStream(Player player, FireManipulation parentAbility) {
        super(player);

        this.parentAbility = parentAbility;
        setFields();
        start();
    }

    public void setFields() {
        this.streamCooldown = applyModifiersCooldown(getConfig().getLong("Abilities.Fire.FireManipulation.Stream.Cooldown"));
        this.streamRange = applyModifiersRange(getConfig().getDouble("Abilities.Fire.FireManipulation.Stream.Range"));
        this.streamDamage = getConfig().getDouble("Abilities.Fire.FireManipulation.Stream.Damage");
        this.damageInterval = getConfig().getLong("Abilities.Fire.FireManipulation.Stream.DamageInterval");
        this.streamMaxDuration = getConfig().getLong("Abilities.Fire.FireManipulation.Stream.MaxDuration");
        this.streamSpeed = getConfig().getDouble("Abilities.Fire.FireManipulation.Stream.Speed");
        this.streamSideSpeed = getConfig().getDouble("Abilities.Fire.FireManipulation.Stream.SideSpeed");
        this.streamRadius = getConfig().getDouble("Abilities.Fire.FireManipulation.Stream.Radius");
        this.streamCollisionRadius = getConfig().getDouble("Abilities.Fire.FireManipulation.Stream.CollisionRadius");
        this.streamParticles = getConfig().getInt("Abilities.Fire.FireManipulation.Stream.Particles");
        this.streamRangeEnabled = getConfig().getBoolean("Abilities.Fire.FireManipulation.Stream.RangeEnabled");

        this.shieldRange = applyModifiersRange(getConfig().getDouble("Abilities.Fire.FireManipulation.Shield.Range"));
        this.shieldParticles = getConfig().getInt("Abilities.Fire.FireManipulation.Shield.Particles");

        this.focalPoint = GeneralMethods.getTargetedLocation(this.player, this.shieldRange * 2);
        this.origin = this.player.getLocation().clone();
        this.points = new ConcurrentHashMap<>();
        points.putAll(parentAbility.getPoints());
        damageTick = 0;
    }

    @Override
    public void progress() {
        if (!this.bPlayer.canBend(this)) {
            this.remove();
            return;
        }

        if (!this.firing) {
            if (!this.player.isSneaking()) {
                this.bPlayer.addCooldown(this, this.streamCooldown);
                this.remove();
                return;
            }
            boolean readyToFire = true;
            for (final Location point : this.points.keySet()) {
                if (point.distance(this.focalPoint) > 1) {
                    readyToFire = false;
                }
            }
            if (readyToFire) {
                this.shotPoint = this.focalPoint.clone();
                this.firing = true;
                time = System.currentTimeMillis();
                return;
            }
            for (final Location point : this.points.keySet()) {
                final Vector direction = this.focalPoint.toVector().subtract(point.toVector());
                point.add(direction.clone().multiply(this.streamSpeed / 5));
                playFirebendingParticles(point, this.shieldParticles, 0.25, 0.25, 0.25);
            }
        } else {
            Location dest = GeneralMethods.getTargetedLocation(this.player, this.streamRange, getTransparentMaterials());
            Vector direction = GeneralMethods.getDirection(shotPoint, dest).normalize();
            if (this.streamSneaking && !this.player.isSneaking()) {
                this.streamSneaking = false;
                this.streamRemoveTime = System.currentTimeMillis();
                this.streamSneakDirection = direction;
            }
            if (!this.streamSneaking) {
                direction = this.streamSneakDirection;
                if (System.currentTimeMillis() - this.streamRemoveTime > 1000) {
                    this.bPlayer.addCooldown(this, this.streamCooldown);
                    this.remove();
                    return;
                }
            }
            Location playerLoc = player.getLocation();
            double distance = playerLoc.distance(shotPoint);
            Location targetLoc = GeneralMethods.getTargetedLocation(player, distance, getTransparentMaterials());
            targetLoc = playerLoc.clone().add(targetLoc);
            Vector sideDir = targetLoc.toVector().subtract(shotPoint.toVector()).normalize();
            sideDir.multiply(Math.min(targetLoc.distance(shotPoint), streamSideSpeed));
            shotPoint.add(sideDir);

            this.shotPoint.add(direction.multiply(this.streamSpeed));
            if (this.streamRangeEnabled && this.shotPoint.distance(this.origin) > this.streamRange) {
                this.bPlayer.addCooldown(this, this.streamCooldown);
                this.remove();
                return;
            }
            if (this.streamMaxDuration != 0 && System.currentTimeMillis() > time + this.streamMaxDuration) {
                this.bPlayer.addCooldown(this, this.streamCooldown);
                this.remove();
                return;
            }
            if (GeneralMethods.isSolid(this.shotPoint.getBlock())) {
                this.bPlayer.addCooldown(this, this.streamCooldown);
                this.remove();
                return;
            }

            playFirebendingParticles(this.shotPoint, this.streamParticles, 0.5, 0.5, 0.5);
            if (System.currentTimeMillis() - this.getStartTime() > this.damageTick * this.damageInterval) {
                this.damageTick++;
                for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.shotPoint, streamRadius)) {
                    if (entity instanceof LivingEntity && entity.getUniqueId() != this.player.getUniqueId()) {
                        DamageHandler.damageEntity(entity, this.streamDamage, this);
                    }
                }
            }
            if (new Random().nextInt(5) == 0) {
                playFirebendingSound(this.shotPoint);
            }
        }
    }

    @Override
    public void remove() {
        super.remove();
        parentAbility.remove();
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
        return 0;
    }

    @Override
    public String getName() {
        return "FireManipulation";
    }

    @Override
    public Location getLocation() {
        return null;
    }

    @Override
    public List<Location> getLocations() {
        final List<Location> locations = new ArrayList<>();
        if (this.points != null) {
            locations.addAll(this.points.keySet());
        }
        return locations;
    }

    @Override
    public double getCollisionRadius() {
        return streamCollisionRadius;
    }
}