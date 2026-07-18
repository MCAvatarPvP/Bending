package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class AirSpout extends AirAbility {

    private static final int MIN_TENDRIL_POINTS = 4;
    private static final int MAX_TENDRIL_POINTS = 32;
    private static final double TWO_PI = Math.PI * 2.0;
    private static final double DEFAULT_TICK_SECONDS = 1.0 / 20.0;
    private static final double MAX_SIMULATION_STEP_SECONDS = 0.10;
    private final Deque<MotionSample> motionHistory = new ArrayDeque<>();
    private final List<AirTendril> tendrils = new ArrayList<>();
    private final List<Location> collisionLocations = new ArrayList<>();
    private int angle;
    private long animTime;
    private long interval;
    @Attribute(Attribute.DURATION)
    private long duration;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.HEIGHT)
    private double height;
    private double staminaDrainRate;
    private double decayMinimum;
    private long lastStaminaDrainTime;
    private boolean disableSprint;
    /*
     * Animation settings. All of these work without adding anything to the
     * config because each setting has a default value.
     */
    private int tendrilCount;
    private int constraintIterations;
    private double segmentSpacing;
    private double tendrilRadius;
    private double tendrilSway;
    private double maxBaseLag;
    private long trailDelayMillis;
    private long trailHistoryMillis;
    private double angularSpeed;
    private double particleFlowSpeed;
    private double particleSpacing;
    private double collisionSpacing;
    private double animationPhase;
    private double particleFlowOffset;
    private long lastAnimationNanos;
    private Vector visualBase;
    private Vector visualBaseVelocity;
    private String removalReason = "external removal";

    public AirSpout(final Player player) {
        super(player);

        final AirSpout spout = getAbility(player, AirSpout.class);
        if (spout != null) {
            spout.remove();
            return;
        }

        if (!this.bPlayer.canBend(this)) {
            return;
        }

        final AirScooter scooter = CoreAbility.getAbility(player, AirScooter.class);
        if (scooter != null) {
            scooter.remove();
        }

        final boolean cancelBlast = getConfig().getBoolean("Abilities.Air.AirSpout.CancelBlast");
        final AirBlast blast = CoreAbility.getAbility(player, AirBlast.class);
        if (cancelBlast && blast != null && blast.isFromOtherOrigin()) {
            blast.remove();
        }

        this.angle = 0;
        this.cooldown = getConfig().getLong("Abilities.Air.AirSpout.Cooldown");
        this.duration = getConfig().getLong("Abilities.Air.AirSpout.Duration");
        this.animTime = System.currentTimeMillis();
        this.interval = getConfig().getLong("Abilities.Air.AirSpout.Interval");
        this.height = getConfig().getDouble("Abilities.Air.AirSpout.Height");
        this.staminaDrainRate = getConfig().getDouble("Abilities.Air.AirSpout.StaminaDrainRate", 0.05);
        this.decayMinimum = getConfig().getDouble("Abilities.Air.AirBlast.DecayMinimum");
        this.disableSprint = getConfig().getBoolean("Abilities.Air.AirSpout.DisableSprint");

        this.tendrilCount = clampInt(
                getConfig().getInt("Abilities.Air.AirSpout.Animation.Tendrils", 3), 2, 5);
        this.constraintIterations = clampInt(
                getConfig().getInt("Abilities.Air.AirSpout.Animation.ConstraintIterations", 3), 1, 6);
        this.segmentSpacing = Math.max(0.20,
                getConfig().getDouble("Abilities.Air.AirSpout.Animation.SegmentSpacing", 0.42));
        this.tendrilRadius = Math.max(0.05,
                getConfig().getDouble("Abilities.Air.AirSpout.Animation.Radius", 0.34));
        this.tendrilSway = Math.max(0.0,
                getConfig().getDouble("Abilities.Air.AirSpout.Animation.Sway", 0.20));

        /*
         * A larger default lag makes turns visibly bend instead of making the
         * entire column jump beneath the player.
         */
        this.maxBaseLag = Math.max(0.0,
                getConfig().getDouble("Abilities.Air.AirSpout.Animation.MaxBaseLag", 2.25));
        this.trailDelayMillis = Math.max(0L,
                getConfig().getLong("Abilities.Air.AirSpout.Animation.TrailDelay", 650L));
        this.trailHistoryMillis = Math.max(this.trailDelayMillis + 250L,
                getConfig().getLong("Abilities.Air.AirSpout.Animation.HistoryLength", 1200L));
        this.angularSpeed = Math.max(0.0,
                getConfig().getDouble("Abilities.Air.AirSpout.Animation.AngularSpeed", 3.8));
        this.particleFlowSpeed = Math.max(0.05,
                getConfig().getDouble("Abilities.Air.AirSpout.Animation.FlowSpeed", 3.4));
        this.particleSpacing = Math.max(0.12,
                getConfig().getDouble("Abilities.Air.AirSpout.Animation.ParticleSpacing", 0.34));
        this.collisionSpacing = Math.max(0.15,
                getConfig().getDouble("Abilities.Air.AirSpout.Animation.CollisionSpacing", 0.28));

        if (this.staminaDrainRate > 0 && this.bPlayer.getAirBlastDecay() <= this.decayMinimum) {
            return;
        }

        final double heightRemoveThreshold = 2.0;
        if (!this.isWithinMaxSpoutHeight(heightRemoveThreshold)) {
            return;
        }

        this.flightHandler.createInstance(player, this.getName());
        this.start();
    }

    /**
     * This method was used for the old collision detection system. Please see
     * {@link Collision} for the new system.
     */
    @Deprecated
    public static boolean removeSpouts(final Location location, final double radius,
                                       final Player sourcePlayer) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        final double radiusSquared = radius * radius;
        boolean removed = false;

        for (final AirSpout spout : getAbilities(AirSpout.class)) {
            if (spout.player.equals(sourcePlayer)) {
                continue;
            }

            for (final Location collisionPoint : spout.getLocations()) {
                if (!location.getWorld().equals(collisionPoint.getWorld())) {
                    continue;
                }

                if (location.distanceSquared(collisionPoint) <= radiusSquared) {
                    spout.remove();
                    removed = true;
                    break;
                }
            }
        }

        return removed;
    }

    private static int clampInt(final int value, final int minimum, final int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double polylineLength(final List<Vector> points) {
        double length = 0.0;
        for (int index = 0; index < points.size() - 1; index++) {
            length += points.get(index).distance(points.get(index + 1));
        }
        return length;
    }

    private static Vector samplePolylineAtDistance(final List<Vector> points,
                                                   final double rawDistance) {
        if (points.isEmpty()) {
            return new Vector();
        }
        if (points.size() == 1 || rawDistance <= 0.0) {
            return points.get(0).clone();
        }

        double remaining = rawDistance;
        for (int index = 0; index < points.size() - 1; index++) {
            final Vector start = points.get(index);
            final Vector end = points.get(index + 1);
            final double segmentLength = start.distance(end);

            if (segmentLength <= 0.000001) {
                continue;
            }

            if (remaining <= segmentLength) {
                return lerp(start, end, remaining / segmentLength);
            }
            remaining -= segmentLength;
        }

        return points.get(points.size() - 1).clone();
    }

    private static Vector sampleByNormalizedIndex(final List<Vector> points,
                                                  final double rawProgress) {
        if (points.isEmpty()) {
            return new Vector();
        }
        if (points.size() == 1) {
            return points.get(0).clone();
        }

        final double progress = Math.max(0.0, Math.min(1.0, rawProgress));
        final double scaled = progress * (points.size() - 1);
        final int lower = (int) Math.floor(scaled);
        final int upper = Math.min(points.size() - 1, lower + 1);
        return lerp(points.get(lower), points.get(upper), scaled - lower);
    }

    private static Vector lerp(final Vector start, final Vector end,
                               final double progress) {
        return start.clone().multiply(1.0 - progress)
                .add(end.clone().multiply(progress));
    }

    private static double wrap(final double value, final double modulus) {
        if (modulus <= 0.0) {
            return 0.0;
        }

        double wrapped = value % modulus;
        if (wrapped < 0.0) {
            wrapped += modulus;
        }
        return wrapped;
    }

    private void allowFlight() {
        this.player.setAllowFlight(true);
        this.player.setFlying(true);
    }

    private void removeFlight() {
        // FlightHandler is a shared lease. AirSpout may suspend its own lift
        // above the configured height, but it must not revoke WaterSpout,
        // AirScooter, FireJet, Flight, or an addon grant owned by the player.
        if (this.flightHandler.hasOtherInstance(this.player, this.getName())) {
            return;
        }
        this.player.setFlying(false);
        this.player.setAllowFlight(false);
    }

    private boolean isWithinMaxSpoutHeight(final double threshold) {
        final Block ground = this.getGround();
        if (ground == null) {
            return false;
        }

        final double playerHeight = this.player.getLocation().getY();
        return playerHeight <= ground.getLocation().getY() + this.height + threshold;
    }

    private Block getGround() {
        final Block standingBlock = this.player.getLocation().getBlock();
        for (int i = 0; i <= this.height + 5; i++) {
            final Block block = standingBlock.getRelative(BlockFace.DOWN, i);
            if (GeneralMethods.isSolid(block) || ElementalAbility.isWater(block)) {
                return block;
            }
        }
        return null;
    }

    @Override
    public void handleCollision(final Collision collision) {
        if (collision.isRemovingFirst()) {
            this.bPlayer.addCooldown(this, this.cooldown);
        }
        super.handleCollision(collision);
    }

    @Override
    public void progress() {
        if (this.player.isDead() || !this.player.isOnline()
                || !this.bPlayer.canBendIgnoreBinds(this) || !this.bPlayer.canBind(this)) {
            this.removalReason = "player state or bending permission became invalid";
            this.remove();
            return;
        }

        if (this.duration != 0
                && System.currentTimeMillis() > this.getStartTime() + this.duration) {
            this.bPlayer.addCooldown(this);
            this.removalReason = "duration expired";
            this.remove();
            if (this.isRemoved()) return;
        }

        if (!this.consumeStamina()) {
            this.removalReason = "air stamina was exhausted";
            this.remove();
            if (this.isRemoved()) return;
        }

        final double heightRemoveThreshold = 2.0;
        if (!this.isWithinMaxSpoutHeight(heightRemoveThreshold)) {
            this.removalReason = "no ground was found or maximum spout height was exceeded";
            this.bPlayer.addCooldown(this);
            this.remove();
            return;
        }

        final Block eyeBlock = this.player.getEyeLocation().getBlock();
        if (ElementalAbility.isWater(eyeBlock) || GeneralMethods.isSolid(eyeBlock)) {
            this.removalReason = "player eye location entered a solid or water block";
            this.remove();
            return;
        }

        this.player.setFallDistance(0);
        if (this.disableSprint) {
            this.player.setSprinting(false);
        }

        final Block block = this.getGround();
        if (block == null) {
            this.removalReason = "no ground block was found";
            this.remove();
            return;
        }

        final double dy = this.player.getLocation().getY() - block.getY();
        if (dy > this.height) {
            this.removeFlight();
        } else {
            this.allowFlight();
        }

        this.animateAirSpout(block);
    }

    private boolean consumeStamina() {
        if (this.staminaDrainRate <= 0) {
            return true;
        }

        final long now = System.currentTimeMillis();
        if (this.lastStaminaDrainTime == 0) {
            this.lastStaminaDrainTime = now;
            this.bPlayer.resetAirBlast();
            return this.bPlayer.getAirBlastDecay() > this.decayMinimum;
        }

        final double elapsedSeconds = (now - this.lastStaminaDrainTime) / 1000.0;
        this.lastStaminaDrainTime = now;

        if (elapsedSeconds > 0) {
            this.bPlayer.increaseAirBlastDecay(
                    this.staminaDrainRate * elapsedSeconds, this.decayMinimum);
        }

        this.bPlayer.resetAirBlast();
        return this.bPlayer.getAirBlastDecay() > this.decayMinimum;
    }

    @Override
    public void remove() {
        if (this.isRemoved()) return;
        super.remove();
        this.flightHandler.removeInstance(this.player, this.getName());
        // removeInstance restores the state captured before the first shared
        // lease only when this was the final owner. Calling removeFlight here
        // overwrote that restoration and also disabled every remaining owner.
        this.motionHistory.clear();
        this.tendrils.clear();
        this.collisionLocations.clear();
    }

    private void animateAirSpout(final Block block) {
        if (!this.player.getWorld().equals(block.getWorld())) {
            return;
        }

        final long nowMillis = System.currentTimeMillis();
        final double deltaSeconds = this.getAnimationDeltaSeconds();
        final Location playerLocation = this.player.getLocation();
        final World world = playerLocation.getWorld();

        if (world == null) {
            return;
        }

        final Vector currentBase = new Vector(
                playerLocation.getX(), block.getY() + 1.05, playerLocation.getZ());
        final Vector currentTop = playerLocation.toVector().add(new Vector(0, 0.10, 0));

        this.recordMotionSample(nowMillis, currentTop);

        final Vector delayedBaseTarget = this.getSpineCenter(
                0.0, currentBase, currentTop, nowMillis);
        this.updateVisualBase(delayedBaseTarget, currentBase, deltaSeconds);

        this.animationPhase = wrap(
                this.animationPhase + this.angularSpeed * deltaSeconds, TWO_PI);
        this.particleFlowOffset = wrap(
                this.particleFlowOffset + this.particleFlowSpeed * deltaSeconds,
                this.particleSpacing);

        final double columnHeight = Math.max(0.5,
                currentTop.getY() - this.visualBase.getY());
        final int pointCount = clampInt(
                (int) Math.ceil(columnHeight / this.segmentSpacing) + 1,
                MIN_TENDRIL_POINTS, MAX_TENDRIL_POINTS);

        this.ensureTendrilCount();
        for (final AirTendril tendril : this.tendrils) {
            final List<Vector> targets = this.buildTargetCurve(
                    tendril, pointCount, currentBase, currentTop, nowMillis);
            tendril.resizePreservingShape(targets);
            this.simulateTendril(tendril, targets, deltaSeconds);
        }

        /*
         * Collision is rebuilt every simulation tick, not only when particles are
         * emitted. This keeps collisions correct even if the visual interval is
         * changed in the config.
         */
        this.rebuildCollisionLocations(world, currentBase, currentTop, nowMillis,
                pointCount);

        /*
         * Minecraft normally progresses abilities at 20 TPS. Capping the old
         * Interval setting at 50 ms prevents a high configured interval from
         * making the entire spout visibly jump several frames at a time.
         */
        final long renderInterval = this.interval <= 0L
                ? 0L : Math.min(this.interval, 50L);

        if (nowMillis >= this.animTime + renderInterval) {
            this.animTime = nowMillis;
            this.angle = (this.angle + 1) % 360;
            for (final AirTendril tendril : this.tendrils) {
                this.displayFlowingTendril(tendril, world);
            }
        }
    }

    private double getAnimationDeltaSeconds() {
        final long now = System.nanoTime();
        if (this.lastAnimationNanos == 0L) {
            this.lastAnimationNanos = now;
            return DEFAULT_TICK_SECONDS;
        }

        final double delta = (now - this.lastAnimationNanos) / 1_000_000_000.0;
        this.lastAnimationNanos = now;
        return Math.max(0.001, Math.min(MAX_SIMULATION_STEP_SECONDS, delta));
    }

    private void recordMotionSample(final long nowMillis, final Vector currentTop) {
        final Vector horizontal = new Vector(currentTop.getX(), 0, currentTop.getZ());
        final MotionSample newest = this.motionHistory.peekFirst();

        if (newest == null || newest.horizontal.distanceSquared(horizontal) > 0.000001
                || nowMillis - newest.timeMillis >= 25L) {
            this.motionHistory.addFirst(new MotionSample(nowMillis, horizontal));
        }

        final long oldestAllowed = nowMillis - this.trailHistoryMillis;
        while (!this.motionHistory.isEmpty()
                && this.motionHistory.peekLast().timeMillis < oldestAllowed) {
            this.motionHistory.removeLast();
        }
    }

    private Vector getSpineCenter(final double rawProgress, final Vector currentBase,
                                  final Vector currentTop, final long nowMillis) {
        final double progress = Math.max(0.0, Math.min(1.0, rawProgress));
        final long delay = (long) (this.trailDelayMillis * (1.0 - progress));
        final Vector delayedHorizontal = this.sampleHorizontalPosition(
                nowMillis - delay, currentTop);

        final Vector currentHorizontal = new Vector(
                currentTop.getX(), 0, currentTop.getZ());
        final Vector lag = delayedHorizontal.clone().subtract(currentHorizontal);

        /*
         * The bottom can trail the full configured amount. The allowed lag tapers
         * smoothly to zero toward the player, creating a curved wake instead of a
         * rigid diagonal line.
         */
        final double allowedLag = this.maxBaseLag * (1.0 - progress);
        if (allowedLag <= 0.0) {
            lag.zero();
        } else if (lag.lengthSquared() > allowedLag * allowedLag) {
            lag.normalize().multiply(allowedLag);
        }

        final double y = currentBase.getY()
                + (currentTop.getY() - currentBase.getY()) * progress;
        return new Vector(
                currentHorizontal.getX() + lag.getX(),
                y,
                currentHorizontal.getZ() + lag.getZ());
    }

    private Vector sampleHorizontalPosition(final long targetTimeMillis,
                                            final Vector fallbackTop) {
        if (this.motionHistory.isEmpty()) {
            return new Vector(fallbackTop.getX(), 0, fallbackTop.getZ());
        }

        MotionSample newer = null;
        for (final MotionSample sample : this.motionHistory) {
            if (sample.timeMillis <= targetTimeMillis) {
                if (newer == null || newer.timeMillis == sample.timeMillis) {
                    return sample.horizontal.clone();
                }

                final double fraction = Math.max(0.0, Math.min(1.0,
                        (targetTimeMillis - sample.timeMillis)
                                / (double) (newer.timeMillis - sample.timeMillis)));
                return lerp(sample.horizontal, newer.horizontal, fraction);
            }
            newer = sample;
        }

        return this.motionHistory.peekLast().horizontal.clone();
    }

    private void updateVisualBase(final Vector delayedTarget, final Vector currentBase,
                                  final double deltaSeconds) {
        if (this.visualBase == null
                || this.visualBase.distanceSquared(delayedTarget) > 36.0) {
            this.visualBase = delayedTarget.clone();
            this.visualBaseVelocity = new Vector();
            return;
        }

        final double frameScale = deltaSeconds / DEFAULT_TICK_SECONDS;
        final double damping = Math.pow(0.72, frameScale);
        final double attraction = 1.0 - Math.pow(1.0 - 0.18, frameScale);

        this.visualBaseVelocity.multiply(damping);
        this.visualBaseVelocity.add(
                delayedTarget.clone().subtract(this.visualBase).multiply(attraction));
        this.visualBase.add(this.visualBaseVelocity);
        this.visualBase.setY(delayedTarget.getY());

        /*
         * Keep the smoothed base inside the same maximum lag used by the history
         * curve, so physics and visuals cannot drift arbitrarily far behind.
         */
        final Vector lag = this.visualBase.clone().subtract(currentBase).setY(0);
        if (this.maxBaseLag <= 0.0) {
            this.visualBase.setX(currentBase.getX());
            this.visualBase.setZ(currentBase.getZ());
        } else if (lag.lengthSquared() > this.maxBaseLag * this.maxBaseLag) {
            lag.normalize().multiply(this.maxBaseLag);
            this.visualBase.setX(currentBase.getX() + lag.getX());
            this.visualBase.setZ(currentBase.getZ() + lag.getZ());
        }
    }

    private void ensureTendrilCount() {
        if (this.tendrils.size() == this.tendrilCount) {
            return;
        }

        this.tendrils.clear();
        for (int strand = 0; strand < this.tendrilCount; strand++) {
            this.tendrils.add(new AirTendril(
                    TWO_PI * strand / this.tendrilCount));
        }
    }

    private List<Vector> buildTargetCurve(final AirTendril tendril,
                                          final int pointCount, final Vector currentBase, final Vector currentTop,
                                          final long nowMillis) {
        final List<Vector> targets = new ArrayList<>(pointCount);
        final int last = pointCount - 1;

        for (int index = 0; index < pointCount; index++) {
            final double progress = index / (double) last;
            final Vector center;

            if (index == 0) {
                center = this.visualBase.clone();
            } else {
                center = this.getSpineCenter(
                        progress, currentBase, currentTop, nowMillis);
            }

            final double endpointScale;
            if (index == 0) {
                endpointScale = 0.45;
            } else if (index == last) {
                endpointScale = 0.20;
            } else {
                endpointScale = 0.60
                        + Math.sin(progress * Math.PI) * 0.40;
            }

            final double coilPhase = this.animationPhase + tendril.phaseOffset
                    + progress * Math.PI * 3.5;
            final double radius = this.tendrilRadius * endpointScale;
            center.add(new Vector(
                    Math.cos(coilPhase) * radius,
                    0,
                    Math.sin(coilPhase) * radius));

            if (index > 0 && index < last && this.tendrilSway > 0.0) {
                final double middleWeight = Math.sin(progress * Math.PI);
                center.add(new Vector(
                        Math.sin(this.animationPhase * 0.65
                                + progress * 4.0 + tendril.phaseOffset)
                                * this.tendrilSway * middleWeight,
                        0,
                        Math.cos(this.animationPhase * 0.55
                                + progress * 3.0 + tendril.phaseOffset)
                                * this.tendrilSway * middleWeight));
            }

            targets.add(center);
        }

        return targets;
    }

    private void simulateTendril(final AirTendril tendril,
                                 final List<Vector> targets, final double deltaSeconds) {
        final int last = tendril.points.size() - 1;
        if (last < 1) {
            return;
        }

        final double frameScale = deltaSeconds / DEFAULT_TICK_SECONDS;
        final double damping = Math.pow(0.82, frameScale);
        final double attraction = 1.0 - Math.pow(1.0 - 0.16, frameScale);

        for (int index = 1; index < last; index++) {
            final Vector point = tendril.points.get(index);
            final Vector previous = tendril.previous.get(index);
            final Vector velocity = point.clone().subtract(previous).multiply(damping);

            tendril.previous.set(index, point.clone());
            point.add(velocity);
            point.add(targets.get(index).clone().subtract(point).multiply(attraction));
        }

        final double restLength = Math.max(0.10,
                polylineLength(targets) / last);

        for (int iteration = 0; iteration < this.constraintIterations; iteration++) {
            tendril.points.set(0, targets.get(0).clone());
            tendril.points.set(last, targets.get(last).clone());

            for (int index = 0; index < last; index++) {
                this.constrainPair(tendril.points, index, restLength);
            }

            /*
             * A small curve-restoring pull prevents the distance constraints from
             * ironing a sharp turn back into a straight line.
             */
            for (int index = 1; index < last; index++) {
                final Vector point = tendril.points.get(index);
                point.add(targets.get(index).clone().subtract(point).multiply(0.07));
            }
        }

        tendril.points.set(0, targets.get(0).clone());
        tendril.points.set(last, targets.get(last).clone());
    }

    private void constrainPair(final List<Vector> points, final int index,
                               final double restLength) {
        final int last = points.size() - 1;
        final Vector first = points.get(index);
        final Vector second = points.get(index + 1);
        final Vector delta = second.clone().subtract(first);
        final double distance = delta.length();

        if (distance < 0.0001) {
            return;
        }

        final double difference = (distance - restLength) / distance;
        if (index == 0) {
            second.subtract(delta.multiply(difference));
        } else if (index + 1 == last) {
            first.add(delta.multiply(difference));
        } else {
            final Vector correction = delta.multiply(difference * 0.5);
            first.add(correction);
            second.subtract(correction);
        }
    }

    private void displayFlowingTendril(final AirTendril tendril,
                                       final World world) {
        if (tendril.points.size() < 2) {
            return;
        }

        final double totalLength = polylineLength(tendril.points);
        if (totalLength <= 0.0001) {
            return;
        }

        /*
         * Each strand starts at a different part of the spacing cycle so the
         * particles do not all form synchronized horizontal rings.
         */
        final double strandOffset = tendril.phaseOffset / TWO_PI
                * this.particleSpacing;
        double distance = wrap(
                this.particleFlowOffset + strandOffset,
                this.particleSpacing);

        for (; distance <= totalLength; distance += this.particleSpacing) {
            final Vector point = samplePolylineAtDistance(tendril.points, distance);
            playAirbendingParticles(point.toLocation(world), 1, 0F, 0F, 0F);
        }
    }

    private void rebuildCollisionLocations(final World world,
                                           final Vector currentBase, final Vector currentTop, final long nowMillis,
                                           final int pointCount) {
        this.collisionLocations.clear();

        /*
         * The center spine fills the space between visible tendrils. The tendril
         * samples then make the collision envelope match the particle radius and
         * the delayed curve seen by the player.
         */
        final List<Vector> spine = new ArrayList<>(pointCount);
        for (int index = 0; index < pointCount; index++) {
            final double progress = index / (double) (pointCount - 1);
            if (index == 0) {
                spine.add(this.visualBase.clone());
            } else {
                spine.add(this.getSpineCenter(
                        progress, currentBase, currentTop, nowMillis));
            }
        }

        this.appendCollisionPolyline(spine, world);
        for (final AirTendril tendril : this.tendrils) {
            this.appendCollisionPolyline(tendril.points, world);
        }
    }

    private void appendCollisionPolyline(final List<Vector> points,
                                         final World world) {
        if (points.isEmpty()) {
            return;
        }

        this.collisionLocations.add(points.get(0).toLocation(world));
        for (int index = 0; index < points.size() - 1; index++) {
            final Vector start = points.get(index);
            final Vector end = points.get(index + 1);
            final double distance = start.distance(end);
            final int steps = Math.max(1,
                    (int) Math.ceil(distance / this.collisionSpacing));

            for (int step = 1; step <= steps; step++) {
                final double progress = step / (double) steps;
                this.collisionLocations.add(
                        lerp(start, end, progress).toLocation(world));
            }
        }
    }

    @Override
    public String getName() {
        return "AirSpout";
    }

    @Override
    public Location getLocation() {
        return this.player != null ? this.player.getLocation() : null;
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    public void setCooldown(final long cooldown) {
        this.cooldown = cooldown;
    }

    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public boolean isHarmlessAbility() {
        return true;
    }

    @Override
    public boolean isCollidable() {
        return true;
    }

    @Override
    public List<Location> getLocations() {
        if (!this.collisionLocations.isEmpty()) {
            final List<Location> locations = new ArrayList<>(
                    this.collisionLocations.size());
            for (final Location location : this.collisionLocations) {
                locations.add(location.clone());
            }
            return locations;
        }

        /*
         * Fallback used only before the first animation tick. Once the spout has
         * progressed, collisions use the full curved cached geometry above.
         */
        final List<Location> fallback = new ArrayList<>();
        if (this.player == null) {
            return fallback;
        }

        final Location top = this.player.getLocation();
        for (double distance = 0.0; distance <= this.height;
             distance += this.collisionSpacing) {
            fallback.add(top.clone().add(0, -distance, 0));
        }
        return fallback;
    }

    public int getAngle() {
        return this.angle;
    }

    public void setAngle(final int angle) {
        this.angle = angle;
    }

    public long getAnimTime() {
        return this.animTime;
    }

    public void setAnimTime(final long animTime) {
        this.animTime = animTime;
    }

    public long getInterval() {
        return this.interval;
    }

    public void setInterval(final long interval) {
        this.interval = interval;
    }

    public double getHeight() {
        return this.height;
    }

    public void setHeight(final double height) {
        this.height = height;
    }

    private static final class MotionSample {
        private final long timeMillis;
        private final Vector horizontal;

        private MotionSample(final long timeMillis, final Vector horizontal) {
            this.timeMillis = timeMillis;
            this.horizontal = horizontal;
        }
    }

    private static final class AirTendril {
        private final double phaseOffset;
        private final List<Vector> points = new ArrayList<>();
        private final List<Vector> previous = new ArrayList<>();

        private AirTendril(final double phaseOffset) {
            this.phaseOffset = phaseOffset;
        }

        private void resizePreservingShape(final List<Vector> targets) {
            final int desiredSize = targets.size();
            if (desiredSize == this.points.size()) {
                return;
            }

            if (this.points.isEmpty()) {
                for (final Vector target : targets) {
                    this.points.add(target.clone());
                    this.previous.add(target.clone());
                }
                return;
            }

            final List<Vector> oldPoints = new ArrayList<>(this.points.size());
            final List<Vector> oldPrevious = new ArrayList<>(this.previous.size());
            for (final Vector point : this.points) {
                oldPoints.add(point.clone());
            }
            for (final Vector point : this.previous) {
                oldPrevious.add(point.clone());
            }

            this.points.clear();
            this.previous.clear();

            for (int index = 0; index < desiredSize; index++) {
                final double progress = index / (double) (desiredSize - 1);
                final Vector preservedPoint = sampleByNormalizedIndex(
                        oldPoints, progress);
                final Vector preservedPrevious = sampleByNormalizedIndex(
                        oldPrevious, progress);

                /*
                 * A light blend toward the new target prevents a resized strand from
                 * retaining a badly stretched shape, while still avoiding the old
                 * full reinitialization snap.
                 */
                preservedPoint.add(targets.get(index).clone()
                        .subtract(preservedPoint).multiply(0.15));
                preservedPrevious.add(targets.get(index).clone()
                        .subtract(preservedPrevious).multiply(0.15));

                this.points.add(preservedPoint);
                this.previous.add(preservedPrevious);
            }

            this.points.set(0, targets.get(0).clone());
            this.points.set(desiredSize - 1,
                    targets.get(desiredSize - 1).clone());
            this.previous.set(0, targets.get(0).clone());
            this.previous.set(desiredSize - 1,
                    targets.get(desiredSize - 1).clone());
        }
    }
}
