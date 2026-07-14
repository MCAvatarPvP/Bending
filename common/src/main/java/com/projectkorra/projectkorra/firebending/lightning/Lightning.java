package com.projectkorra.projectkorra.firebending.lightning;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.LightningAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.firebending.FireJet;
import com.projectkorra.projectkorra.firebending.FireShield;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.MovementHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class Lightning extends LightningAbility {

    private static final SilentHitboxMode DEFAULT_SILENT_HITBOX_MODE = SilentHitboxMode.DELAYED_INSTANT_ARC;
    private static final int POINT_GENERATION = 5;
    private static final double MAIN_BOLT_MIN_JAGGEDNESS_DEGREES = 3.0;
    private static final double MAIN_BOLT_MAX_JAGGEDNESS_DEGREES = 18.0;
    private static final double BRANCH_MIN_FORK_ANGLE_DEGREES = 18.0;
    private static final double BRANCH_MAX_FORK_ANGLE_DEGREES = 58.0;
    private static final double BRANCH_MAX_PARENT_LENGTH_RATIO = 0.36;
    private static final double BRANCH_MIN_LENGTH = 0.65;
    private static final double STRIKE_PARTICLE_SPACING = 0.15;
    private static final double DEFAULT_STRIKE_BLOCKS_PER_TICK = 1.25;
    private static final double MIN_STRIKE_BLOCKS_PER_TICK = 0.01;
    private static final double DEFAULT_SILENT_HITBOX_STEP = 0.20;
    private static final double DEFAULT_SILENT_HITBOX_RADIUS = 1.15;
    private static final double DEFAULT_SILENT_HITBOX_SELF_GRACE = 1.50;
    private static final double DEFAULT_SILENT_PROJECTILE_SPEED = -1.0;
    @Attribute("Charged")
    private boolean charged;
    private boolean hitWater;
    private boolean hitIce;
    private boolean selfHitWater;
    private boolean selfHitClose;
    private boolean allowOnFireJet;
    @Attribute("ArcOnIce")
    private boolean arcOnIce;
    private int waterArcs;
    @Attribute(Attribute.RANGE)
    private double range;
    @Attribute("SilentHitboxStep")
    private double silentHitboxStep;
    @Attribute("SilentHitboxRadius")
    private double silentHitboxRadius;
    @Attribute("SilentHitboxSelfGrace")
    private double silentHitboxSelfGrace;
    @Attribute("SilentHitboxProjectileSpeed")
    private double silentProjectileSpeed;
    private SilentHitboxMode silentHitboxMode;
    private double silentProjectileTravelled;
    private Vector silentProjectileDirection;
    private boolean silentHitResolved;
    @Attribute(Attribute.SPEED)
    private double speed;
    @Attribute(Attribute.CHARGE_DURATION)
    private double chargeTime;
    @Attribute("SubArcChance")
    private double subArcChance;
    @Attribute("MinimumSubArcs")
    private int minimumSubArcs;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    private double damageMultiplierRedirection;
    @Attribute("MaxChainArcs")
    private double maxChainArcs;
    @Attribute("Chain" + Attribute.RANGE)
    private double chainRange;
    @Attribute("WaterArc" + Attribute.RANGE)
    private double waterArcRange;
    @Attribute("ChainArcChance")
    private double chainArcChance;
    @Attribute("StunChance")
    private double stunChance;
    @Attribute("Stun" + Attribute.DURATION)
    private long stunDuration;
    @Attribute("MaxArcAngle")
    private double maxArcAngle;
    private double particleRotation;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private long redirectionDuration;
    private boolean canRedirectOnCD;
    private boolean canSwapSlots;
    private boolean allowWhenFireShield;
    private long startTime;
    private State state;
    private Location origin;
    private Location destination;
    private ArrayList<Entity> affectedEntities;
    private ArrayList<Arc> arcs;
    private ArrayList<BukkitRunnable> tasks;
    private ArrayList<Location> locations;
    private LightningHit pendingHit;
    private int matchedVisualTick;
    public Lightning(final Player player) {
        super(player);

        if (!this.bPlayer.canBendIgnoreCooldowns(this)) {
            return;
        }
        if (hasAbility(player, Lightning.class)) {
            if (!getAbility(player, Lightning.class).isCharged()) {
                return;
            }
        }

        this.charged = false;
        this.hitWater = false;
        this.hitIce = false;
        this.startTime = System.currentTimeMillis();
        this.state = State.START;
        this.affectedEntities = new ArrayList<>();
        this.arcs = new ArrayList<>();
        this.tasks = new ArrayList<>();
        this.locations = new ArrayList<>();
        this.pendingHit = null;
        this.silentProjectileTravelled = 0;
        this.silentProjectileDirection = null;
        this.silentHitResolved = false;
        this.matchedVisualTick = 0;

        this.selfHitWater = getConfig().getBoolean("Abilities.Fire.Lightning.SelfHitWater");
        this.selfHitClose = getConfig().getBoolean("Abilities.Fire.Lightning.SelfHitClose");
        this.arcOnIce = getConfig().getBoolean("Abilities.Fire.Lightning.ArcOnIce");
        this.range = applyModifiersRange(getConfig().getDouble("Abilities.Fire.Lightning.Range"));
        this.silentHitboxStep = Math.max(0.01, getConfig().getDouble("Abilities.Fire.Lightning.SilentHitbox.Step", DEFAULT_SILENT_HITBOX_STEP));
        this.silentHitboxRadius = Math.max(0.01, getConfig().getDouble("Abilities.Fire.Lightning.SilentHitbox.Radius", DEFAULT_SILENT_HITBOX_RADIUS));
        this.silentHitboxSelfGrace = Math.max(0.0, getConfig().getDouble("Abilities.Fire.Lightning.SilentHitbox.SelfGrace", DEFAULT_SILENT_HITBOX_SELF_GRACE));
        this.silentHitboxMode = this.parseSilentHitboxMode(getConfig().getString("Abilities.Fire.Lightning.SilentHitbox.Mode", DEFAULT_SILENT_HITBOX_MODE.name()));
        this.speed = Math.max(MIN_STRIKE_BLOCKS_PER_TICK, applyModifiers(getConfig().getDouble("Abilities.Fire.Lightning.Speed", DEFAULT_STRIKE_BLOCKS_PER_TICK)));
        this.silentProjectileSpeed = applyModifiers(getConfig().getDouble("Abilities.Fire.Lightning.SilentHitbox.ProjectileSpeed", DEFAULT_SILENT_PROJECTILE_SPEED));
        if (this.silentProjectileSpeed <= 0) {
            this.silentProjectileSpeed = this.speed;
        }
        this.silentProjectileSpeed = Math.max(MIN_STRIKE_BLOCKS_PER_TICK, this.silentProjectileSpeed);
        this.damage = applyModifiersDamage(getConfig().getDouble("Abilities.Fire.Lightning.Damage"));
        this.damageMultiplierRedirection = getConfig().getDouble("Abilities.Fire.Lightning.RedirectionDamageMultiplier");
        this.maxArcAngle = getConfig().getDouble("Abilities.Fire.Lightning.MaxArcAngle");
        this.subArcChance = getConfig().getDouble("Abilities.Fire.Lightning.SubArcChance");
        this.minimumSubArcs = Math.max(0, getConfig().getInt("Abilities.Fire.Lightning.MinimumSubArcs", 0));
        this.chainRange = applyModifiersRange(getConfig().getDouble("Abilities.Fire.Lightning.ChainArcRange"));
        this.chainArcChance = applyModifiers(getConfig().getDouble("Abilities.Fire.Lightning.ChainArcChance"));
        this.waterArcRange = applyModifiers(getConfig().getDouble("Abilities.Fire.Lightning.WaterArcRange"));
        this.stunChance = applyModifiers(getConfig().getDouble("Abilities.Fire.Lightning.StunChance"));
        this.stunDuration = getConfig().getLong("Abilities.Fire.Lightning.StunDuration");
        this.maxChainArcs = applyModifiers(getConfig().getInt("Abilities.Fire.Lightning.MaxChainArcs"));
        this.waterArcs = (int) applyModifiers(getConfig().getInt("Abilities.Fire.Lightning.WaterArcs"));
        this.chargeTime = applyInverseModifiers(getConfig().getLong("Abilities.Fire.Lightning.ChargeTime"));
        this.cooldown = applyModifiersCooldown(getConfig().getLong("Abilities.Fire.Lightning.Cooldown"));
        this.redirectionDuration = getConfig().getLong("Abilities.Fire.Lightning.RedirectionDuration");
        this.canRedirectOnCD = getConfig().getBoolean("Abilities.Fire.Lightning.RedirectionOnCD");
        this.canSwapSlots = getConfig().getBoolean("Abilities.Fire.Lightning.CanSwapSlots");
        this.allowWhenFireShield = getConfig().getBoolean("Abilities.Fire.Lightning.AllowWhenFireShield");
        this.allowOnFireJet = getConfig().getBoolean("Abilities.Fire.Lightning.AllowOnFireJet");

		/*this.range = this.getDayFactor(this.range);
		this.subArcChance = this.getDayFactor(this.subArcChance);
		this.damage = this.getDayFactor(this.damage);
		this.maxChainArcs = this.getDayFactor(this.maxChainArcs);
		this.chainArcChance = this.getDayFactor(this.chainArcChance);
		this.chainRange = this.getDayFactor(this.chainRange);
		this.waterArcRange = this.getDayFactor(this.waterArcRange);
		this.stunChance = this.getDayFactor(this.stunChance);
		this.stunDuration = this.getDayFactor(this.stunDuration);*/

        if (this.bPlayer.isAvatarState()) {
            this.chargeTime = getConfig().getLong("Abilities.Avatar.AvatarState.Fire.Lightning.ChargeTime");
            this.cooldown = getConfig().getLong("Abilities.Avatar.AvatarState.Fire.Lightning.Cooldown");
            this.damage = getConfig().getDouble("Abilities.Avatar.AvatarState.Fire.Lightning.Damage");
        }

        this.start();
    }

    public static int getPointGeneration() {
        return POINT_GENERATION;
    }

    /**
     * Damages an entity, and may cause paralysis depending on the config.
     *
     * @param lent The LivingEntity that is being damaged
     */
    public void electrocute(final LivingEntity lent) {
        playLightningbendingSound(lent.getLocation());
        playLightningbendingSound(this.player.getLocation());
        playLightningbendingHitSound(lent.getLocation());
        playLightningbendingHitSound(this.player.getLocation());
        DamageHandler.damageEntity(lent, this.damage, this);
        if (ThreadLocalRandom.current().nextDouble() <= this.stunChance) {
            final MovementHandler mh = new MovementHandler(lent, CoreAbility.getAbility(Lightning.class));
            mh.stopWithDuration(this.stunDuration, Element.LIGHTNING.getColor() + "* Electrocuted *");
        }
    }

    /**
     * Checks if a block is transparent, also considers the ARC_ON_ICE config
     * option.
     *
     * @param player the player that is viewing the block
     * @param block  the block
     * @return true if the block is transparent
     */
    private boolean isTransparentForLightning(final Player player, final Block block) {
        if (this.isTransparent(block)) {
            if (GeneralMethods.isRegionProtectedFromBuild(this, block.getLocation())) {
                return false;
            } else if (isIce(block)) {
                return this.arcOnIce;
            } else {
                return true;
            }
        }
        return false;
    }

    /**
     * Progresses the instance of this ability by 1 tick. This is the heart of
     * the ability, it checks if it needs to remove itself, and handles the
     * initial Lightning Arc generation.
     * <p>
     * Once all of the arcs have been created then this ability instance gets
     * removed, but the BukkitRunnables continue until they remove themselves.
     **/
    @Override
    public void progress() {
        if (this.player.isDead() || !this.player.isOnline()) {
            this.removeWithTasks();
            return;
        } else if (!this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
            this.remove();
            return;
        } else if (!(bPlayer.getBoundAbilityName().equalsIgnoreCase(getName())
                || canSwapSlots && bPlayer.getBoundAbilityName().equalsIgnoreCase("FireJet"))) {
            remove();
            return;
        } else if (CoreAbility.hasAbility(player, FireJet.class) && !allowOnFireJet) {
            this.removeWithTasks();
            return;
        } else if (CoreAbility.hasAbility(player, FireShield.class) && !allowWhenFireShield) {
            this.removeWithTasks();
            return;
        }

        this.locations.clear();

        if (this.state == State.START) {
            if (this.bPlayer.isOnCooldown(this)) {
                if (!canRedirectOnCD || !player.isSneaking()) remove();
                if (System.currentTimeMillis() - startTime > this.chargeTime) remove();
                this.startTime = System.currentTimeMillis();
                return;
            } else if (System.currentTimeMillis() - this.startTime > this.chargeTime) {
                if (!charged) {
                    playLightningbendingHitSound(player.getLocation());
                    ParticleEffect.FLASH.display(player.getLocation(), 1);
                }
                this.charged = true;
            }

            if (this.charged) {
                if (this.player.isSneaking()) {
                    Location eyeLoc = player.getEyeLocation();
                    Vector direction = eyeLoc.getDirection().normalize();
                    Location center = eyeLoc.clone().add(direction.clone().multiply(1.2));
                    center.add(0, 0.3, 0);

                    int points = 16;
                    double radius = 1;

                    Vector right = Lightning.this.getStablePerpendicular(direction);
                    Vector up = Lightning.this.safeNormalize(right.clone().crossProduct(direction), new Vector(0, 1, 0));

                    for (int i = 0; i < points; i++) {
                        double angle = 2 * Math.PI * i / points;
                        Vector offset = right.clone().multiply(Math.cos(angle)).add(up.clone().multiply(Math.sin(angle))).multiply(radius);
                        Location particleLoc = center.clone().add(offset);

                        player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, particleLoc, 0, 0, 0, 0);
                    }

                    ParticleEffect.CRIT_MAGIC.display(center, 1, 0.2F, 0.2F, 0.2F);
                    emitFirebendingLight(center);
                    if (ThreadLocalRandom.current().nextDouble() < .2) {
                        playLightningbendingChargingSound(center);
                    }

                    player.getLocation().getWorld().playSound(player.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 3f, 2f);
                } else {
                    this.bPlayer.addCooldown(this);
                    this.origin = this.player.getEyeLocation();
                    this.silentProjectileDirection = this.origin.getDirection().normalize();
                    this.silentProjectileTravelled = 0;
                    this.silentHitResolved = false;
                    this.matchedVisualTick = 0;

                    player.getLocation().getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 3f, 2f);

                    if (this.silentHitboxMode == SilentHitboxMode.DELAYED_INSTANT_ARC) {
                        this.destination = this.origin.clone().add(this.silentProjectileDirection.clone().multiply(this.range));
                        this.state = State.SILENT_PROJECTILE;
                    } else if (this.silentHitboxMode == SilentHitboxMode.MATCH_VISUAL) {
                        this.destination = this.origin.clone().add(this.silentProjectileDirection.clone().multiply(this.range));
                        this.state = State.MAINBOLT;
                    } else {
                        this.pendingHit = this.traceSilentProjectile(this.origin, this.silentProjectileDirection);
                        this.destination = this.pendingHit.getImpactLocation();
                        this.state = State.MAINBOLT;
                    }
                }
            } else {
                if (!this.player.isSneaking()) {
                    this.remove();
                    return;
                }

                final Location localLocation1 = this.player.getLocation();
                final double d1 = 0.1570796326794897D;
                final double d2 = 0.06283185307179587D;
                final double d3 = 1.0D;
                final double d4 = 1.0D;
                final double d5 = d1 * this.particleRotation;
                final double d6 = d2 * this.particleRotation;
                final double d7 = localLocation1.getX() + d4 * Math.cos(d5);
                final double d8 = localLocation1.getZ() + d4 * Math.sin(d5);
                final double newY = (localLocation1.getY() + 1.0D + d4 * Math.cos(d6));
                final Location localLocation2 = new Location(this.player.getWorld(), d7, newY, d8);
                playLightningbendingParticle(localLocation2);
                this.particleRotation += 1.0D / d3;
                if (ThreadLocalRandom.current().nextDouble() < .2) {
                    playLightningbendingChargingSound(this.player.getLocation());
                }
            }
        } else if (this.state == State.SILENT_PROJECTILE) {
            this.progressSilentProjectile();
        } else if (this.state == State.MAINBOLT) {
            this.createMainVisualArc(this.origin, this.destination);
            if (this.silentHitboxMode == SilentHitboxMode.INSTANT) {
                if (this.resolveSilentProjectileHit()) {
                    return;
                }
            }
            this.state = State.STRIKE;
        } else if (this.state == State.STRIKE) {
            if (this.silentHitboxMode == SilentHitboxMode.MATCH_VISUAL) {
                this.progressMatchedVisualStrike();
            } else {
                this.scheduleVisualArcParticles(false);
            }
            if (this.tasks.size() == 0 && this.arcs.isEmpty()) {
                this.remove();
                return;
            }
        }
    }

    /**
     * Removes the instance of this ability and cancels any current runnables
     */
    public void removeWithTasks() {
        for (final BukkitRunnable task : new ArrayList<>(this.tasks)) {
            task.cancel();
        }
        this.remove();
    }

    private SilentHitboxMode parseSilentHitboxMode(final String rawMode) {
        if (rawMode == null) {
            return DEFAULT_SILENT_HITBOX_MODE;
        }

        try {
            return SilentHitboxMode.valueOf(rawMode.trim().toUpperCase().replace('-', '_').replace(' ', '_'));
        } catch (final IllegalArgumentException ignored) {
            return DEFAULT_SILENT_HITBOX_MODE;
        }
    }

    private Arc createMainVisualArc(final Location start, final Location end) {
        final Arc mainArc = new Arc(start, end);
        mainArc.generatePoints(POINT_GENERATION);
        this.arcs.add(mainArc);
        final ArrayList<Arc> subArcs = mainArc.generateArcs(this.subArcChance, this.minimumSubArcs, this.range / 2.0, this.maxArcAngle);
        this.arcs.addAll(subArcs);
        return mainArc;
    }

    private void scheduleVisualArcParticles(final boolean instant) {
        for (int i = 0; i < this.arcs.size(); i++) {
            final Arc arc = this.arcs.get(i);
            for (int j = 0; j < arc.getAnimationLocations().size() - 1; j++) {
                final Location iterLoc = arc.getAnimationLocations().get(j).getLocation().clone();
                final Location dest = arc.getAnimationLocations().get(j + 1).getLocation().clone();
                double distanceTravelled = arc.getAnimationLocations().get(j).getAnimCounter() * Lightning.this.speed;
                while (iterLoc.distanceSquared(dest) > STRIKE_PARTICLE_SPACING * STRIKE_PARTICLE_SPACING) {
                    final BukkitRunnable task = new LightningParticle(arc, iterLoc.clone(), this.selfHitWater, this.waterArcs);
                    final long timer = instant ? 0L : Math.max(0L, Math.round(distanceTravelled / Lightning.this.speed));
                    task.runTaskTimer(ProjectKorra.plugin, timer, 1);
                    this.tasks.add(task);
                    iterLoc.add(Lightning.this.safeNormalize(GeneralMethods.getDirection(iterLoc, dest), arc.getDirection()).multiply(STRIKE_PARTICLE_SPACING));
                    distanceTravelled += STRIKE_PARTICLE_SPACING;
                }
            }
            this.arcs.remove(i);
            i--;
        }
    }

    private void progressSilentProjectile() {
        if (this.silentProjectileDirection == null) {
            this.silentProjectileDirection = this.origin.getDirection().normalize();
        }

        final double previousTravelled = this.silentProjectileTravelled;
        this.silentProjectileTravelled = Math.min(this.range, this.silentProjectileTravelled + this.silentProjectileSpeed);
        this.pendingHit = this.traceSilentProjectileSegment(this.origin, this.silentProjectileDirection, previousTravelled, this.silentProjectileTravelled);

        if (this.pendingHit != null || this.silentProjectileTravelled >= this.range) {
            if (this.pendingHit == null) {
                final Location end = this.origin.clone().add(this.silentProjectileDirection.clone().multiply(this.range));
                this.pendingHit = new LightningHit(end, null, end.getBlock());
            }

            this.destination = this.pendingHit.getImpactLocation();
            this.createMainVisualArc(this.origin, this.destination);
            if (this.resolveSilentProjectileHit()) {
                return;
            }
            this.scheduleVisualArcParticles(true);
            this.state = State.STRIKE;
        }
    }

    private void progressMatchedVisualStrike() {
        this.scheduleVisualArcParticlesForTick(this.matchedVisualTick++);

        if (!this.silentHitResolved) {
            if (this.silentProjectileDirection == null) {
                this.silentProjectileDirection = this.origin.getDirection().normalize();
            }

            final double previousTravelled = this.silentProjectileTravelled;
            this.silentProjectileTravelled = Math.min(this.range, this.silentProjectileTravelled + this.silentProjectileSpeed);
            this.pendingHit = this.traceSilentProjectileSegment(this.origin, this.silentProjectileDirection, previousTravelled, this.silentProjectileTravelled);

            if (this.pendingHit != null || this.silentProjectileTravelled >= this.range) {
                if (this.pendingHit == null) {
                    final Location end = this.origin.clone().add(this.silentProjectileDirection.clone().multiply(this.range));
                    this.pendingHit = new LightningHit(end, null, end.getBlock());
                }

                // Stop extending the main bolt past the real impact, but preserve any
                // particles that were already spawned for the portion that has visually
                // travelled.
                this.arcs.clear();
                if (this.resolveSilentProjectileHit()) {
                    return;
                }

                // Secondary chain/water arcs should begin once the main strike actually
                // lands, using the normal delayed visual scheduling from that point onward.
                this.scheduleVisualArcParticles(false);
            }
        } else if (!this.arcs.isEmpty()) {
            this.scheduleVisualArcParticles(false);
        }
    }

    private void scheduleVisualArcParticlesForTick(final int tick) {
        for (int i = 0; i < this.arcs.size(); i++) {
            final Arc arc = this.arcs.get(i);
            while (arc.getNextScheduledSegmentIndex() < arc.getAnimationLocations().size() - 1) {
                final int segmentIndex = arc.getNextScheduledSegmentIndex();
                final AnimationLocation animationLocation = arc.getAnimationLocations().get(segmentIndex);
                if (animationLocation.getAnimCounter() > tick) {
                    break;
                }

                final Location iterLoc = animationLocation.getLocation().clone();
                final Location dest = arc.getAnimationLocations().get(segmentIndex + 1).getLocation().clone();
                this.scheduleArcSegmentParticles(arc, iterLoc, dest, 0L);
                arc.setNextScheduledSegmentIndex(segmentIndex + 1);
            }

            if (arc.getNextScheduledSegmentIndex() >= arc.getAnimationLocations().size() - 1) {
                this.arcs.remove(i);
                i--;
            }
        }
    }

    private void scheduleArcSegmentParticles(final Arc arc, final Location start, final Location dest, final long delay) {
        final Location iterLoc = start.clone();
        while (iterLoc.distanceSquared(dest) > STRIKE_PARTICLE_SPACING * STRIKE_PARTICLE_SPACING) {
            final BukkitRunnable task = new LightningParticle(arc, iterLoc.clone(), this.selfHitWater, this.waterArcs);
            task.runTaskTimer(ProjectKorra.plugin, Math.max(0L, delay), 1);
            this.tasks.add(task);
            iterLoc.add(this.safeNormalize(GeneralMethods.getDirection(iterLoc, dest), arc.getDirection()).multiply(STRIKE_PARTICLE_SPACING));
        }
    }

    /**
     * Traces the real lightning hitbox as a straight, silent projectile. This is
     * intentionally separate from the noisy visual arc generation.
     */
    private LightningHit traceSilentProjectile(final Location start, final Vector direction) {
        final LightningHit hit = this.traceSilentProjectileSegment(start, direction, 0, this.range);
        if (hit != null) {
            return hit;
        }

        final Location end = start.clone().add(direction.clone().multiply(this.range));
        return new LightningHit(end, null, end.getBlock());
    }

    private LightningHit traceSilentProjectileSegment(final Location start, final Vector direction, final double fromDistance, final double toDistance) {
        for (double travelled = Math.max(0, fromDistance); travelled <= toDistance; travelled += this.silentHitboxStep) {
            final Location check = start.clone().add(direction.clone().multiply(travelled));
            if (!check.getWorld().equals(start.getWorld())) {
                break;
            }

            final LightningHit hit = this.checkSilentProjectilePoint(check, travelled);
            if (hit != null) {
                return hit;
            }
        }

        final Location endCheck = start.clone().add(direction.clone().multiply(toDistance));
        return this.checkSilentProjectilePoint(endCheck, toDistance);
    }

    private LightningHit checkSilentProjectilePoint(final Location check, final double travelled) {
        final Block block = check.getBlock();
        if (isWater(block) || (this.arcOnIce && isIce(block))) {
            return new LightningHit(check, null, block);
        }

        if (!this.isTransparentForLightning(this.player, block)) {
            return new LightningHit(check, null, block);
        }

        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(check, this.silentHitboxRadius)) {
            if (!(entity instanceof LivingEntity)) {
                continue;
            }

            // Do not let the straight silent hitbox reacquire the caster while aiming down/up.
            // Without this, downward shots can immediately resolve against the player's own
            // hitbox after SelfGrace expires, which makes the destination collapse near the
            // origin and causes the visual bolt to look like it never fired.
            if (entity.equals(this.player)) {
                if (!this.selfHitClose || travelled < this.silentHitboxSelfGrace) {
                    continue;
                }
            }

            if (this.affectedEntities.contains(entity)) {
                continue;
            }
            return new LightningHit(((LivingEntity) entity).getLocation().add(0, 1, 0), entity, block);
        }

        return null;
    }

    /**
     * Applies the deterministic hit result once. Returns true only when the
     * lightning redirects/removes itself and the caller should stop progressing.
     */
    private boolean resolveSilentProjectileHit() {
        if (this.silentHitResolved) {
            return false;
        }
        this.silentHitResolved = true;

        if (this.pendingHit == null) {
            this.pendingHit = this.traceSilentProjectile(this.origin, this.origin.getDirection().normalize());
        }

        final LightningHit hit = this.pendingHit;
        final Location impact = hit.getImpactLocation();
        ParticleEffect.FLASH.display(impact, 1);
        this.locations.add(impact.getBlock().getLocation());

        if (hit.getEntity() instanceof LivingEntity) {
            return this.affectLivingEntity((LivingEntity) hit.getEntity(), impact, false);
        }

        final Block block = hit.getBlock();
        if (block != null && (isWater(block) || (this.arcOnIce && isIce(block)))) {
            this.handleWaterImpact(impact, block);
        } else if (block != null && this.selfHitClose && this.player.getLocation().distanceSquared(impact) < 9 && !this.affectedEntities.contains(this.player)) {
            return this.affectLivingEntity(this.player, impact, false);
        }
        return false;
    }

    private boolean affectLivingEntity(final LivingEntity lent, final Location impact, final boolean fromWater) {
        if (lent.equals(this.player) && !fromWater && !this.selfHitClose) {
            return false;
        }
        if (this.affectedEntities.contains(lent)) {
            return false;
        }

        this.affectedEntities.add(lent);
        if (lent instanceof Player) {
            playLightningbendingSound(lent.getLocation());
            ParticleEffect.FLASH.display(impact, 1);
            playLightningbendingSound(this.player.getLocation());
            final Player p = (Player) lent;
            final Lightning light = getAbility(p, Lightning.class);
            if (light != null && light.state == State.START && System.currentTimeMillis() <= light.getStartTime() + this.redirectionDuration) {
                light.charged = true;
                if (this.damageMultiplierRedirection != 0) {
                    light.setDamage(this.damage * this.damageMultiplierRedirection);
                }
                if (this.canRedirectOnCD) {
                    BendingPlayer.getBendingPlayer(p).removeCooldown(CoreAbility.getAbility(Lightning.class));
                }
                this.removeWithTasks();
                return true;
            }
        }

        this.electrocute(lent);
        this.tryChainLightning(lent);
        return false;
    }

    private void tryChainLightning(final LivingEntity source) {
        if (this.maxChainArcs < 1 || ThreadLocalRandom.current().nextDouble() > this.chainArcChance) {
            return;
        }

        for (final Entity ent : GeneralMethods.getEntitiesAroundPoint(source.getLocation(), this.chainRange)) {
            if (ent.equals(this.player) || ent.equals(source) || !(ent instanceof LivingEntity) || this.affectedEntities.contains(ent)) {
                continue;
            }

            this.maxChainArcs--;
            final Location chainOrigin = source.getLocation().add(0, 1, 0);
            final Location chainDestination = ent.getLocation().add(0, 1, 0);
            final Arc newArc = new Arc(chainOrigin, chainDestination);
            newArc.generatePoints(POINT_GENERATION);
            this.arcs.add(newArc);

            if (this.affectLivingEntity((LivingEntity) ent, chainDestination, false)) {
                return;
            }
            break;
        }
    }

    private void handleWaterImpact(final Location impact, final Block block) {
        if (!this.hitWater) {
            this.hitWater = true;
            if (isIce(block)) {
                this.hitIce = true;
            }
        }

        this.generateWaterSpreadArcs(impact);

        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(impact, this.waterArcRange)) {
            if (!(entity instanceof LivingEntity) || this.affectedEntities.contains(entity)) {
                continue;
            }

            final Block feet = entity.getLocation().getBlock();
            final Block eyes = entity instanceof LivingEntity ? ((LivingEntity) entity).getEyeLocation().getBlock() : feet;
            final boolean inConductiveBlock = isWater(feet) || isWater(eyes) || (this.hitIce && (isIce(feet) || isIce(eyes)));
            if (!inConductiveBlock) {
                continue;
            }
            if (entity.equals(this.player) && !this.selfHitWater) {
                continue;
            }

            final Location arcEnd = entity.getLocation().add(0, 1, 0);
            final Arc newArc = new Arc(impact, arcEnd);
            newArc.generatePoints(POINT_GENERATION);
            this.arcs.add(newArc);

            if (this.affectLivingEntity((LivingEntity) entity, arcEnd, true)) {
                return;
            }
        }
    }

    private void generateWaterSpreadArcs(final Location impact) {
        for (int i = 0; i < this.waterArcs; i++) {
            final double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0;
            final Vector direction = new Vector(Math.cos(angle), 0, Math.sin(angle));
            Location end = impact.clone();

            for (double travelled = 0.5; travelled <= this.waterArcRange; travelled += 0.5) {
                final Location check = impact.clone().add(direction.clone().multiply(travelled));
                final Block spreadBlock = check.getBlock();
                if (isWater(spreadBlock) || (this.arcOnIce && isIce(spreadBlock))) {
                    end = check;
                } else if (!this.isTransparentForLightning(this.player, spreadBlock)) {
                    break;
                }
            }

            final Arc newArc = new Arc(impact.clone(), end.clone());
            newArc.generatePoints(POINT_GENERATION);
            this.arcs.add(newArc);
        }
    }

    /**
     * Safely normalizes a vector. Bukkit's normalize() is unsafe for zero-length
     * or nearly zero-length vectors, which can happen when the player looks almost
     * straight up/down or when an arc segment collapses to the same point.
     */
    private Vector safeNormalize(final Vector vector, final Vector fallback) {
        if (vector == null || vector.lengthSquared() < 1.0E-8 || Double.isNaN(vector.lengthSquared())) {
            return fallback.clone().normalize();
        }
        return vector.clone().normalize();
    }

    /**
     * Returns a stable vector perpendicular to the given direction. This avoids
     * the old X/Z-only rotation problem that made lightning visually freeze or
     * collapse when the player looked downward or upward.
     */
    private Vector getStablePerpendicular(final Vector direction) {
        final Vector dir = this.safeNormalize(direction, new Vector(0, 0, 1));
        Vector axis = new Vector(0, 1, 0);
        if (Math.abs(dir.dot(axis)) > 0.90) {
            axis = new Vector(1, 0, 0);
        }
        return this.safeNormalize(dir.clone().crossProduct(axis), new Vector(1, 0, 0));
    }

    /**
     * Creates a random 3D offset perpendicular to the segment direction. The
     * visible lightning can now jitter around any aim direction, including nearly
     * vertical shots, instead of relying on rotateXZ().
     */
    private Vector randomPerpendicularOffset(final Vector direction, final double magnitude) {
        final Vector dir = this.safeNormalize(direction, new Vector(0, 0, 1));
        final Vector right = this.getStablePerpendicular(dir);
        final Vector up = this.safeNormalize(right.clone().crossProduct(dir), new Vector(0, 1, 0));
        final double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0;
        return right.multiply(Math.cos(angle) * magnitude).add(up.multiply(Math.sin(angle) * magnitude));
    }

    /**
     * Creates a fork direction for branch lightning. Branches should not stay almost
     * parallel to the main bolt; real lightning tends to split sharply away from
     * the trunk, then fade out quickly.
     */
    private Vector randomForkDirection(final Vector direction, final double maxArcAngleDegrees) {
        final Vector dir = this.safeNormalize(direction, new Vector(0, 0, 1));
        final double userAngle = Math.max(0.0, maxArcAngleDegrees);
        final double maxForkAngle = Math.min(BRANCH_MAX_FORK_ANGLE_DEGREES, Math.max(BRANCH_MIN_FORK_ANGLE_DEGREES + 8.0, userAngle * 3.0));
        final double minForkAngle = Math.min(BRANCH_MIN_FORK_ANGLE_DEGREES, maxForkAngle);
        final double angle = Math.toRadians(ThreadLocalRandom.current().nextDouble(minForkAngle, maxForkAngle));
        final Vector sideways = this.randomPerpendicularOffset(dir, Math.sin(angle));
        return this.safeNormalize(dir.multiply(Math.cos(angle)).add(sideways), dir);
    }

    @Override
    public String getName() {
        return "Lightning";
    }

    @Override
    public Location getLocation() {
        return this.origin;
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
        return true;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isCollidable() {
        return this.arcs.size() > 0;
    }

    @Override
    public List<Location> getLocations() {
        return this.locations;
    }

    public boolean isCharged() {
        return this.charged;
    }

    public void setCharged(final boolean charged) {
        this.charged = charged;
    }

    public boolean isHitWater() {
        return this.hitWater;
    }

    public void setHitWater(final boolean hitWater) {
        this.hitWater = hitWater;
    }

    public boolean isHitIce() {
        return this.hitIce;
    }

    public void setHitIce(final boolean hitIce) {
        this.hitIce = hitIce;
    }

    public boolean isSelfHitWater() {
        return this.selfHitWater;
    }

    public void setSelfHitWater(final boolean selfHitWater) {
        this.selfHitWater = selfHitWater;
    }

    public boolean isSelfHitClose() {
        return this.selfHitClose;
    }

    public void setSelfHitClose(final boolean selfHitClose) {
        this.selfHitClose = selfHitClose;
    }

    public boolean isArcOnIce() {
        return this.arcOnIce;
    }

    public void setArcOnIce(final boolean arcOnIce) {
        this.arcOnIce = arcOnIce;
    }

    public int getWaterArcs() {
        return this.waterArcs;
    }

    public void setWaterArcs(final int waterArcs) {
        this.waterArcs = waterArcs;
    }

    public double getRange() {
        return this.range;
    }

    public void setRange(final double range) {
        this.range = range;
    }

    public double getSilentHitboxStep() {
        return this.silentHitboxStep;
    }

    public void setSilentHitboxStep(final double silentHitboxStep) {
        this.silentHitboxStep = Math.max(0.01, silentHitboxStep);
    }

    public double getSilentHitboxRadius() {
        return this.silentHitboxRadius;
    }

    public void setSilentHitboxRadius(final double silentHitboxRadius) {
        this.silentHitboxRadius = Math.max(0.01, silentHitboxRadius);
    }

    public double getSilentHitboxSelfGrace() {
        return this.silentHitboxSelfGrace;
    }

    public void setSilentHitboxSelfGrace(final double silentHitboxSelfGrace) {
        this.silentHitboxSelfGrace = Math.max(0.0, silentHitboxSelfGrace);
    }

    public double getSilentProjectileSpeed() {
        return this.silentProjectileSpeed;
    }

    public void setSilentProjectileSpeed(final double silentProjectileSpeed) {
        this.silentProjectileSpeed = Math.max(MIN_STRIKE_BLOCKS_PER_TICK, silentProjectileSpeed);
    }

    public SilentHitboxMode getSilentHitboxMode() {
        return this.silentHitboxMode;
    }

    public void setSilentHitboxMode(final SilentHitboxMode silentHitboxMode) {
        this.silentHitboxMode = silentHitboxMode == null ? DEFAULT_SILENT_HITBOX_MODE : silentHitboxMode;
    }

    public double getSpeed() {
        return this.speed;
    }

    public void setSpeed(final double speed) {
        this.speed = Math.max(MIN_STRIKE_BLOCKS_PER_TICK, speed);
    }

    public double getChargeTime() {
        return this.chargeTime;
    }

    public void setChargeTime(final double chargeTime) {
        this.chargeTime = chargeTime;
    }

    public double getSubArcChance() {
        return this.subArcChance;
    }

    public void setSubArcChance(final double subArcChance) {
        this.subArcChance = subArcChance;
    }

    public int getMinimumSubArcs() {
        return this.minimumSubArcs;
    }

    public void setMinimumSubArcs(final int minimumSubArcs) {
        this.minimumSubArcs = Math.max(0, minimumSubArcs);
    }

    public double getDamage() {
        return this.damage;
    }

    public void setDamage(final double damage) {
        this.damage = damage;
    }

    public double getMaxChainArcs() {
        return this.maxChainArcs;
    }

    public void setMaxChainArcs(final double maxChainArcs) {
        this.maxChainArcs = maxChainArcs;
    }

    public double getChainRange() {
        return this.chainRange;
    }

    public void setChainRange(final double chainRange) {
        this.chainRange = chainRange;
    }

    public double getWaterArcRange() {
        return this.waterArcRange;
    }

    public void setWaterArcRange(final double waterArcRange) {
        this.waterArcRange = waterArcRange;
    }

    public double getChainArcChance() {
        return this.chainArcChance;
    }

    public void setChainArcChance(final double chainArcChance) {
        this.chainArcChance = chainArcChance;
    }

    public double getStunChance() {
        return this.stunChance;
    }

    public void setStunChance(final double stunChance) {
        this.stunChance = stunChance;
    }

    public double getStunDuration() {
        return this.stunDuration;
    }

    public void setStunDuration(final long stunDuration) {
        this.stunDuration = stunDuration;
    }

    public double getMaxArcAngle() {
        return this.maxArcAngle;
    }

    public void setMaxArcAngle(final double maxArcAngle) {
        this.maxArcAngle = maxArcAngle;
    }

    public double getParticleRotation() {
        return this.particleRotation;
    }

    public void setParticleRotation(final double particleRotation) {
        this.particleRotation = particleRotation;
    }

    public State getState() {
        return this.state;
    }

    public void setState(final State state) {
        this.state = state;
    }

    public Location getOrigin() {
        return this.origin;
    }

    public void setOrigin(final Location origin) {
        this.origin = origin;
    }

    public Location getDestination() {
        return this.destination;
    }

    public void setDestination(final Location destination) {
        this.destination = destination;
    }

    public ArrayList<Entity> getAffectedEntities() {
        return this.affectedEntities;
    }

    public ArrayList<Arc> getArcs() {
        return this.arcs;
    }

    public ArrayList<BukkitRunnable> getTasks() {
        return this.tasks;
    }

    public static enum State {
        START, SILENT_PROJECTILE, STRIKE, MAINBOLT
    }

    public static enum SilentHitboxMode {
        /**
         * Resolve damage/water immediately, then animate the visible arc to the confirmed impact.
         */
        INSTANT,
        /**
         * Pre-trace the straight hitbox, but delay damage/water until the visible arc reaches the impact.
         */
        MATCH_VISUAL,
        /**
         * Move an invisible straight projectile first; when it impacts, draw the full visible arc at once.
         */
        DELAYED_INSTANT_ARC
    }

    /**
     * A deterministic, invisible projectile result. The visible lightning may
     * still jitter/branch, but all hit detection is resolved against this
     * straight path so random particles cannot cause missed hits or fake hits.
     */
    private class LightningHit {
        private final Location impactLocation;
        private final Entity entity;
        private final Block block;

        private LightningHit(final Location impactLocation, final Entity entity, final Block block) {
            this.impactLocation = impactLocation;
            this.entity = entity;
            this.block = block;
        }

        private Location getImpactLocation() {
            return this.impactLocation;
        }

        private Entity getEntity() {
            return this.entity;
        }

        private Block getBlock() {
            return this.block;
        }
    }

    /**
     * Represents a Lightning Arc Point particle animation. This basically just
     * holds a location and counts the amount of times that a particle has been
     * animated.
     **/
    public class AnimationLocation {
        private Location location;
        private int animationCounter;

        public AnimationLocation(final Location loc, final int animationCounter) {
            this.location = loc;
            this.animationCounter = animationCounter;
        }

        public int getAnimCounter() {
            return this.animationCounter;
        }

        public Location getLocation() {
            return this.location;
        }

        public void setLocation(final Location location) {
            this.location = location;
        }

        public void setAnimationCounter(final int animationCounter) {
            this.animationCounter = animationCounter;
        }
    }

    /**
     * An Arc represents a Lightning arc for the specific ability. These Arcs
     * contain a list of Particles that are used to display the entire arc. Arcs
     * can also generate a list of subarcs that chain off of their own instance.
     **/
    public class Arc {
        private final ArrayList<Location> points;
        private final ArrayList<AnimationLocation> animationLocations;
        private final ArrayList<LightningParticle> particles;
        private final ArrayList<Arc> subArcs;
        private int animationCounter;
        private int nextScheduledSegmentIndex;
        private Vector direction;

        public Arc(final Location startPoint, final Location endPoint) {
            this.points = new ArrayList<>();
            this.points.add(startPoint.clone());
            this.points.add(endPoint.clone());
            this.direction = Lightning.this.safeNormalize(GeneralMethods.getDirection(startPoint, endPoint), Lightning.this.player.getEyeLocation().getDirection());
            this.particles = new ArrayList<>();
            this.subArcs = new ArrayList<>();
            this.animationLocations = new ArrayList<>();
            this.animationCounter = 0;
            this.nextScheduledSegmentIndex = 0;
        }

        /**
         * Stops this Arc from further animating or doing damage.
         */
        public void cancel() {
            for (int i = 0; i < this.particles.size(); i++) {
                this.particles.get(i).cancel();
            }

            for (final Arc subArc : this.subArcs) {
                subArc.cancel();
            }
        }

        /**
         * Randomly generates subarcs off of this arc.
         *
         * @param chance The chance that an arc will be generated for each
         *               specific point in the arc. Note: if you generate a lot of
         *               points then chance will need to be lowered.
         * @param range  The length of each subarc.
         *
         **/
        public ArrayList<Arc> generateArcs(final double chance, final int minimumArcs, final double range, final double maxArcAngle) {
            final ArrayList<Arc> arcs = new ArrayList<>();
            if (this.animationLocations.size() < 3) {
                return arcs;
            }

            // Skip the exact start and end points. Branches look much more like real
            // lightning when they fork off the body of the bolt instead of growing out of
            // the caster's face or the impact block.
            for (int i = 1; i < this.animationLocations.size() - 1; i++) {
                if (ThreadLocalRandom.current().nextDouble() < chance) {
                    final Arc arc = this.createSubArc(this.animationLocations.get(i), range, maxArcAngle);
                    arcs.add(arc);
                    arcs.addAll(arc.generateArcs(chance / 3.0, 0, range / 2.5, maxArcAngle));
                }
            }

            while (arcs.size() < minimumArcs) {
                final int index = this.pickSubArcIndex();
                if (index < 0) {
                    break;
                }
                final Arc arc = this.createSubArc(this.animationLocations.get(index), range, maxArcAngle);
                arcs.add(arc);
            }

            return arcs;
        }

        private int pickSubArcIndex() {
            if (this.animationLocations.size() < 3) {
                return -1;
            }

            // Weighted toward the middle of the visible bolt, where natural leaders tend
            // to look best. Avoids ugly branch clusters at the origin/destination.
            final double center = (this.animationLocations.size() - 1) / 2.0;
            final double spread = Math.max(1.0, this.animationLocations.size() / 3.0);
            int index = (int) Math.round(center + ThreadLocalRandom.current().nextGaussian() * spread);
            index = Math.max(1, Math.min(this.animationLocations.size() - 2, index));
            return index;
        }

        private Arc createSubArc(final AnimationLocation animationLocation, final double range, final double maxArcAngle) {
            final Location loc = animationLocation.getLocation().clone();
            final Vector dir = Lightning.this.randomForkDirection(this.direction.clone(), maxArcAngle);
            final double safeRange = Math.max(0.0, range);
            final double parentLength = this.getDirectLength();
            final double lengthCap = Math.max(BRANCH_MIN_LENGTH, Math.min(safeRange, parentLength * BRANCH_MAX_PARENT_LENGTH_RATIO));
            final double minLength = Math.min(lengthCap, Math.max(BRANCH_MIN_LENGTH, lengthCap * 0.35));
            final double randRange = ThreadLocalRandom.current().nextDouble(minLength, lengthCap + 0.0001);
            final Location loc2 = loc.clone().add(dir.multiply(randRange));
            final Arc arc = new Arc(loc, loc2);

            this.subArcs.add(arc);
            arc.setAnimationCounter(animationLocation.getAnimCounter());
            arc.generatePoints(Math.max(2, POINT_GENERATION - 1));
            return arc;
        }

        private double getDirectLength() {
            if (this.points.size() < 2) {
                return 0.0;
            }
            final Location start = this.points.get(0);
            final Location end = this.points.get(this.points.size() - 1);
            if (!start.getWorld().equals(end.getWorld())) {
                return 0.0;
            }
            return start.distance(end);
        }

        /**
         * Runs a midpoint-displacement lightning generator. The main bolt stays
         * coherent from origin to destination, but every subdivision adds a sharp
         * perpendicular snap. The offset is strongest near the center and tapers near
         * both ends, which makes the bolt read as one connected lightning strike
         * instead of a loose cloud of random particles.
         *
         * @param times The amount of times that the arc will be split in half
         **/
        public void generatePoints(final int times) {
            final Location startPoint = this.points.get(0).clone();
            final Location endPoint = this.points.get(this.points.size() - 1).clone();
            final double totalLength = startPoint.getWorld().equals(endPoint.getWorld()) ? Math.max(0.001, startPoint.distance(endPoint)) : 0.001;
            final double jaggednessDegrees = Math.max(MAIN_BOLT_MIN_JAGGEDNESS_DEGREES, Math.min(MAIN_BOLT_MAX_JAGGEDNESS_DEGREES, Lightning.this.maxArcAngle + 4.0));
            final double jaggedness = Math.tan(Math.toRadians(jaggednessDegrees));

            for (int level = 0; level < times; level++) {
                for (int j = 0; j < this.points.size() - 1; j += 2) {
                    final Location loc1 = this.points.get(j);
                    final Location loc2 = this.points.get(j + 1);
                    double halfSegmentLength = 0;
                    if (loc1.getWorld().equals(loc2.getWorld())) {
                        halfSegmentLength = loc1.distance(loc2) / 2.0;
                    }

                    final Vector segment = GeneralMethods.getDirection(loc1, loc2);
                    final Vector dir = Lightning.this.safeNormalize(segment, this.direction);
                    final Location newLoc = loc1.clone().add(dir.clone().multiply(halfSegmentLength));

                    final double progress = Math.max(0.0, Math.min(1.0, startPoint.distance(newLoc) / totalLength));
                    final double endTaper = Math.sin(Math.PI * progress);
                    final double subdivisionFalloff = Math.pow(0.72, level);
                    final double jitterScale = Math.max(0.025, halfSegmentLength * jaggedness * endTaper * subdivisionFalloff);
                    final double jitter = ThreadLocalRandom.current().nextDouble(0.35, 1.0) * jitterScale;
                    newLoc.add(Lightning.this.randomPerpendicularOffset(dir, jitter));
                    this.points.add(j + 1, newLoc);
                }
            }
            double distanceTravelled = this.animationCounter * Lightning.this.speed;
            for (int i = 0; i < this.points.size(); i++) {
                if (i > 0 && this.points.get(i - 1).getWorld().equals(this.points.get(i).getWorld())) {
                    distanceTravelled += this.points.get(i - 1).distance(this.points.get(i));
                }
                this.animationLocations.add(new AnimationLocation(this.points.get(i), (int) Math.round(distanceTravelled / Lightning.this.speed)));
            }
        }

        public int getAnimationCounter() {
            return this.animationCounter;
        }

        public void setAnimationCounter(final int animationCounter) {
            this.animationCounter = animationCounter;
        }

        public int getNextScheduledSegmentIndex() {
            return this.nextScheduledSegmentIndex;
        }

        public void setNextScheduledSegmentIndex(final int nextScheduledSegmentIndex) {
            this.nextScheduledSegmentIndex = Math.max(0, nextScheduledSegmentIndex);
        }

        public Vector getDirection() {
            return this.direction;
        }

        public void setDirection(final Vector direction) {
            this.direction = direction;
        }

        public ArrayList<Location> getPoints() {
            return this.points;
        }

        public ArrayList<AnimationLocation> getAnimationLocations() {
            return this.animationLocations;
        }

        public ArrayList<LightningParticle> getParticles() {
            return this.particles;
        }

        public ArrayList<Arc> getSubArcs() {
            return this.subArcs;
        }
    }

    /**
     * A Runnable Particle that continuously displays itself until it reaches a
     * certain time limit.
     * <p>
     * These LightningParticles do the actual checking for player collision and
     * handle damaging any entities. These Runnables also check to see if they
     * reach water, in which case they will generate subarcs to branch out.
     **/
    public class LightningParticle extends BukkitRunnable {
        private boolean selfHitWater;
        private int count = 0;
        private int waterArcs;
        private Arc arc;
        private Location location;

        public LightningParticle(final Arc arc, final Location location, final boolean selfHitWater, final int waterArcs) {
            this.arc = arc;
            this.location = location;
            this.selfHitWater = selfHitWater;
            this.waterArcs = waterArcs;
            arc.particles.add(this);
        }

        /**
         * Cancels this Runnable
         **/
        @Override
        public void cancel() {
            super.cancel();
            Lightning.this.tasks.remove(this);
        }

        /**
         * Animates the Location, checks for water/player collision and also
         * deals with any chain subarcs.
         */
        @Override
        public void run() {
            playLightningbendingParticle(this.location, 0F, 0F, 0F);
            this.count++;
            if (this.count > 5) {
                this.cancel();
            } else if (this.count == 1) {
                if (ThreadLocalRandom.current().nextDouble() < .1) {
                    playLightningbendingSound(location);
                }

                Lightning.this.locations.add(this.location.getBlock().getLocation());

                // Collision is now handled by the deterministic silent projectile, not by
                // the random visual particles. Do not cancel the whole visual arc here; at
                // steep downward/upward angles a jittered particle can touch the caster's
                // nearby block and make the bolt appear to stall or never fire.
                if (!Lightning.this.isTransparentForLightning(Lightning.this.player, this.location.getBlock())) {
                    ParticleEffect.FLASH.display(this.location, 1);
                }
            }
        }

        public boolean isSelfHitWater() {
            return this.selfHitWater;
        }

        public void setSelfHitWater(final boolean selfHitWater) {
            this.selfHitWater = selfHitWater;
        }

        public int getCount() {
            return this.count;
        }

        public void setCount(final int count) {
            this.count = count;
        }

        public int getWaterArcs() {
            return this.waterArcs;
        }

        public void setWaterArcs(final int waterArcs) {
            this.waterArcs = waterArcs;
        }

        public Arc getArc() {
            return this.arc;
        }

        public void setArc(final Arc arc) {
            this.arc = arc;
        }

        public Location getLocation() {
            return this.location;
        }

        public void setLocation(final Location location) {
            this.location = location;
        }
    }
}
