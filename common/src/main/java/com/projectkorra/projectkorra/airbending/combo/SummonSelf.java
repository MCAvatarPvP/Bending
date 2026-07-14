package com.projectkorra.projectkorra.airbending.combo;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.ability.util.ComboUtil;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.object.HorizontalVelocityTracker;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.ArrayList;

/**
 * Launches an air-particle outline of the caster. Its speed, recoil, and impact
 * knockback scale with the caster's velocity when the combo is completed.
 */
public class SummonSelf extends AirAbility implements ComboAbility {
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.SPEED)
    private double minimumSpeed;
    @Attribute(Attribute.SPEED)
    private double maximumSpeed;
    @Attribute(Attribute.RANGE)
    private double range;
    @Attribute(Attribute.KNOCKBACK)
    private double minimumCasterKnockback;
    @Attribute(Attribute.KNOCKBACK)
    private double casterKnockbackFactor;
    @Attribute(Attribute.KNOCKBACK)
    private double minimumOpponentKnockback;
    @Attribute(Attribute.KNOCKBACK)
    private double opponentKnockbackFactor;
    private double movementSpeedFactor;
    private double collisionRadius;
    private double gravity;
    private double impactRingRadius;
    private double impactRingStep;
    private Location location;
    private Location origin;
    private Vector velocity;
    private double projectileSpeed;

    public SummonSelf(final Player player) {
        super(player);

        if (hasAbility(player, SummonSelf.class) || !this.bPlayer.canBendIgnoreBindsCooldowns(this) || this.bPlayer.isOnCooldown(this)) {
            return;
        }

        final String path = "Abilities.Air.SummonSelf.";
        this.cooldown = getConfig().getLong(path + "Cooldown");
        this.minimumSpeed = getConfig().getDouble(path + "MinimumSpeed");
        this.maximumSpeed = Math.max(this.minimumSpeed, getConfig().getDouble(path + "MaximumSpeed"));
        this.movementSpeedFactor = getConfig().getDouble(path + "MovementSpeedFactor");
        this.range = getConfig().getDouble(path + "Range");
        this.collisionRadius = getConfig().getDouble(path + "CollisionRadius");
        this.gravity = getConfig().getDouble(path + "Gravity");
        this.minimumCasterKnockback = getConfig().getDouble(path + "CasterKnockback.Minimum");
        this.casterKnockbackFactor = getConfig().getDouble(path + "CasterKnockback.SpeedFactor");
        this.minimumOpponentKnockback = getConfig().getDouble(path + "OpponentKnockback.Minimum");
        this.opponentKnockbackFactor = getConfig().getDouble(path + "OpponentKnockback.SpeedFactor");
        this.impactRingRadius = getConfig().getDouble(path + "Impact.RingRadius");
        this.impactRingStep = Math.max(1, getConfig().getDouble(path + "Impact.RingStep"));

        final double casterSpeed = this.player.getVelocity().length();
        this.projectileSpeed = Math.min(this.maximumSpeed, Math.max(this.minimumSpeed,
                this.minimumSpeed + casterSpeed * this.movementSpeedFactor));
        this.velocity = this.player.getEyeLocation().getDirection().normalize().multiply(this.projectileSpeed);
        this.origin = this.player.getEyeLocation().add(this.velocity.clone().normalize().multiply(1.25));
        this.location = this.origin.clone();

        this.applyCasterRecoil();
        this.bPlayer.addCooldown(this);
        this.start();
    }

    private void applyCasterRecoil() {
        final double strength = Math.max(this.minimumCasterKnockback, this.projectileSpeed * this.casterKnockbackFactor);
        final Vector recoil = this.velocity.clone().normalize().multiply(-strength);
        GeneralMethods.setVelocity(this, this.player, recoil);
        this.player.setFallDistance(0);
    }

    @Override
    public void progress() {
        if (this.location.getWorld() != this.player.getWorld()) {
            this.remove();
            return;
        }

        final int subSteps = Math.max(1, (int) Math.ceil(this.velocity.length() / 0.35));
        final Vector step = this.velocity.clone().multiply(1.0 / subSteps);
        for (int i = 0; i < subSteps; i++) {
            this.location.add(step);
            if (this.hasHitEntity()) {
                this.impact();
                return;
            }
            if (this.hasHitBlock() || this.origin.distanceSquared(this.location) >= this.range * this.range) {
                this.remove();
                return;
            }
        }

        this.velocity.setY(this.velocity.getY() - this.gravity);
        this.location.setDirection(this.velocity);
        this.renderPlayerOutline();
    }

    private boolean hasHitEntity() {
        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.location, this.collisionRadius)) {
            if (!(entity instanceof LivingEntity) || entity.equals(this.player) || !entity.isValid()) {
                continue;
            }
            if (entity instanceof Player && Commands.invincible.contains(entity.getName())) {
                continue;
            }
            if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) {
                continue;
            }

            final double strength = Math.max(this.minimumOpponentKnockback, this.projectileSpeed * this.opponentKnockbackFactor);
            final Vector knockback = this.velocity.clone().normalize().multiply(strength);
            GeneralMethods.setVelocity(this, entity, knockback);
            new HorizontalVelocityTracker(entity, this.player, 200L, this);
            entity.setFallDistance(0);
            return true;
        }
        return false;
    }

    private boolean hasHitBlock() {
        return !this.isTransparent(this.location.getBlock()) || GeneralMethods.isRegionProtectedFromBuild(this, this.location);
    }

    private void renderPlayerOutline() {
        final Vector up = new Vector(0, 1, 0);
        Vector facing = this.velocity.clone().setY(0);
        if (facing.lengthSquared() < 0.001) {
            facing = this.player.getLocation().getDirection().setY(0);
        }
        if (facing.lengthSquared() < 0.001) {
            facing = new Vector(0, 0, 1);
        }
        facing.normalize();
        final Vector right = facing.clone().crossProduct(up).normalize();
        final Location center = this.location.clone();

        // Upright head and torso, yawed toward the projectile's travel direction.
        final Location head = center.clone().add(up.clone().multiply(0.85));
        this.renderOutlineRing(head, right, up, 0.28, 8);
        final Location leftShoulder = center.clone().add(up.clone().multiply(0.42)).subtract(right.clone().multiply(0.3));
        final Location rightShoulder = center.clone().add(up.clone().multiply(0.42)).add(right.clone().multiply(0.3));
        final Location leftHip = center.clone().subtract(up.clone().multiply(0.32)).subtract(right.clone().multiply(0.22));
        final Location rightHip = center.clone().subtract(up.clone().multiply(0.32)).add(right.clone().multiply(0.22));
        this.renderOutlineSegment(leftShoulder, leftHip, 5);
        this.renderOutlineSegment(rightShoulder, rightHip, 5);
        this.renderOutlineSegment(leftShoulder, leftShoulder.clone().subtract(up.clone().multiply(0.75)).subtract(right.clone().multiply(0.08)), 5);
        this.renderOutlineSegment(rightShoulder, rightShoulder.clone().subtract(up.clone().multiply(0.75)).add(right.clone().multiply(0.08)), 5);
        this.renderOutlineSegment(leftHip, leftHip.clone().subtract(up.clone().multiply(0.85)), 5);
        this.renderOutlineSegment(rightHip, rightHip.clone().subtract(up.clone().multiply(0.85)), 5);
    }

    private void renderOutlineRing(final Location center, final Vector horizontal, final Vector vertical,
                                   final double radius, final int points) {
        for (int i = 0; i < points; i++) {
            final double angle = Math.PI * 2 * i / points;
            final Vector offset = horizontal.clone().multiply(Math.cos(angle) * radius)
                    .add(vertical.clone().multiply(Math.sin(angle) * radius));
            this.playAirbendingParticles(center.clone().add(offset), 1, 0, 0, 0, 0);
        }
    }

    private void renderOutlineSegment(final Location start, final Location end, final int points) {
        final Vector segment = end.toVector().subtract(start.toVector());
        for (int i = 0; i < points; i++) {
            final Location point = start.clone().add(segment.clone().multiply(i / (points - 1.0)));
            this.playAirbendingParticles(point, 1, 0, 0, 0, 0);
        }
    }

    private void impact() {
        final Location center = this.location.clone().add(0, 0.4, 0);
        this.animateDirectionalImpact(center, this.velocity);
        this.remove();
    }

    private void animateDirectionalImpact(final Location origin, final Vector direction) {
        final Vector forward = direction.clone().normalize();
        Vector right = forward.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 0.001) {
            right = forward.clone().crossProduct(new Vector(1, 0, 0));
        }
        right.normalize();
        final Vector up = right.clone().crossProduct(forward).normalize();
        final Vector ringRight = right;

        new BukkitRunnable() {
            private int frame;

            @Override
            public void run() {
                if (this.frame >= 3 || origin.getWorld() == null) {
                    this.cancel();
                    return;
                }

                final double radius = impactRingRadius * 0.15 * this.frame;
                final double particleSpeed = 0.22 + this.frame * 0.04;
                final Location ringCenter = origin.clone().add(forward.clone().multiply(this.frame * 0.18));
                final double angleStep = Math.max(30.0, impactRingStep);
                for (double angle = 0; angle < 360; angle += angleStep) {
                    final double radians = Math.toRadians(angle + this.frame * 15.0);
                    final Vector radial = ringRight.clone().multiply(Math.cos(radians))
                            .add(up.clone().multiply(Math.sin(radians))).normalize();
                    final Location spawn = ringCenter.clone().add(radial.clone().multiply(radius));
                    // A zero count makes Bukkit interpret XYZ as particle direction rather than random spread.
                    playAirbendingParticles(spawn, 0, radial.getX(), radial.getY(), radial.getZ(), particleSpeed);
                }
                this.frame++;
            }
        }.runTaskTimer(ProjectKorra.plugin, 0L, 1L);
    }

    @Override
    public void remove() {
        super.remove();
    }

    @Override
    public String getName() {
        return "SummonSelf";
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
    public boolean isCollidable() {
        return true;
    }

    @Override
    public double getCollisionRadius() {
        return this.collisionRadius;
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    @Override
    public Location getLocation() {
        return this.location;
    }

    @Override
    public Object createNewComboInstance(final Player player) {
        return new SummonSelf(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        return ComboUtil.generateCombinationFromList(this,
                ConfigManager.defaultConfig.get().getStringList("Abilities.Air.SummonSelf.Combination"));
    }
}
