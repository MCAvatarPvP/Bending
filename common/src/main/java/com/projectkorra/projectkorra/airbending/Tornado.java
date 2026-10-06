package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.GameMode;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.Display;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.AbilityLagCompensator;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.colliders.AABB;

import java.util.*;

public class Tornado extends AirAbility {

    private static final String RIDE_FLIGHT_ID = "TornadoRide";
    private static final long CHARGE_SOUND_INTERVAL = 200L;
    private static final String[] TRAPPED_PLAYER_ABILITIES = {"AirScooter", "AirSpout"};
    private final Map<UUID, Long> lastDamageTimes;
    private final Map<UUID, Long> pullStartTimes;
    private final Map<UUID, Long> lastRestrictedTimes;
    private final Map<UUID, Entity> caughtEntities;
    private final Set<UUID> exhaustedPullEntities;
    private final Set<UUID> pulledEntitiesThisTick;
    private final AbilityLagCompensator lagCompensator;
    private TornadoChargeProfile chargeProfile;
    private TornadoVisuals.Settings visualSettings;
    private TornadoDebris debris;
    private double controlDistance, controlSpeed, controlAcceleration, controlDrag, aimSmoothing;
    private boolean launched, soundEnabled;
    private long soundInterval;
    private float soundVolume, soundPitch;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.CHARGE_DURATION)
    private long chargeTime;
    private long damageInterval;
    private long maxPullDuration;
    private long minimumPullDuration;
    private Player capturedPlayer;
    private long capturedAt;
    private long trappedAbilityCooldown;
    private long time;
    private long lastSoundTime;
    private long rideDuration;
    private long rideStartTime;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute("PullZone" + Attribute.RADIUS)
    private double pullZoneRadius;
    @Attribute("Pull" + Attribute.SPEED)
    private double pullVelocity;
    @Attribute(Attribute.SPEED)
    private double speed;
    @Attribute(Attribute.RANGE)
    private double range;
    @Attribute(Attribute.HEIGHT)
    private double tornadoHeight;
    @Attribute(Attribute.RADIUS)
    private double tornadoRadius;
    private double tornadoRemoveDelay;
    @Attribute("Ride" + Attribute.SPEED)
    private double rideSpeed;
    private double rideHeightPercentage;
    private double rideVerticalSmoothing;
    private double rideMaxVerticalSpeed;
    private double rideTargetingRange;
    private double chargeAngle;
    private double vortexAngle;
    private long chargedDuration;
    private long lastChargeUpdateTime;
    private int lastKnownNoDamageTicks;
    private boolean spinPlayers;
    private boolean rideEnabled;
    private boolean riding;
    private AbilityState state;
    private Location origin;
    private Location currentLoc;
    private Vector direction;
    private Vector motion;
    private Vector velocity;
    private double distanceTravelled;

    public Tornado(final Player player) {
        super(player);

        this.lastDamageTimes = new HashMap<>();
        this.pullStartTimes = new HashMap<>();
        this.lastRestrictedTimes = new HashMap<>();
        this.caughtEntities = new HashMap<>();
        this.exhaustedPullEntities = new HashSet<>();
        this.pulledEntitiesThisTick = new HashSet<>();
        this.lagCompensator = new AbilityLagCompensator((p, snapshot) ->
                this.pullEntity(p, snapshot.getLocation().clone().subtract(0, this.tornadoHeight / 2.0, 0)));

        if (player == null || this.bPlayer == null || CoreAbility.hasAbility(player, Tornado.class) || !this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
            return;
        }

        if (this.bPlayer.isOnCooldown(this)) {
            return;
        }

        if (CoreAbility.hasAbility(player, AirSpout.class)) {
            player.sendMessage(ChatColor.RED + "You can't use Tornado while using AirSpout.");
            return;
        }

        this.range = finite(getConfig().getDouble("Abilities.Air.Tornado.Range", 32), 1, 128, 32);
        this.speed = finite(getConfig().getDouble("Abilities.Air.Tornado.Speed", 0.35), 0.05, 2, 0.35);
        this.cooldown = getConfig().getLong("Abilities.Air.Tornado.Cooldown", 18000);
        this.chargeTime = getConfig().getLong("Abilities.Air.Tornado.ChargeTime", 6000L);
        this.damage = getConfig().getDouble("Abilities.Air.Tornado.Damage", 0);
        this.damageInterval = getConfig().getLong("Abilities.Air.Tornado.DamageInterval", 500L);
        this.maxPullDuration = getConfig().getLong("Abilities.Air.Tornado.MaxPullDuration", 2000L);
        this.minimumPullDuration = getConfig().getLong("Abilities.Air.Tornado.MinimumPullDuration", 500L);
        this.trappedAbilityCooldown = getConfig().getLong("Abilities.Air.Tornado.TrappedAbilityCooldown", 1500L);
        this.tornadoHeight = getConfig().getDouble("Abilities.Air.Tornado.Height", 18);
        this.tornadoRadius = getConfig().getDouble("Abilities.Air.Tornado.Radius", 7);
        this.pullZoneRadius = getConfig().getDouble("Abilities.Air.Tornado.PullZoneRadius", this.tornadoRadius + 1.75);
        this.pullVelocity = getConfig().getDouble("Abilities.Air.Tornado.PullVelocity", Math.max(0.1, this.speed * 0.9));
        this.tornadoRemoveDelay = Math.max(1, getConfig().getLong("Abilities.Air.Tornado.RemoveDelay", 12000));
        this.spinPlayers = getConfig().getBoolean("Abilities.Air.Tornado.SpinPlayers", false);
        this.rideEnabled = getConfig().getBoolean("Abilities.Air.Tornado.Ride.Enabled", true);
        this.rideDuration = getConfig().getLong("Abilities.Air.Tornado.Ride.Duration", 8000L);
        this.rideSpeed = finite(getConfig().getDouble("Abilities.Air.Tornado.Ride.Speed", 0.22), 0.01, 2, 0.22);
        this.rideHeightPercentage = GeneralMethods.clamp(
                getConfig().getDouble("Abilities.Air.Tornado.Ride.HeightPercentage", 0.62), 0.2, 0.9);
        this.rideVerticalSmoothing = Math.max(0.01,
                getConfig().getDouble("Abilities.Air.Tornado.Ride.VerticalSmoothing", 0.16));
        this.rideMaxVerticalSpeed = Math.max(0.1,
                getConfig().getDouble("Abilities.Air.Tornado.Ride.MaxVerticalSpeed", 0.55));
        this.rideTargetingRange = Math.max(2.0,
                getConfig().getDouble("Abilities.Air.Tornado.Ride.TargetingRange", 12.0));
        this.state = AbilityState.CHARGING;
        this.chargeAngle = 0;
        this.vortexAngle = 0;
        this.chargedDuration = 0L;
        this.lastChargeUpdateTime = 0L;
        this.lastKnownNoDamageTicks = this.player.getNoDamageTicks();
        this.lastSoundTime = 0L;
        this.motion = new Vector(0, 0, 0);
        this.velocity = new Vector(0, 0, 0);
        this.distanceTravelled = 0;
        this.riding = false;
        this.rideStartTime = 0L;

        this.controlDistance = finite(getConfig().getDouble("Abilities.Air.Tornado.Control.CenterDistance", 6), 1, 32, 6);
        this.controlSpeed = finite(getConfig().getDouble("Abilities.Air.Tornado.Control.Speed", 0.16), 0.01, 2, 0.16);
        this.controlAcceleration = finite(getConfig().getDouble("Abilities.Air.Tornado.Control.Acceleration", 0.02), 0.001, 1, 0.02);
        this.controlDrag = finite(getConfig().getDouble("Abilities.Air.Tornado.Control.Drag", 0.88), 0, 0.99, 0.88);
        this.aimSmoothing = finite(getConfig().getDouble("Abilities.Air.Tornado.Control.AimSmoothing", 0.1), 0.01, 1, 0.1);
        this.visualSettings = new TornadoVisuals.Settings(
                getConfig().getInt("Abilities.Air.Tornado.Visuals.Ribbons", 4),
                getConfig().getInt("Abilities.Air.Tornado.Visuals.RibbonPoints", 32),
                getConfig().getInt("Abilities.Air.Tornado.Visuals.CloudLobes", 10),
                getConfig().getInt("Abilities.Air.Tornado.Visuals.GroundTendrils", 7));
        this.soundEnabled = getConfig().getBoolean("Abilities.Air.Tornado.Sound.Enabled", true);
        this.soundInterval = Math.max(5, Math.min(200, getConfig().getInt("Abilities.Air.Tornado.Sound.IntervalTicks", 8))) * 50L;
        this.soundVolume = (float) finite(getConfig().getDouble("Abilities.Air.Tornado.Sound.Volume", 1), 0, 4, 1);
        this.soundPitch = (float) finite(getConfig().getDouble("Abilities.Air.Tornado.Sound.Pitch", 0.65), 0.1, 2, 0.65);
        this.debris = new TornadoDebris(getConfig().getInt("Abilities.Air.Tornado.Visuals.GlassPieces", 20),
                getConfig().getDouble("Abilities.Air.Tornado.Visuals.MinimumGlassScale", 0.18),
                getConfig().getDouble("Abilities.Air.Tornado.Visuals.MaximumGlassScale", 0.65),
                getConfig().getDouble("Abilities.Air.Tornado.Visuals.GlassOrbitSpeed", 0.16));
        this.start();
        this.speed = finite(this.speed, 0.05, 2, 0.35);
        this.rideSpeed = finite(this.rideSpeed, 0.01, 2, 0.22);
        // Capture the attribute-adjusted maxima before applying the chosen charge size.
        this.chargeProfile = new TornadoChargeProfile(
                getConfig().getLong("Abilities.Air.Tornado.MinimumChargeTime", 500), this.chargeTime,
                getConfig().getLong("Abilities.Air.Tornado.MinimumCooldown", 3000), this.cooldown,
                getConfig().getDouble("Abilities.Air.Tornado.MinimumHeight", 4), this.tornadoHeight,
                getConfig().getDouble("Abilities.Air.Tornado.MinimumRadius", 1.5), this.tornadoRadius,
                getConfig().getDouble("Abilities.Air.Tornado.MinimumPullZoneRadius", 2.5), this.pullZoneRadius);
        this.lastChargeUpdateTime = System.currentTimeMillis();
        this.direction = this.getHorizontalDirection();
        this.origin = this.getGroundedTornadoLocation(this.player.getLocation().clone()
                .add(this.direction.clone().multiply(this.controlDistance)));
        if (this.origin == null || GeneralMethods.isRegionProtectedFromBuild(this, this.origin)) {
            this.remove();
        }
    }

    @Override
    public String getName() {
        return "Tornado";
    }

    @Override
    public void progress() {
        if (this.isRemoved()) return;
        if (this.player.isDead() || !this.player.isOnline()
                || !this.bPlayer.canBendIgnoreBindsCooldowns(this)
                || (this.currentLoc != null && !this.player.getWorld().equals(this.currentLoc.getWorld()))) {
            this.remove();
            return;
        }

        if (this.state == AbilityState.CHARGING) {
            if (this.origin == null || !this.player.getWorld().equals(this.origin.getWorld())
                    || GeneralMethods.isRegionProtectedFromBuild(this, this.origin)
                    || GeneralMethods.isRegionProtectedFromBuild(this, this.player.getLocation())) {
                this.remove();
                return;
            }
            if (this.wasHitDuringCharge()) {
                this.remove();
                return;
            }

            this.updateChargeProgress();
            if (!this.player.isSneaking()) {
                if (this.chargeProfile.ready(this.chargedDuration)) this.deployTornado();
                else this.remove();
            } else {
                this.renderChargeAnimation();
                if (this.chargeProfile.complete(this.chargedDuration)) this.deployTornado();
            }
            return;
        }

        if (this.currentLoc == null) {
            this.remove();
            return;
        } else if (GeneralMethods.isRegionProtectedFromBuild(this, this.currentLoc)) {
            this.remove();
            return;
        }

        final long now = System.currentTimeMillis();
        if ((this.riding && this.rideDuration > 0 && now - this.rideStartTime >= this.rideDuration)
                || (this.capturedPlayer == null && !this.riding && now - this.time >= this.tornadoRemoveDelay)) {
            this.remove();
            return;
        }

        if (this.riding) {
            if (this.player.isSneaking()) {
                this.remove();
                return;
            }
            this.controlRiddenTornado();
        } else if (this.launched) {
            this.motion = this.direction.clone().multiply(this.speed);
            this.moveTornado();
        } else if (this.player.isSneaking()) {
            this.controlHeldTornado();
        } else {
            this.motion.zero();
            this.velocity.zero();
            this.state = AbilityState.TORNADO_STATIONARY;
        }
        if (this.isRemoved()) {
            return;
        }

        final Location groundedLocation = this.getGroundedTornadoLocation(this.currentLoc);
        if (groundedLocation == null) {
            this.remove();
            return;
        }
        this.currentLoc = groundedLocation;

        if (this.riding) {
            final Location rideTarget = this.getRideTargetLocation();
            if (!this.isRideSpaceClear(rideTarget)) {
                this.remove();
                return;
            }
            this.updateRiderMotion(rideTarget);
        }

        try {
            this.renderTornadoAnimation();
            this.debris.update(this.currentLoc, this.tornadoHeight, this.tornadoRadius, this.getRunningTicks());
        } catch (RuntimeException | Error failure) {
            try {
                this.remove();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        this.pullEntitiesInsideTornado();
    }

    private void deployTornado() {
        if (this.state != AbilityState.CHARGING || !this.chargeProfile.ready(this.chargedDuration)) return;
        final TornadoChargeProfile.Size size = this.chargeProfile.sample(this.chargedDuration);
        this.tornadoHeight = size.height();
        this.tornadoRadius = size.radius();
        this.pullZoneRadius = size.pullRadius();
        this.cooldown = size.cooldown();
        this.maxPullDuration = this.chargeProfile.pullDuration(
                this.chargedDuration, this.minimumPullDuration, this.maxPullDuration);
        if (this.origin == null || !this.player.getWorld().equals(this.origin.getWorld())) {
            this.remove();
            return;
        }
        final Location deployLocation = this.getGroundedTornadoLocation(this.origin);
        if (deployLocation == null || GeneralMethods.isRegionProtectedFromBuild(this, deployLocation)) {
            this.remove();
            return;
        }

        this.origin = deployLocation.clone();
        this.currentLoc = this.origin.clone();
        this.motion.zero();
        this.time = System.currentTimeMillis();
        this.state = AbilityState.TORNADO_STATIONARY;
        this.bPlayer.addCooldown(this);
        this.playStormSound(this.currentLoc, Sound.ENTITY_BREEZE_CHARGE, 0.85F);
    }

    /** Locks the current charge size and throws the vortex in the caster's aim direction. */
    public boolean launch() {
        if (this.isRemoved() || this.riding || this.launched
                || !this.bPlayer.canBendIgnoreBindsCooldowns(this)) return false;
        if (this.state == AbilityState.CHARGING) {
            if (this.wasHitDuringCharge()) { this.remove(); return false; }
            this.updateChargeProgress();
            if (!this.chargeProfile.ready(this.chargedDuration)) return false;
            this.deployTornado();
        }
        if (this.isRemoved() || this.currentLoc == null
                || !this.player.getWorld().equals(this.currentLoc.getWorld())
                || GeneralMethods.isRegionProtectedFromBuild(this, this.currentLoc)) return false;
        this.direction = this.getHorizontalDirection();
        this.motion = this.direction.clone().multiply(this.speed);
        this.distanceTravelled = 0;
        this.time = System.currentTimeMillis();
        this.launched = true;
        this.state = AbilityState.TORNADO_MOVING;
        this.playStormSound(this.currentLoc, Sound.ENTITY_BREEZE_WIND_BURST, 1.0F);
        return true;
    }

    private static double finite(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    private Vector getHorizontalDirection() {
        final Vector horizontal = this.player.getEyeLocation().getDirection().clone();
        horizontal.setY(0);

        if (horizontal.lengthSquared() == 0) {
            return new Vector(0, 0, 1);
        }
        return horizontal.normalize();
    }

    public boolean tryStartRiding() {
        if (!this.rideEnabled || this.riding || this.launched || this.isRemoved() || this.state == AbilityState.CHARGING
                || this.currentLoc == null || !this.bPlayer.canBendIgnoreBindsCooldowns(this)
                || !this.isPlayerTargetingTornado()) {
            return false;
        }

        final Location rideTarget = this.getRideTargetLocation();
        if (!this.isRideSpaceClear(rideTarget)) {
            return false;
        }

        final AirScooter scooter = CoreAbility.getAbility(this.player, AirScooter.class);
        if (scooter != null) {
            scooter.remove();
        }
        final AirSpout spout = CoreAbility.getAbility(this.player, AirSpout.class);
        if (spout != null) {
            spout.remove();
        }

        this.riding = true;
        this.rideStartTime = System.currentTimeMillis();
        this.distanceTravelled = 0;
        this.flightHandler.createInstance(this.player, RIDE_FLIGHT_ID);
        this.player.setAllowFlight(true);
        this.player.setFlying(true);
        this.player.setSneaking(false);
        this.player.setFallDistance(0);
        return true;
    }

    private boolean isPlayerTargetingTornado() {
        final Location eye = this.player.getEyeLocation();
        if (!eye.getWorld().equals(this.currentLoc.getWorld())) {
            return false;
        }

        final Vector lookDirection = eye.getDirection().clone().normalize();
        final int samples = Math.max(5, (int) Math.ceil(this.tornadoHeight / 0.75));
        for (int sample = 0; sample <= samples; sample++) {
            final double progress = (double) sample / samples;
            final Location axisPoint = this.currentLoc.clone().add(0, this.tornadoHeight * progress, 0);
            final Vector toAxis = axisPoint.toVector().subtract(eye.toVector());
            final double distanceAlongRay = toAxis.dot(lookDirection);
            if (distanceAlongRay < 0 || distanceAlongRay > this.rideTargetingRange) {
                continue;
            }

            final double hitRadius = this.particleRadiusAt(progress, this.tornadoRadius) + 0.45;
            if (GeneralMethods.getDistanceFromLine(lookDirection, eye, axisPoint) <= hitRadius
                    && !GeneralMethods.isObstructed(eye, axisPoint)) {
                return true;
            }
        }
        return false;
    }

    private void controlRiddenTornado() {
        final Vector facing = this.getHorizontalDirection();
        if (facing.lengthSquared() == 0) {
            this.motion.zero();
            this.velocity.zero();
            this.state = AbilityState.TORNADO_STATIONARY;
            return;
        }

        this.direction = facing.clone();
        this.motion = facing.multiply(this.rideSpeed);
        this.state = AbilityState.TORNADO_MOVING;
        this.moveTornado();
    }

    private Location getRideTargetLocation() {
        final double rideHeight = Math.max(1.8, this.tornadoHeight * this.rideHeightPercentage);
        return this.currentLoc.clone().add(0, rideHeight, 0);
    }

    private boolean isRideSpaceClear(final Location target) {
        return target != null && target.getWorld() != null
                && this.isTornadoPassable(target.getBlock())
                && this.isTornadoPassable(target.clone().add(0, 1, 0).getBlock());
    }

    private void updateRiderMotion(final Location rideTarget) {
        final Vector riderVelocity = this.velocity.clone();
        final Vector correction = rideTarget.toVector().subtract(this.player.getLocation().toVector());
        final Vector horizontalCorrection = correction.clone().setY(0);
        if (horizontalCorrection.lengthSquared() > 0.0001) {
            final double correctionSpeed = Math.min(
                    Math.max(0.2, this.rideSpeed * 0.65), horizontalCorrection.length() * 0.18);
            riderVelocity.add(horizontalCorrection.normalize().multiply(correctionSpeed));
        }

        final double smoothedVerticalVelocity = this.player.getVelocity().getY() * 0.4
                + correction.getY() * this.rideVerticalSmoothing;
        riderVelocity.setY(GeneralMethods.clamp(
                smoothedVerticalVelocity, -this.rideMaxVerticalSpeed, this.rideMaxVerticalSpeed));
        GeneralMethods.setVelocity(this, this.player, riderVelocity);
        this.player.setFallDistance(0);
    }

    private void renderChargeAnimation() {
        // The charge anchor and orientation are fixed when charging starts.
        final Location target = this.origin;
        if (target == null) return;

        final TornadoChargeProfile.Size size = this.chargeProfile.sample(this.chargedDuration);
        final double progress = size.progress();
        final Location center = target.clone().add(0, 0.08, 0);
        final double formingHeight = size.height();
        final double formingRadius = size.radius();
        this.chargeAngle += 11.0 + progress * 8.0;
        this.renderParticleFunnel(center, formingHeight, formingRadius, this.chargeAngle);
        if (System.currentTimeMillis() - this.lastSoundTime >= CHARGE_SOUND_INTERVAL) {
            this.playStormSound(center, Sound.ENTITY_BREEZE_CHARGE, 0.75F);
            this.lastSoundTime = System.currentTimeMillis();
        }
    }

    private void updateChargeProgress() {
        final long now = System.currentTimeMillis();
        if (this.lastChargeUpdateTime == 0L) {
            this.lastChargeUpdateTime = now;
            return;
        }

        final long elapsed = Math.max(0, now - this.lastChargeUpdateTime);
        this.chargedDuration += Math.min(Long.MAX_VALUE - this.chargedDuration, elapsed);
        this.lastChargeUpdateTime = now;
    }

    private boolean wasHitDuringCharge() {
        final int currentNoDamageTicks = this.player.getNoDamageTicks();
        final boolean wasHit = currentNoDamageTicks > this.lastKnownNoDamageTicks && currentNoDamageTicks >= this.player.getMaximumNoDamageTicks() / 2;
        this.lastKnownNoDamageTicks = currentNoDamageTicks;
        return wasHit;
    }

    private Location getGroundedTornadoLocation(final Location base) {
        final Block topBlock = GeneralMethods.getTopBlock(base, 3, -3);
        if (topBlock == null) {
            return null;
        }

        final Location grounded = base.clone();
        grounded.setY(topBlock.getLocation().getY() + 1.0);
        return this.isTornadoSpaceClear(grounded) ? grounded : null;
    }

    private void renderTornadoAnimation() {
        final double movementFactor = this.state == AbilityState.TORNADO_MOVING ? 1.0 : 0.55;
        this.vortexAngle += Math.max(7.0, this.speed * 45.0 * movementFactor);
        this.renderParticleFunnel(this.currentLoc, this.tornadoHeight, this.tornadoRadius, this.vortexAngle);

        if (System.currentTimeMillis() - this.lastSoundTime >= this.soundInterval) {
            this.playStormSound(this.currentLoc, Sound.ENTITY_BREEZE_IDLE_GROUND, 0.8F);
            this.lastSoundTime = System.currentTimeMillis();
        }
    }

    private void renderParticleFunnel(final Location base, final double height,
                                      final double maximumRadius, final double rotationDegrees) {
        TornadoVisuals.render(base, this.getVisualDirection(), height, maximumRadius,
                Math.toRadians(rotationDegrees), this.visualSettings,
                (point, count, spread) -> this.playAirbendingParticles(point, count, spread, spread, spread, 0));
    }

    private double particleRadiusAt(final double progress, final double maximumRadius) {
        return maximumRadius * TornadoVisuals.radiusAt(progress);
    }

    private void playStormSound(Location location, Sound sound, float volume) {
        if (this.soundEnabled && getConfig().getBoolean("Properties.Air.PlaySound", true)
                && location != null && location.getWorld() != null) {
            location.getWorld().playSound(location, sound, volume * this.soundVolume, this.soundPitch);
        }
    }

    private void controlHeldTornado() {
        final Vector facing = this.getHorizontalDirection();
        final double currentAngle = Math.atan2(this.direction.getZ(), this.direction.getX());
        final double targetAngle = Math.atan2(facing.getZ(), facing.getX());
        final double difference = Math.atan2(Math.sin(targetAngle - currentAngle), Math.cos(targetAngle - currentAngle));
        final double angle = currentAngle + difference * this.aimSmoothing;
        this.direction = new Vector(Math.cos(angle), 0, Math.sin(angle));
        final Location target = this.player.getLocation().clone().add(this.direction.clone().multiply(this.controlDistance));
        final Vector displacement = target.toVector().subtract(this.currentLoc.toVector()).setY(0);
        final Vector desired = displacement.clone();
        if (desired.lengthSquared() > this.controlSpeed * this.controlSpeed) desired.normalize().multiply(this.controlSpeed);
        this.motion.multiply(this.controlDrag);
        final Vector steering = desired.subtract(this.motion.clone());
        if (steering.lengthSquared() > this.controlAcceleration * this.controlAcceleration) steering.normalize().multiply(this.controlAcceleration);
        this.motion.add(steering);
        if (this.motion.lengthSquared() > this.controlSpeed * this.controlSpeed) this.motion.normalize().multiply(this.controlSpeed);
        if (this.motion.lengthSquared() > displacement.lengthSquared()) this.motion = displacement;
        this.moveTornado();
    }

    private void moveTornado() {
        if (this.motion.lengthSquared() == 0) {
            this.velocity.zero();
            this.state = AbilityState.TORNADO_STATIONARY;
            return;
        }

        final Location previousLocation = this.currentLoc.clone();
        final Vector direction = this.motion.clone().normalize();
        double remaining = this.motion.length();

        while (remaining > 0) {
            final double segment = Math.min(0.25, remaining);
            if (!this.advanceTornado(direction, segment)) {
                this.updateVelocity(previousLocation);
                this.motion.zero();
                this.state = AbilityState.TORNADO_STATIONARY;
                if (this.launched) this.remove();
                return;
            }

            this.distanceTravelled += segment;
            if (this.launched && this.distanceTravelled >= this.range) {
                this.remove();
                return;
            }

            remaining -= segment;
        }
        this.updateVelocity(previousLocation);

        this.state = this.motion.lengthSquared() > 1.0E-9
                ? AbilityState.TORNADO_MOVING : AbilityState.TORNADO_STATIONARY;
    }

    private void updateVelocity(final Location previousLocation) {
        this.velocity = this.currentLoc.toVector().subtract(previousLocation.toVector());
    }

    private boolean advanceTornado(final Vector direction, final double distance) {
        if (!this.riding && GeneralMethods.checkDiagonalWall(this.currentLoc.clone().add(0, 0.5, 0), direction)) {
            return false;
        }

        final Location next = this.currentLoc.clone().add(direction.clone().multiply(distance));
        final Location grounded = this.getGroundedTornadoLocation(next);
        if (grounded == null || GeneralMethods.isRegionProtectedFromBuild(this, grounded)) {
            return false;
        }
        if (this.riding && grounded.getY() - this.currentLoc.getY() > 1.25) {
            return false;
        }

        this.currentLoc = grounded;
        return true;
    }

    private boolean isTornadoSpaceClear(final Location location) {
        final Block feet = location.getBlock();
        final Block body = location.clone().add(0, 1, 0).getBlock();
        return this.isTornadoPassable(feet) && this.isTornadoPassable(body);
    }

    private boolean isTornadoPassable(final Block block) {
        return GeneralMethods.isPassable(block) && !block.isLiquid();
    }

    private Vector getVisualDirection() {
        if (this.direction != null && this.direction.lengthSquared() > 0) {
            return this.direction.clone().normalize();
        }
        return new Vector(1, 0, 0);
    }

    private void pullEntitiesInsideTornado() {
        if (this.capturedPlayer != null) {
            this.progressCapturedPlayer();
            return;
        }
        this.pulledEntitiesThisTick.clear();
        this.lagCompensator.addSnapshot(this.getLagCompensationCollider());

        final Location pullCenter = this.currentLoc.clone().add(0, this.tornadoHeight / 2.0, 0);
        final AABB search = new AABB(pullCenter, this.pullZoneRadius + 0.75, this.tornadoHeight / 2.0 + 3);
        for (final Entity entity : search.getEntities(this::canPullEntity)) {
            if (entity.equals(this.player)) {
                continue;
            } else if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) {
                continue;
            } else if (entity instanceof Player && Commands.invincible.contains(((Player) entity).getName())) {
                continue;
            }

            if (entity instanceof Player) {
                this.lagCompensator.addPlayer((Player) entity);
                continue;
            }

            this.pullEntity(entity, this.currentLoc);
        }

        this.lagCompensator.update();
        if (this.isRemoved() || this.capturedPlayer != null) return;
        this.pullCaughtEntities();
    }

    private AABB getLagCompensationCollider() {
        final Location center = this.currentLoc.clone().add(0, this.tornadoHeight / 2.0, 0);
        return new AABB(center, this.pullZoneRadius, this.tornadoHeight / 2.0 + 0.5);
    }

    private boolean canPullEntity(Entity entity) {
        return entity != null && entity.isValid() && !entity.isDead() && !entity.equals(this.player)
                && !(entity instanceof ArmorStand)
                && !(entity instanceof Display)
                && (!(entity instanceof Player target) || (target.isOnline()
                && target.getGameMode() != GameMode.SPECTATOR && !Commands.invincible.contains(target.getName())
                && !Platform.players().isExternalSpectator(target)))
                && !GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation());
    }

    private void pullEntity(final Entity entity, final Location tornadoLocation) {
        if (this.isRemoved() || (this.capturedPlayer != null
                && !this.capturedPlayer.getUniqueId().equals(entity.getUniqueId()))
                || !this.canPullEntity(entity) || !this.isInPullZone(entity, tornadoLocation)) {
            return;
        }

        if (this.exhaustedPullEntities.contains(entity.getUniqueId())) {
            return;
        }

        this.caughtEntities.put(entity.getUniqueId(), entity);
        this.pulledEntitiesThisTick.add(entity.getUniqueId());

        final long now = System.currentTimeMillis();
        final long pullStart = this.pullStartTimes.computeIfAbsent(entity.getUniqueId(), uuid -> now);
        if (this.maxPullDuration > 0 && now - pullStart >= this.maxPullDuration) {
            if (this.exhaustedPullEntities.add(entity.getUniqueId())) {
                this.releaseEntity(entity, tornadoLocation);
            }
            this.caughtEntities.remove(entity.getUniqueId());
            return;
        }

        this.applyPullToEntity(entity, this.currentLoc);
        // Capture exactly one opponent. Finish that pull before consuming the vortex.
        if (entity instanceof Player target && this.capturedPlayer == null) {
            this.capturedPlayer = target;
            this.capturedAt = now;
        }
    }

    private void progressCapturedPlayer() {
        if (!this.shouldKeepCaughtEntity(this.capturedPlayer)) {
            this.remove();
            return;
        }
        if (System.currentTimeMillis() - this.capturedAt >= this.maxPullDuration) {
            try {
                this.releaseEntity(this.capturedPlayer, this.currentLoc);
            } finally {
                this.remove();
            }
            return;
        }
        this.applyPullToEntity(this.capturedPlayer, this.currentLoc);
    }

    private void pullCaughtEntities() {
        final ArrayList<UUID> toRemove = new ArrayList<>();

        for (final Map.Entry<UUID, Entity> entry : this.caughtEntities.entrySet()) {
            final UUID uuid = entry.getKey();
            if (this.pulledEntitiesThisTick.contains(uuid) || this.exhaustedPullEntities.contains(uuid)) {
                continue;
            }

            final Entity entity = entry.getValue();
            if (!this.shouldKeepCaughtEntity(entity)) {
                toRemove.add(uuid);
                continue;
            }

            final long pullStart = this.pullStartTimes.computeIfAbsent(uuid, ignored -> System.currentTimeMillis());
            if (this.maxPullDuration > 0 && System.currentTimeMillis() - pullStart >= this.maxPullDuration) {
                if (this.exhaustedPullEntities.add(uuid)) {
                    this.releaseEntity(entity, this.currentLoc);
                }
                toRemove.add(uuid);
                continue;
            }

            this.pulledEntitiesThisTick.add(uuid);
            this.applyPullToEntity(entity, this.currentLoc);
        }

        for (final UUID uuid : toRemove) {
            this.caughtEntities.remove(uuid);
        }
    }

    private boolean shouldKeepCaughtEntity(final Entity entity) {
        if (!this.canPullEntity(entity) || !entity.getWorld().equals(this.currentLoc.getWorld())) {
            return false;
        }
        if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) {
            return false;
        }
        if (entity instanceof Player && Commands.invincible.contains(((Player) entity).getName())) {
            return false;
        }

        final Location entityLoc = entity.getLocation();
        final double relativeY = entityLoc.getY() - this.currentLoc.getY();
        if (relativeY < -4.0 || relativeY > this.tornadoHeight + 3.0) {
            return false;
        }

        final double dx = entityLoc.getX() - this.currentLoc.getX();
        final double dz = entityLoc.getZ() - this.currentLoc.getZ();
        final double horizontalLimit = this.pullZoneRadius + 2.0;
        return (dx * dx) + (dz * dz) <= horizontalLimit * horizontalLimit;
    }

    private void applyPullToEntity(final Entity entity, final Location tornadoLocation) {
        final double pullStrength = Math.max(0.1, this.pullVelocity);
        final Location target = tornadoLocation.clone().add(0, Math.max(1.8, this.tornadoHeight * 0.45), 0);
        final Vector correction = GeneralMethods.getDirection(entity.getLocation(), target);
        if (correction.lengthSquared() > 0) {
            final double distance = correction.length();
            correction.normalize().multiply(Math.min(pullStrength, distance * 0.25));
        }
        final Vector velocity = this.velocity.clone().add(correction);

        GeneralMethods.setVelocity(this, entity, velocity);
        entity.setFallDistance(0F);

        if (this.damage > 0 && entity instanceof LivingEntity && this.canDamage(entity)) {
            DamageHandler.damageEntity(entity, this.damage, this);
        }

        if (entity instanceof Player) {
            final Player player = (Player) entity;
            this.lockTrappedPlayerAbilities(player);
            if (this.spinPlayers) {
                this.spinPlayer(player);
            }
        }
    }

    private void releaseEntity(final Entity entity, final Location tornadoLocation) {
        final Vector release = entity.getLocation().toVector().subtract(tornadoLocation.toVector());
        release.setY(0);
        if (release.lengthSquared() == 0) {
            release.copy(this.getVisualDirection());
        } else {
            release.normalize();
        }

        release.multiply(Math.max(0.25, this.pullVelocity * 1.1));
        release.setY(Math.max(0.12, entity.getVelocity().getY() * 0.35));
        GeneralMethods.setVelocity(this, entity, release);
    }

    private boolean canDamage(final Entity entity) {
        final long now = System.currentTimeMillis();
        final Long lastDamageTime = this.lastDamageTimes.get(entity.getUniqueId());
        if (lastDamageTime != null && now - lastDamageTime < this.damageInterval) {
            return false;
        }

        this.lastDamageTimes.put(entity.getUniqueId(), now);
        return true;
    }

    private void spinPlayer(final Player player) {
        final float spinAmount = (float) Math.max(8.0, Math.min(45.0, this.speed * 90.0));
        player.setRotation(player.getLocation().getYaw() + spinAmount, player.getLocation().getPitch());
    }

    private void lockTrappedPlayerAbilities(final Player player) {
        if (this.trappedAbilityCooldown <= 0) {
            return;
        }

        final long now = System.currentTimeMillis();
        final long refreshInterval = Math.max(250L, this.trappedAbilityCooldown / 2L);
        final Long lastRestricted = this.lastRestrictedTimes.get(player.getUniqueId());
        if (lastRestricted != null && now - lastRestricted < refreshInterval) {
            return;
        }

        this.lastRestrictedTimes.put(player.getUniqueId(), now);

        final AirScooter scooter = CoreAbility.getAbility(player, AirScooter.class);
        if (scooter != null) {
            scooter.remove();
        }

        final AirSpout spout = CoreAbility.getAbility(player, AirSpout.class);
        if (spout != null) {
            spout.remove();
        }

        final BendingPlayer targetBPlayer = BendingPlayer.getBendingPlayer(player);
        if (targetBPlayer == null) {
            return;
        }

        for (final String abilityName : TRAPPED_PLAYER_ABILITIES) {
            targetBPlayer.addCooldown(abilityName, this.trappedAbilityCooldown);
        }
    }

    private boolean isInPullZone(final Entity entity, final Location tornadoLocation) {
        if (!entity.getWorld().equals(tornadoLocation.getWorld())) {
            return false;
        }

        final Location entityLoc = entity.getLocation();
        final double relativeY = entityLoc.getY() - tornadoLocation.getY();
        if (relativeY < -3.0 || relativeY > this.tornadoHeight + 1.5) {
            return false;
        }

        final double dx = entityLoc.getX() - tornadoLocation.getX();
        final double dz = entityLoc.getZ() - tornadoLocation.getZ();
        final double allowedRadius = this.pullZoneRadius;
        return (dx * dx) + (dz * dz) <= allowedRadius * allowedRadius;
    }

    @Override
    public void remove() {
        if (this.isRemoved()) return;
        super.remove();
        if (!this.isRemoved()) {
            return;
        }
        this.capturedPlayer = null;
        this.caughtEntities.clear();
        this.lastDamageTimes.clear();
        this.pullStartTimes.clear();
        this.lastRestrictedTimes.clear();
        this.exhaustedPullEntities.clear();
        this.pulledEntitiesThisTick.clear();
        try {
            if (this.debris != null) this.debris.remove();
            if (this.currentLoc != null) this.playStormSound(this.currentLoc, Sound.ENTITY_BREEZE_LAND, 0.65F);
        } finally {
            if (this.riding) {
                this.riding = false;
                this.flightHandler.removeInstance(this.player, RIDE_FLIGHT_ID);
                this.player.setFallDistance(0);
            }
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

    public void setCooldown(final long cooldown) {
        this.cooldown = cooldown;
    }

    @Override
    public Location getLocation() {
        if (this.currentLoc != null) {
            return this.currentLoc;
        } else if (this.origin != null) {
            return this.origin;
        }
        return this.player != null ? this.player.getLocation() : null;
    }

    public void setLocation(final Location location) {
        this.origin = location;
    }

    public static enum AbilityState {
        CHARGING, TORNADO_MOVING, TORNADO_STATIONARY
    }
}
