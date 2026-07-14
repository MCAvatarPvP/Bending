package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.FluidCollisionMode;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.RayTraceResult;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AirBurst extends AirAbility {

    private final List<BurstRay> rays = new ArrayList<>();
    private final Set<Entity> affectedEntities = new HashSet<>();
    private boolean isCharged;
    private boolean isFallBurst;
    private boolean launched;
    private int sneakParticles;
    private float playerFallDistance;
    @Attribute(Attribute.CHARGE_DURATION)
    private long chargeTime;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private double fallThreshold;
    @Attribute(Attribute.KNOCKBACK)
    private double pushFactor;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.RANGE)
    private double range;
    @Attribute(Attribute.SPEED)
    private double speed;
    @Attribute(Attribute.RADIUS)
    private double hitRadius;
    private double blastAngleTheta;
    private double blastAnglePhi;
    private int particles;
    private double waveDistance;
    private Location origin;

    public AirBurst(final Player player, final boolean isFallBurst) {
        super(player);
        if (this.bPlayer.isOnCooldown(this)) {
            return;
        }
        if (hasAbility(player, AirBurst.class) && !getAbility(player, AirBurst.class).isCharged()) {
            return;
        }

        this.isFallBurst = isFallBurst;
        this.playerFallDistance = player.getFallDistance();
        this.chargeTime = getConfig().getLong("Abilities.Air.AirBurst.ChargeTime");
        this.cooldown = getConfig().getLong("Abilities.Air.AirBurst.Cooldown");
        this.fallThreshold = getConfig().getDouble("Abilities.Air.AirBurst.FallThreshold");
        this.pushFactor = getConfig().getDouble("Abilities.Air.AirBurst.PushFactor");
        this.damage = getConfig().getDouble("Abilities.Air.AirBurst.Damage");
        this.range = getConfig().getDouble("Abilities.Air.AirBurst.Range", 16.0);
        this.speed = getConfig().getDouble("Abilities.Air.AirBurst.Speed", 1.2);
        this.hitRadius = getConfig().getDouble("Abilities.Air.AirBurst.Radius", 1.25);
        this.particles = getConfig().getInt("Abilities.Air.AirBurst.Particles", 3);
        this.blastAnglePhi = getConfig().getDouble("Abilities.Air.AirBurst.AnglePhi");
        this.blastAngleTheta = getConfig().getDouble("Abilities.Air.AirBurst.AngleTheta");
        this.sneakParticles = getConfig().getInt("Abilities.Air.AirBurst.SneakParticles");
        this.start();
    }

    public static void coneBurst(final Player player) {
        final AirBurst burst = getAbility(player, AirBurst.class);
        if (burst != null && burst.isCharged && !burst.launched) {
            burst.bPlayer.addCooldown(burst);
            burst.launch(BurstShape.CONE);
        }
    }

    @Override
    public void progress() {
        if (this.player.isDead() || !this.player.isOnline() || !this.bPlayer.canBendIgnoreCooldowns(this)) {
            this.remove();
            return;
        }
        if (this.launched) {
            this.progressWave();
            return;
        }
        if (this.isFallBurst) {
            if (this.playerFallDistance >= this.fallThreshold) {
                this.bPlayer.addCooldown(this);
                this.launch(BurstShape.FALL);
            } else {
                this.remove();
            }
            return;
        }
        if (System.currentTimeMillis() > this.getStartTime() + this.chargeTime) {
            this.isCharged = true;
        }
        if (!this.player.isSneaking()) {
            if (this.isCharged) {
                this.bPlayer.addCooldown(this);
                this.launch(BurstShape.SPHERE);
            } else {
                this.remove();
            }
        } else if (this.isCharged) {
            playAirbendingParticles(this.player.getEyeLocation(), this.sneakParticles);
        }
    }

    private void launch(final BurstShape shape) {
        this.origin = shape == BurstShape.FALL ? this.player.getLocation().clone().add(0, 0.5, 0) : this.player.getEyeLocation().clone();
        final Vector facing = this.origin.getDirection().normalize();
        final double thetaStart = shape == BurstShape.FALL ? 75 : 0;
        final double thetaEnd = shape == BurstShape.FALL ? 105 : 180;
        for (double theta = thetaStart; theta <= thetaEnd; theta += Math.max(1, this.blastAngleTheta)) {
            final double sinTheta = Math.sin(Math.toRadians(theta));
            final double phiStep = Math.max(1, this.blastAnglePhi / Math.max(0.15, sinTheta));
            for (double phi = 0; phi < 360; phi += phiStep) {
                final double rPhi = Math.toRadians(phi);
                final double rTheta = Math.toRadians(theta);
                final Vector direction = new Vector(Math.cos(rPhi) * sinTheta, Math.cos(rTheta), Math.sin(rPhi) * sinTheta).normalize();
                if (shape != BurstShape.CONE || direction.angle(facing) <= Math.toRadians(30)) {
                    this.rays.add(new BurstRay(direction, this.getWallDistance(direction)));
                }
            }
        }
        this.launched = true;
        playAirbendingSound(this.origin);
    }

    private double getWallDistance(final Vector direction) {
        final RayTraceResult hit = this.origin.getWorld().rayTraceBlocks(this.origin, direction, this.range, FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitPosition() == null ? this.range : Math.max(0, hit.getHitPosition().distance(this.origin.toVector()) - 0.05);
    }

    private void progressWave() {
        final double previousDistance = this.waveDistance;
        this.waveDistance = Math.min(this.range, this.waveDistance + Math.max(0.1, this.speed));
        int activeRays = 0;
        for (final BurstRay ray : this.rays) {
            if (ray.wallDistance + this.hitRadius < previousDistance) {
                continue;
            }
            activeRays++;
            final Location location = this.origin.clone().add(ray.direction.clone().multiply(Math.min(this.waveDistance, ray.wallDistance)));
            playAirbendingParticles(location, this.particles, 0.35, 0.35, 0.35, 0.01);
        }
        this.affectEntities(previousDistance, this.waveDistance);
        if (this.waveDistance >= this.range || activeRays == 0) {
            this.remove();
        }
    }

    private void affectEntities(final double previousDistance, final double currentDistance) {
        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.origin, currentDistance + this.hitRadius)) {
            if (entity.equals(this.player) || this.affectedEntities.contains(entity) || !(entity instanceof LivingEntity)
                    || entity instanceof Player && Commands.invincible.contains(entity.getName())) {
                continue;
            }
            final Location target = ((LivingEntity) entity).getEyeLocation();
            final Vector offset = target.toVector().subtract(this.origin.toVector());
            final double distance = offset.length();
            if (distance + this.hitRadius < previousDistance || distance - this.hitRadius > currentDistance || !this.matchesRay(offset, distance)) {
                continue;
            }
            if (this.origin.getWorld().rayTraceBlocks(this.origin, offset.clone().normalize(), distance, FluidCollisionMode.NEVER, true) != null) {
                continue;
            }
            if (this.damage > 0) {
                DamageHandler.damageEntity(entity, this.damage, this);
            }
            GeneralMethods.setVelocity(this, entity, offset.normalize().multiply(this.pushFactor));
            breakBreathbendingHold(entity);
            this.affectedEntities.add(entity);
        }
    }

    private boolean matchesRay(final Vector offset, final double distance) {
        if (distance == 0) {
            return true;
        }
        final Vector direction = offset.clone().normalize();
        final double tolerance = Math.atan2(this.hitRadius, distance) + Math.toRadians(Math.max(this.blastAnglePhi, this.blastAngleTheta) * 0.6);
        for (final BurstRay ray : this.rays) {
            if (ray.wallDistance + this.hitRadius >= distance && ray.direction.angle(direction) <= tolerance) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getName() {
        return "AirBurst";
    }

    @Override
    public Location getLocation() {
        return this.launched ? this.origin : this.player.getLocation();
    }

    @Override
    public List<Location> getLocations() {
        final List<Location> locations = new ArrayList<>();
        if (this.launched) {
            for (final BurstRay ray : this.rays) {
                if (ray.wallDistance + this.hitRadius >= this.waveDistance) {
                    locations.add(this.origin.clone().add(ray.direction.clone().multiply(Math.min(this.waveDistance, ray.wallDistance))));
                }
            }
        }
        return locations;
    }

    @Override
    public double getCollisionRadius() {
        return this.hitRadius;
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    public void addAffectedEntity(final Entity entity) {
        this.affectedEntities.add(entity);
    }

    public boolean isAffectedEntity(final Entity entity) {
        return this.affectedEntities.contains(entity);
    }

    public long getChargeTime() {
        return this.chargeTime;
    }

    public void setChargeTime(final long chargeTime) {
        this.chargeTime = chargeTime;
    }

    public double getFallThreshold() {
        return this.fallThreshold;
    }

    public void setFallThreshold(final double fallThreshold) {
        this.fallThreshold = fallThreshold;
    }

    public double getPushFactor() {
        return this.pushFactor;
    }

    public void setPushFactor(final double pushFactor) {
        this.pushFactor = pushFactor;
    }

    public double getDamage() {
        return this.damage;
    }

    public void setDamage(final double damage) {
        this.damage = damage;
    }

    public double getBlastAngleTheta() {
        return this.blastAngleTheta;
    }

    public void setBlastAngleTheta(final double theta) {
        this.blastAngleTheta = theta;
    }

    public double getBlastAnglePhi() {
        return this.blastAnglePhi;
    }

    public void setBlastAnglePhi(final double phi) {
        this.blastAnglePhi = phi;
    }

    public boolean isCharged() {
        return this.isCharged;
    }

    public void setCharged(final boolean charged) {
        this.isCharged = charged;
    }

    public boolean isFallBurst() {
        return this.isFallBurst;
    }

    public void setFallBurst(final boolean fallBurst) {
        this.isFallBurst = fallBurst;
    }

    private enum BurstShape {SPHERE, CONE, FALL}

    private static final class BurstRay {
        private final Vector direction;
        private final double wallDistance;

        private BurstRay(final Vector direction, final double wallDistance) {
            this.direction = direction;
            this.wallDistance = wallDistance;
        }
    }
}
