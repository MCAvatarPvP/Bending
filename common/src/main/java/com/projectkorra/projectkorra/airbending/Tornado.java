package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.Location;
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

    private static final int PARTICLE_STREAMS = 3;
    private static final int PARTICLE_INNER_STREAMS = 2;
    private static final String RIDE_FLIGHT_ID = "TornadoRide";
    private static final long CHARGE_SOUND_INTERVAL = 200L;
    private static final long TORNADO_SOUND_INTERVAL = 350L;
    private static final String[] TRAPPED_PLAYER_ABILITIES = {"AirScooter", "AirSpout"};
    private final Map<UUID, Long> lastDamageTimes;
    private final Map<UUID, Long> pullStartTimes;
    private final Map<UUID, Long> lastRestrictedTimes;
    private final Map<UUID, Entity> caughtEntities;
    private final Set<UUID> exhaustedPullEntities;
    private final Set<UUID> pulledEntitiesThisTick;
    private final AbilityLagCompensator lagCompensator;
    private final Set<AirBlast> handledBlasts;
    private final Set<AirSuction> handledSuctions;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.CHARGE_DURATION)
    private long chargeTime;
    private long damageInterval;
    private long maxPullDuration;
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
    private double tornadoDegreeParticles;
    private double tornadoHeightParticles;
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
        this.lagCompensator = new AbilityLagCompensator((p, snapshot) -> this.pullEntity(p, snapshot.getLocation()));
        this.handledBlasts = Collections.newSetFromMap(new HashMap<>());
        this.handledSuctions = Collections.newSetFromMap(new HashMap<>());

        if (CoreAbility.hasAbility(player, Tornado.class) || !this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
            return;
        }

        if (this.bPlayer.isOnCooldown(this)) {
            return;
        }

        if (CoreAbility.hasAbility(player, AirSpout.class)) {
            player.sendMessage(ChatColor.RED + "You can't use Tornado while using AirSpout.");
            return;
        }

        this.range = getConfig().getDouble("Abilities.Air.Tornado.Range");
        this.speed = getConfig().getDouble("Abilities.Air.Tornado.Speed");
        this.cooldown = getConfig().getLong("Abilities.Air.Tornado.Cooldown");
        this.chargeTime = getConfig().getLong("Abilities.Air.Tornado.ChargeTime", 750L);
        this.damage = getConfig().getDouble("Abilities.Air.Tornado.Damage", 0);
        this.damageInterval = getConfig().getLong("Abilities.Air.Tornado.DamageInterval", 500L);
        this.maxPullDuration = getConfig().getLong("Abilities.Air.Tornado.MaxPullDuration", 0L);
        this.trappedAbilityCooldown = getConfig().getLong("Abilities.Air.Tornado.TrappedAbilityCooldown", 1500L);
        this.tornadoHeight = getConfig().getDouble("Abilities.Air.Tornado.Height");
        this.tornadoRadius = getConfig().getDouble("Abilities.Air.Tornado.Radius");
        this.pullZoneRadius = getConfig().getDouble("Abilities.Air.Tornado.PullZoneRadius", this.tornadoRadius + 1.75);
        this.pullVelocity = getConfig().getDouble("Abilities.Air.Tornado.PullVelocity", Math.max(0.1, this.speed * 0.9));
        this.tornadoDegreeParticles = getConfig().getDouble("Abilities.Air.Tornado.DegreesPerParticle");
        this.tornadoHeightParticles = getConfig().getDouble("Abilities.Air.Tornado.HeightPerParticle");
        this.tornadoRemoveDelay = getConfig().getLong("Abilities.Air.Tornado.RemoveDelay");
        this.spinPlayers = getConfig().getBoolean("Abilities.Air.Tornado.SpinPlayers", false);
        this.rideEnabled = getConfig().getBoolean("Abilities.Air.Tornado.Ride.Enabled", true);
        this.rideDuration = getConfig().getLong("Abilities.Air.Tornado.Ride.Duration", 8000L);
        this.rideSpeed = getConfig().getDouble("Abilities.Air.Tornado.Ride.Speed", 0.8);
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

        this.start();
    }

    @Override
    public String getName() {
        return "Tornado";
    }

    @Override
    public void progress() {
        if (this.player.isDead() || !this.player.isOnline()) {
            this.remove();
            return;
        }

        if (this.state == AbilityState.CHARGING) {
            if (GeneralMethods.isRegionProtectedFromBuild(this, this.player.getLocation())) {
                this.remove();
                return;
            }
            if (!this.player.isSneaking()) {
                this.remove();
                return;
            }
            if (this.wasHitDuringCharge()) {
                this.remove();
                return;
            }

            this.updateChargeProgress();
            this.renderChargeAnimation();
            if (this.chargedDuration >= this.chargeTime) {
                this.deployTornado();
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
                || (!this.riding && now - this.time >= this.tornadoRemoveDelay)) {
            this.remove();
            return;
        }

        if (this.riding) {
            if (this.player.isSneaking()) {
                this.remove();
                return;
            }
            this.controlRiddenTornado();
        } else {
            this.absorbAirControllerPushes();
            this.moveTornado();
        }
        if (this.isRemoved()) {
            return;
        }

        if (!exhaustedPullEntities.isEmpty()) {
            remove();
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

        this.renderTornadoAnimation();
        this.pullEntitiesInsideTornado();
    }

    private void deployTornado() {
        final Vector facing = this.getHorizontalDirection();
        if (facing.lengthSquared() == 0) {
            this.remove();
            return;
        }

        final Location deployLocation = this.getGroundedTornadoLocation(this.player.getLocation().add(facing.clone().multiply(2)));
        if (deployLocation == null) {
            this.remove();
            return;
        }

        this.origin = deployLocation.clone();
        this.currentLoc = this.origin.clone();
        this.direction = facing;
        this.motion.zero();
        this.time = System.currentTimeMillis();
        this.state = AbilityState.TORNADO_STATIONARY;
        this.bPlayer.addCooldown(this);
    }

    private Vector getHorizontalDirection() {
        final Vector horizontal = this.player.getEyeLocation().getDirection().clone();
        horizontal.setY(0);

        if (horizontal.lengthSquared() == 0) {
            return new Vector(0, 0, 0);
        }
        return horizontal.normalize();
    }

    public boolean tryStartRiding() {
        if (!this.rideEnabled || this.riding || this.isRemoved() || this.state == AbilityState.CHARGING
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
        this.motion = facing.multiply(Math.max(this.speed, this.rideSpeed));
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
        final Vector facing = this.getHorizontalDirection();
        if (facing.lengthSquared() == 0) {
            return;
        }

        final Location target = this.getGroundedTornadoLocation(this.player.getLocation().add(facing.clone().multiply(2)));
        if (target == null) {
            return;
        }

        final double progress = this.chargeTime <= 0 ? 1.0
                : Math.min(1.0, (double) this.chargedDuration / this.chargeTime);
        final Location center = target.clone().add(0, 0.08, 0);
        final double formingHeight = Math.max(0.6, this.tornadoHeight * (0.12 + progress * 0.88));
        final double formingRadius = Math.max(0.4, this.tornadoRadius * (0.18 + progress * 0.82));

        this.chargeAngle += 11.0 + progress * 8.0;
        this.renderParticleFunnel(center, formingHeight, formingRadius, this.chargeAngle);

        if (System.currentTimeMillis() - this.lastSoundTime >= CHARGE_SOUND_INTERVAL) {
            playAirbendingSound(center, (float) (1.0 + (progress * 0.2)));
            this.lastSoundTime = System.currentTimeMillis();
        }
    }

    private void updateChargeProgress() {
        final long now = System.currentTimeMillis();
        if (this.lastChargeUpdateTime == 0L) {
            this.lastChargeUpdateTime = now;
            return;
        }

        this.chargedDuration += now - this.lastChargeUpdateTime;
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

        if (System.currentTimeMillis() - this.lastSoundTime >= TORNADO_SOUND_INTERVAL) {
            playAirbendingSound(this.currentLoc);
            this.lastSoundTime = System.currentTimeMillis();
        }
    }

    private void renderParticleFunnel(final Location base, final double height,
                                      final double maximumRadius, final double rotationDegrees) {
        if (base == null || base.getWorld() == null) {
            return;
        }

        final double safeHeight = Math.max(0.4, height);
        final double safeRadius = Math.max(0.25, maximumRadius);
        final double heightStep = GeneralMethods.clamp(this.tornadoHeightParticles * 0.35, 0.32, 0.55);
        final int verticalSamples = Math.max(4, (int) Math.ceil(safeHeight / heightStep));
        final double baseRotation = Math.toRadians(rotationDegrees);

        for (int level = 0; level <= verticalSamples; level++) {
            final double progress = (double) level / verticalSamples;
            final double y = safeHeight * progress;
            final double radius = this.particleRadiusAt(progress, safeRadius);
            final double spiralAngle = baseRotation + progress * Math.PI * 1.65;

            for (int stream = 0; stream < PARTICLE_STREAMS; stream++) {
                final double angle = spiralAngle + stream * (Math.PI * 2.0 / PARTICLE_STREAMS);
                this.spawnFunnelParticle(base, y, radius, angle);
            }

            if (level % 3 == 1) {
                for (int stream = 0; stream < PARTICLE_INNER_STREAMS; stream++) {
                    final double angle = spiralAngle + Math.PI / 3.0 + stream * Math.PI;
                    this.spawnFunnelParticle(base, y, radius * 0.58, angle);
                }
            }
        }

        if ((this.getRunningTicks() & 1L) == 0L) {
            for (final double progress : new double[]{0.12, 0.56, 0.96}) {
                this.renderParticleRing(base, safeHeight, safeRadius, baseRotation, progress);
            }
        }
    }

    private void renderParticleRing(final Location base, final double height, final double maximumRadius,
                                    final double baseRotation, final double progress) {
        final double radius = this.particleRadiusAt(progress, maximumRadius);
        final int configuredSamples = (int) Math.ceil(360.0 / Math.max(18.0, this.tornadoDegreeParticles * 3.0));
        final int circumferenceSamples = (int) Math.ceil(Math.PI * 2.0 * radius / 0.5);
        final int samples = Math.max(7, Math.min(configuredSamples, circumferenceSamples));
        final double ringRotation = baseRotation + progress * Math.PI * 1.65;

        for (int point = 0; point < samples; point++) {
            final double angle = ringRotation + point * (Math.PI * 2.0 / samples);
            this.spawnFunnelParticle(base, height * progress, radius, angle);
        }
    }

    private double particleRadiusAt(final double progress, final double maximumRadius) {
        return Math.max(0.18, maximumRadius * (0.10 + 0.72 * Math.pow(progress, 0.72)));
    }

    private void spawnFunnelParticle(final Location base, final double y,
                                     final double radius, final double angle) {
        final Location particle = base.clone().add(
                Math.cos(angle) * radius,
                y,
                Math.sin(angle) * radius
        );
        playAirbendingParticles(particle, 1, 0.018, 0.012, 0.018, 0.0);
    }

    private void absorbAirControllerPushes() {
        this.absorbAirBlastPushes();
        this.absorbAirSuctionPushes();
    }

    private void absorbAirBlastPushes() {
        for (final AirBlast airBlast : getAbilities(AirBlast.class)) {
            if (this.handledBlasts.contains(airBlast) || !airBlast.isProgressing() || airBlast.getBendingPlayer() != bPlayer) {
                continue;
            }

            if (!this.isWithinAirControllerHitbox(airBlast.getLocation(), airBlast.getRadius())) {
                continue;
            }

            final Vector push = airBlast.getDirection();
            if (push == null) {
                continue;
            }

            final Vector horizontalPush = push.clone();
            horizontalPush.setY(0);
            if (horizontalPush.lengthSquared() == 0) {
                continue;
            }

            this.applyPush(horizontalPush.normalize(), airBlast.getSpeed());
            this.handledBlasts.add(airBlast);
        }
    }

    private void absorbAirSuctionPushes() {
        for (final AirSuction airSuction : getAbilities(AirSuction.class)) {
            if (this.handledSuctions.contains(airSuction) || !airSuction.isProgressing() || airSuction.getBendingPlayer() != bPlayer) {
                continue;
            }

            if (!this.isWithinAirControllerHitbox(airSuction.getLocation(), airSuction.getRadius())) {
                continue;
            }

            final Vector push = airSuction.getDirection();
            if (push == null) {
                continue;
            }

            final Vector horizontalPush = push.clone();
            horizontalPush.setY(0);
            if (horizontalPush.lengthSquared() == 0) {
                continue;
            }

            this.applyPush(horizontalPush.normalize(), airSuction.getSpeed());
            this.handledSuctions.add(airSuction);
        }
    }

    private boolean isWithinAirControllerHitbox(final Location abilityLocation, final double abilityRadius) {
        if (abilityLocation == null || abilityLocation.getWorld() != this.currentLoc.getWorld()) {
            return false;
        }

        final Location collisionCenter = this.currentLoc.clone().add(0, Math.min(1.0, this.tornadoHeight * 0.18), 0);
        final double dx = abilityLocation.getX() - collisionCenter.getX();
        final double dz = abilityLocation.getZ() - collisionCenter.getZ();
        final double horizontalDistanceSq = (dx * dx) + (dz * dz);
        final double maxHorizontalDistance = Math.max(1.0, this.tornadoRadius * 0.55 + abilityRadius);
        if (horizontalDistanceSq > maxHorizontalDistance * maxHorizontalDistance) {
            return false;
        }

        final double verticalDistance = Math.abs(abilityLocation.getY() - collisionCenter.getY());
        final double maxVerticalDistance = Math.max(1.0, Math.min(2.0, this.tornadoHeight * 0.3));
        return verticalDistance <= maxVerticalDistance;
    }

    private void applyPush(final Vector pushDirection, final double airBlastSpeed) {
        final double baseSpeed = Math.max(0.12, this.speed);
        final double maxSpeed = Math.max(baseSpeed, this.speed * 1.35);
        final double blastFactor = Math.max(1.0, airBlastSpeed);
        final double pushStrength = Math.max(baseSpeed * 0.85, Math.min(maxSpeed, baseSpeed * blastFactor));
        final double retainedMomentum = Math.max(0.25, Math.min(0.72, 0.35 + (this.speed * 0.55)));

        if (this.motion.lengthSquared() == 0) {
            this.motion = pushDirection.clone().multiply(pushStrength);
        } else {
            this.motion = this.motion.clone().multiply(retainedMomentum).add(pushDirection.clone().multiply(pushStrength));
            if (this.motion.lengthSquared() > maxSpeed * maxSpeed) {
                this.motion.normalize().multiply(maxSpeed);
            }
        }

        this.direction = pushDirection.clone();
        this.state = AbilityState.TORNADO_MOVING;
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
                return;
            }

            this.distanceTravelled += segment;
            if (!this.riding && this.distanceTravelled >= this.range) {
                this.remove();
                return;
            }

            remaining -= segment;
        }
        this.updateVelocity(previousLocation);

        final double drag = Math.max(0.88, Math.min(0.97, 0.91 + (this.speed * 0.08)));
        this.motion.multiply(drag);
        if (this.motion.lengthSquared() < 0.0025) {
            this.motion.zero();
            this.state = AbilityState.TORNADO_STATIONARY;
        } else {
            this.direction = this.motion.clone().normalize();
            this.state = AbilityState.TORNADO_MOVING;
        }
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
        if (grounded == null) {
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
        this.pulledEntitiesThisTick.clear();
        this.lagCompensator.addSnapshot(this.getLagCompensationCollider());

        final Location pullCenter = this.currentLoc.clone().add(0, this.tornadoHeight / 2.0, 0);
        final double searchRadius = this.pullZoneRadius + 0.75;

        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(pullCenter, searchRadius)) {
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
        this.pullCaughtEntities();
    }

    private AABB getLagCompensationCollider() {
        final Location center = this.currentLoc.clone().add(0, this.tornadoHeight / 2.0, 0);
        return new AABB(center, this.pullZoneRadius, this.tornadoHeight / 2.0 + 0.5);
    }

    private void pullEntity(final Entity entity, final Location tornadoLocation) {
        if (!this.isInPullZone(entity, tornadoLocation)) {
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
        if (entity == null || !entity.isValid() || entity.getWorld() != this.currentLoc.getWorld()) {
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
        super.remove();
        if (!this.isRemoved()) {
            return;
        }
        if (this.riding) {
            this.riding = false;
            this.flightHandler.removeInstance(this.player, RIDE_FLIGHT_ID);
            this.player.setFallDistance(0);
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
