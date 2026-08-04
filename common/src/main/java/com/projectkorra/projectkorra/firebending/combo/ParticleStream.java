package com.projectkorra.projectkorra.firebending.combo;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.firebending.util.FireDamageTimer;
import com.projectkorra.projectkorra.platform.mc.Effect;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.LightManager;
import com.projectkorra.projectkorra.util.ParticleEffect;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.util.Random;

/***
 * Is only here for legacy purposes. All fire combos used to use a form of this
 * stream for all their progress methods. If someone else was reliant on that,
 * they can use this ability instead.
 */
public class ParticleStream extends BukkitRunnable {
    /**
     * Maximum distance, in blocks, between particles when dynamic subdivision
     * is enabled. At 0.20, a stream moving 4 blocks per tick creates 20
     * interpolated segments for that tick.
     */
    private static final double DEFAULT_DYNAMIC_SUB_LOCATION_SPACING = 0.55;

    /**
     * Prevents extreme configured speeds from producing an excessive number
     * of particles in one tick.
     */
    private static final int DEFAULT_MAX_DYNAMIC_SUB_LOCATIONS = 16;

    private final double speed;
    private final double distance;
    private final Player player;
    private final BendingPlayer bPlayer;
    private final CoreAbility coreAbility;
    private final Vector direction;
    private final Location initialLocation;
    private final Location location;
    ParticleEffect particleEffect;
    private boolean useNewParticles;
    private boolean cancelled;
    private boolean collides;
    private boolean singlePoint;
    private boolean goThroughWater;
    private boolean particlesVisible;
    private int density;
    private int checkCollisionDelay;
    private int checkCollisionCounter;
    private float spread;
    private double collisionRadius;
    private int subLocations;
    private double damage;
    private double fireTicks;
    private double knockback;
    private final Random gameplayRandom;
    private boolean dynamicSubLocation;
    private double dynamicSubLocationSpacing;
    private int maxDynamicSubLocations;
    private boolean emittedInitialParticle;
    private boolean reachedRange;

    public ParticleStream(final Player player, final CoreAbility coreAbility, final Vector direction, final Location location, final double distance, final double speed) {
        this.useNewParticles = false;
        this.cancelled = false;
        this.collides = true;
        this.singlePoint = false;
        this.goThroughWater = true;
        this.particlesVisible = true;
        this.density = 1;
        this.checkCollisionDelay = 1;
        this.checkCollisionCounter = 0;
        this.spread = 0;
        this.collisionRadius = 2;
        this.subLocations = 0;
        this.dynamicSubLocation = false;
        this.dynamicSubLocationSpacing = DEFAULT_DYNAMIC_SUB_LOCATION_SPACING;
        this.maxDynamicSubLocations = DEFAULT_MAX_DYNAMIC_SUB_LOCATIONS;
        this.emittedInitialParticle = false;
        this.reachedRange = false;
        this.player = player;
        this.bPlayer = BendingPlayer.getBendingPlayer(player);
        this.particleEffect = bPlayer.canUseSubElement(SubElement.BLUE_FIRE) ? ParticleEffect.SOUL_FIRE_FLAME : ParticleEffect.FLAME;
        this.coreAbility = coreAbility;
        this.direction = direction;
        this.speed = speed;
        this.initialLocation = location.clone();
        this.location = location.clone();
        this.distance = distance;
        final String scope = getClass().getName() + ":block-drying:"
                + location.getBlockX() + ':' + location.getBlockY() + ':' + location.getBlockZ();
        this.gameplayRandom = PredictionDeterminism.random(player == null ? null : player.getUniqueId(), scope,
                coreAbility == null ? PredictionDeterminism.currentSeed() : coreAbility.getPredictionDeterministicSeed());
    }

    @Override
    public void run() {
        /*
         * Keep the stream alive for one scheduler interval after placing its
         * final point. This lets owning abilities such as AirSweep observe and
         * collision-check the exact final segment before the task disappears.
         */
        if (this.reachedRange) {
            this.remove();
            return;
        }

        if (!Double.isFinite(this.distance)
                || this.distance < 0
                || !Double.isFinite(this.speed)
                || !Double.isFinite(this.collisionRadius)) {
            this.remove();
            return;
        }

        final Block block = this.location.getBlock();

        if (RegionProtection.isRegionProtected(
                this.player,
                this.location,
                this.coreAbility
        )) {
            this.remove();
            return;
        }

        if ((this.goThroughWater
                && ElementalAbility.isWater(block)
                && !FireAbility.canPassThroughWater(block))
                && !ElementalAbility.isAir(
                block.getRelative(BlockFace.UP).getType()
        )
                && !ElementalAbility.isPlant(block)) {
            this.remove();
            return;
        }

        if (this.coreAbility.getElement() == Element.FIRE) {
            this.emitFirebendingLight(this.location);
        }

        if (GeneralMethods.checkDiagonalWall(
                this.location,
                this.direction
        )) {
            this.remove();
            return;
        }

        final Location previousLocation = this.location.clone();

        /*
         * The old implementation always moved by the complete speed and only
         * checked the range afterward. Therefore the final rendered segment
         * could extend beyond the configured range by almost one full tick of
         * movement.
         */
        final double travelledDistance =
                this.initialLocation.distance(previousLocation);
        final double remainingDistance =
                this.distance - travelledDistance;

        if (remainingDistance <= 1.0E-9) {
            this.reachedRange = true;
            return;
        }

        final double configuredStepDistance = Math.abs(this.speed);

        if (configuredStepDistance <= 1.0E-9) {
            this.remove();
            return;
        }

        /*
         * Clamp the last tick to the exact amount of range remaining.
         */
        final double movementDistance = Math.min(
                configuredStepDistance,
                remainingDistance
        );

        final Vector movement = this.direction.clone()
                .normalize()
                .multiply(movementDistance);

        final Location nextLocation =
                previousLocation.clone().add(movement);

        try {
            nextLocation.checkFinite();
        } catch (IllegalArgumentException e) {
            this.remove();
            return;
        }

        /*
         * Only the clamped segment is displayed, so no interpolated particle
         * anchor can be farther than distance blocks from initialLocation.
         */
        this.displayParticleSegment(
                previousLocation,
                nextLocation
        );

        this.location.add(movement);

        /*
         * Do collision work at the exact clamped endpoint before marking the
         * stream for removal on its next scheduler run.
         */
        if (this.collides
                && this.checkCollisionCounter
                % this.checkCollisionDelay == 0) {
            this.checkEntityCollisions(previousLocation);

            for (final Block nearbyBlock
                    : GeneralMethods.getBlocksAroundPoint(
                    this.location,
                    this.collisionRadius
            )) {
                FireAbility.dryWetBlocks(
                        nearbyBlock,
                        this.coreAbility,
                        this.gameplayRandom.nextInt(5) == 0
                );
            }
        }

        this.checkCollisionCounter++;

        this.reachedRange =
                movementDistance >= remainingDistance - 1.0E-9;

        if (this.singlePoint) {
            this.remove();
        }
    }

    /**
     * Renders particles over the complete movement segment for the current
     * tick.
     *
     * In fixed mode, {@link #subLocations} is used directly.
     *
     * In dynamic mode, the subdivision count is calculated from:
     *
     *     ceil(distanceTravelled / dynamicSubLocationSpacing)
     *
     * This makes the particle spacing independent of stream speed.
     */
    private void displayParticleSegment(
            final Location start,
            final Location end
    ) {
        if (!this.particlesVisible) {
            return;
        }

        final Vector displacement = end.toVector().subtract(start.toVector());
        final double segmentLength = displacement.length();
        final int subdivisions = this.resolveSubLocations(segmentLength);

        if (segmentLength <= 1.0E-9) {
            this.displayParticle(start);
            this.emittedInitialParticle = true;
            return;
        }

        /*
         * Preserve legacy behavior: zero fixed subdivisions means one visible
         * particle at the stream's new position every tick.
         */
        if (subdivisions <= 0) {
            this.displayParticle(end);
            this.emittedInitialParticle = true;
            return;
        }

        final Vector step = displacement.multiply(1.0 / subdivisions);
        final Location particleLocation = start.clone();

        /*
         * The previous tick already emitted this segment's starting endpoint.
         * Skip that duplicate after the first segment. This lowers the particle
         * count without creating a visual gap between ticks.
         */
        final int firstIndex = this.emittedInitialParticle ? 1 : 0;
        if (firstIndex == 1) {
            particleLocation.add(step);
        }

        for (int index = firstIndex; index <= subdivisions; index++) {
            this.displayParticle(particleLocation);
            particleLocation.add(step);
        }

        this.emittedInitialParticle = true;
    }

    /**
     * Resolves how many particle subdivisions should be used for one movement
     * segment.
     */
    private int resolveSubLocations(final double segmentLength) {
        if (!this.dynamicSubLocation) {
            return Math.max(0, this.subLocations);
        }

        if (!Double.isFinite(segmentLength) || segmentLength <= 1.0E-9) {
            return 0;
        }

        final int calculated = (int) Math.ceil(
                segmentLength / this.dynamicSubLocationSpacing
        );

        return Math.min(
                this.maxDynamicSubLocations,
                Math.max(1, calculated)
        );
    }

    /**
     * Emits this stream's configured particle at one interpolated location.
     */
    private void displayParticle(final Location location) {
        if (this.useNewParticles) {
            if (this.coreAbility instanceof FireAbility
                    && (this.particleEffect == ParticleEffect.FLAME
                    || this.particleEffect == ParticleEffect.SOUL_FIRE_FLAME)) {
                final FireAbility fireAbility = (FireAbility) this.coreAbility;
                fireAbility.playFirebendingParticles(
                        location,
                        this.density,
                        this.spread,
                        this.spread,
                        this.spread
                );
            } else if (this.coreAbility instanceof AirAbility
                    && this.particleEffect == ParticleEffect.SPELL) {
                final AirAbility airAbility = (AirAbility) this.coreAbility;
                airAbility.playAirbendingParticles(
                        location,
                        this.density,
                        this.spread,
                        this.spread,
                        this.spread
                );
            } else {
                this.particleEffect.display(
                        location,
                        this.density,
                        this.spread,
                        this.spread,
                        this.spread
                );
            }
        } else {
            for (int index = 0; index < this.density; index++) {
                location.getWorld().playEffect(
                        location,
                        Effect.MOBSPAWNER_FLAMES,
                        0,
                        15
                );
            }
        }
    }

    private void checkEntityCollisions(final Location previousLocation) {
        final double minimumStepDistance = Math.max(this.collisionRadius, 0.1);
        final int steps = Math.max(1, (int) Math.ceil(previousLocation.distance(this.location) / minimumStepDistance));
        final Vector step = this.location.toVector().subtract(previousLocation.toVector()).multiply(1.0 / steps);
        final Location checkLocation = previousLocation.clone();

        for (int i = 0; i <= steps; i++) {
            for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(checkLocation, this.collisionRadius)) {
                if (entity instanceof LivingEntity && !entity.equals(this.coreAbility.getPlayer()) && !entity.isDead()) {
                    this.collision((LivingEntity) entity, this.direction, this.coreAbility);
                }
            }
            checkLocation.add(step);
        }
    }

    public void collision(final LivingEntity entity, final Vector direction, final CoreAbility coreAbility) {
        entity.getLocation().getWorld().playSound(entity.getLocation(), Sound.ENTITY_VILLAGER_HURT, 0.3f, 0.3f);

        if (coreAbility.getName().equalsIgnoreCase("FireKick")) {
            final FireKick fireKick = CoreAbility.getAbility(this.player, FireKick.class);

            if (!fireKick.getAffectedEntities().contains(entity)) {
                fireKick.getAffectedEntities().add(entity);
                DamageHandler.damageEntity(entity, this.damage, coreAbility);
            }
        } else if (coreAbility.getName().equalsIgnoreCase("FireSpin")) {
            final FireSpin fireSpin = CoreAbility.getAbility(this.player, FireSpin.class);

            if (entity instanceof Player) {
                if (Commands.invincible.contains(((Player) entity).getName())) {
                    return;
                }
            }
            if (!fireSpin.getAffectedEntities().contains(entity)) {
                fireSpin.getAffectedEntities().add(entity);
                final double newKnockback = this.bPlayer.isAvatarState() ? this.knockback + 0.5 : this.knockback;
                DamageHandler.damageEntity(entity, this.damage, coreAbility);
                GeneralMethods.setVelocity(coreAbility, entity, direction.normalize().multiply(newKnockback));
            }
        } else if (coreAbility.getName().equalsIgnoreCase("JetBlaze")) {
            final JetBlaze jetBlaze = CoreAbility.getAbility(this.player, JetBlaze.class);

            if (!jetBlaze.getAffectedEntities().contains(entity)) {
                jetBlaze.getAffectedEntities().add(entity);
                DamageHandler.damageEntity(entity, this.damage, coreAbility);
                entity.setFireTicks((int) (this.fireTicks * 20));
                new FireDamageTimer(entity, this.player, coreAbility);
            }
        } else if (coreAbility.getName().equalsIgnoreCase("FireWheel")) {
            final FireWheel fireWheel = CoreAbility.getAbility(this.player, FireWheel.class);

            if (!fireWheel.getAffectedEntities().contains(entity)) {
                fireWheel.getAffectedEntities().add(entity);
                DamageHandler.damageEntity(entity, this.damage, coreAbility);
                entity.setFireTicks((int) (this.fireTicks * 20));
                new FireDamageTimer(entity, this.player, coreAbility);
                this.remove();
            }
        }
    }

    @Override
    public void cancel() {
        this.remove();
    }

    public Vector getDirection() {
        return this.direction.clone();
    }

    public Location getLocation() {
        return this.location;
    }

    public boolean isCancelled() {
        return this.cancelled;
    }

    public void remove() {
        super.cancel();
        this.cancelled = true;
    }

    public CoreAbility getAbility() {
        return this.coreAbility;
    }

    public void setCheckCollisionDelay(final int delay) {
        this.checkCollisionDelay = delay;
    }

    public void setCollides(final boolean b) {
        this.collides = b;
    }

    public void setGoThroughWater(boolean goThroughWater) {
        this.goThroughWater = goThroughWater;
    }

    public void setCollisionRadius(final double radius) {
        this.collisionRadius = radius;
    }

    public void setSubLocations(final int subLocations) {
        this.subLocations = Math.max(0, subLocations);
    }

    public void setDynamicSubLocation(final boolean dynamicSubLocation) {
        if (this.dynamicSubLocation != dynamicSubLocation) {
            this.emittedInitialParticle = false;
        }
        this.dynamicSubLocation = dynamicSubLocation;
    }

    /**
     * Sets the maximum physical distance between interpolated particles while
     * dynamic subdivision is enabled. Larger values emit fewer particles.
     */
    public void setDynamicSubLocationSpacing(final double spacing) {
        if (!Double.isFinite(spacing) || spacing <= 0) {
            throw new IllegalArgumentException(
                    "Dynamic sub-location spacing must be finite and greater than zero."
            );
        }
        this.dynamicSubLocationSpacing = spacing;
    }

    public void setMaxDynamicSubLocations(final int maximum) {
        this.maxDynamicSubLocations = Math.max(1, maximum);
    }

    public void setDensity(final int density) {
        this.density = density;
    }

    public void setParticlesVisible(final boolean particlesVisible) {
        this.particlesVisible = particlesVisible;
    }

    public void setDamage(final double damage) {
        this.damage = damage;
    }

    public void setKnockback(final double knockback) {
        this.knockback = knockback;
    }

    public void setFireTicks(final double fireTicks) {
        this.fireTicks = fireTicks;
    }

    public void setParticleEffect(final ParticleEffect effect) {
        this.particleEffect = effect;
    }

    public void setSinglePoint(final boolean b) {
        this.singlePoint = b;
    }

    public void setSpread(final float spread) {
        this.spread = spread;
    }

    public void setUseNewParticles(final boolean b) {
        this.useNewParticles = b;
    }

    public void emitFirebendingLight(final Location location) {
        if (!ConfigManager.defaultConfig.get().getBoolean("Properties.Fire.DynamicLight.Enabled")) return;

        int brightness = ConfigManager.defaultConfig.get().getInt("Properties.Fire.DynamicLight.Brightness");
        long keepAlive = ConfigManager.defaultConfig.get().getLong("Properties.Fire.DynamicLight.KeepAlive");

        if (brightness < 1 || brightness > 15) {
            throw new IllegalArgumentException("Properties.Fire.DynamicLight.Brightness must be between 1 and 15.");
        }

        LightManager.createLight(location).brightness(brightness).timeUntilFadeout(keepAlive).emit();
    }

    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this, ToStringStyle.MULTI_LINE_STYLE);
    }
}
