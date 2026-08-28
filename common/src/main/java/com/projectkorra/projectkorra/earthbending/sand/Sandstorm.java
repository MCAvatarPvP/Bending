package com.projectkorra.projectkorra.earthbending.sand;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.SandAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay;
import com.projectkorra.projectkorra.platform.mc.entity.Display;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.entity.Projectile;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleUtil;
import com.projectkorra.projectkorra.util.TempPotionEffect;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A mobile, held storm that controls sight-lines and incoming projectiles. */
public class Sandstorm extends SandAbility {
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DURATION)
    private long duration;
    @Attribute(Attribute.SELECT_RANGE)
    private double sourceRange;
    @Attribute(Attribute.RADIUS)
    private double maximumRadius;
    @Attribute(Attribute.KNOCKBACK)
    private double push;
    @Attribute(Attribute.DAMAGE)
    private double shardDamage;
    private int growthTicks;
    private int effectInterval;
    private int blindnessTicks;
    private int shardHitCooldownTicks;
    private double shardCollisionRadius;
    private double projectileDeflection;
    private double centerDistance;
    private double controlSpeed;
    private double controlAcceleration;
    private double controlDrag;
    private double aimSmoothing;
    private double stormHeight;
    private double swirl;
    private long prepareTimeout;
    private int visualRibbons;
    private int visualRibbonPoints;
    private int visualCloudLobes;
    private int visualGroundTendrils;
    private int visualShardCount;
    private float visualMinimumShardScale;
    private float visualMaximumShardScale;
    private double visualShardOrbitSpeed;
    private int soundIntervalTicks;
    @Attribute(Attribute.SPEED)
    private double launchSpeed;
    @Attribute(Attribute.RANGE)
    private double launchRange;
    @Attribute(Attribute.DAMAGE)
    private double launchDamage;
    @Attribute(Attribute.KNOCKBACK)
    private double launchKnockback;
    private double launchImpactRadius;
    private int launchMaximumStepHeight;

    private SandSource source;
    private boolean active;
    private boolean launched;
    private long activeSince;
    private long launchSince;
    private double radius;
    private double launchTravelled;
    private Location center;
    private Vector centerVelocity = new Vector();
    private Vector stormDirection;
    private Vector launchDirection;
    private final Set<UUID> deflectedProjectiles = new HashSet<>();
    private final Set<UUID> launchHit = new HashSet<>();
    private final Map<UUID, Long> shardHitTicks = new HashMap<>();
    private final List<StormShard> shards = new ArrayList<>();

    public Sandstorm(final Player player) {
        super(player);
        if (player == null || hasAbility(player, Sandstorm.class)) return;
        this.loadFields();
        if (!this.bPlayer.canBend(this) || this.bPlayer.isOnCooldown(this)) return;
        this.source = SandSource.select(this, player, this.sourceRange);
        if (this.source == null) return;
        this.start();
    }

    public static boolean activate(final Player player) {
        final Sandstorm storm = getAbility(player, Sandstorm.class);
        if (storm == null) return false;
        if (!storm.active) return storm.begin();
        return storm.launchStorm();
    }

    private void loadFields() {
        final String path = "Abilities.Earth.Sandstorm.";
        this.cooldown = getConfig().getLong(path + "Cooldown", 6000L);
        this.duration = getConfig().getLong(path + "Duration", 5000L);
        this.sourceRange = getConfig().getDouble(path + "SourceRange", 8.0);
        this.maximumRadius = Math.max(0.5, getConfig().getDouble(path + "Radius", 3.0));
        this.growthTicks = Math.max(1, getConfig().getInt(path + "GrowthTicks", 10));
        this.push = Math.max(0.0, getConfig().getDouble(path + "Push", 0.22));
        this.shardDamage = Math.max(0.0, getConfig().getDouble(path + "ShardDamage", 0.5));
        this.effectInterval = Math.max(1, getConfig().getInt(path + "EffectInterval", 4));
        this.blindnessTicks = Math.max(1, getConfig().getInt(path + "BlindnessTicks", 30));
        this.shardHitCooldownTicks = Math.max(1,
                getConfig().getInt(path + "ShardHitCooldownTicks", 10));
        this.shardCollisionRadius = Math.max(0.1,
                getConfig().getDouble(path + "ShardCollisionRadius", 0.38));
        this.projectileDeflection = Math.max(0.0,
                getConfig().getDouble(path + "ProjectileDeflection", 0.8));
        this.centerDistance = Math.max(1.0,
                getConfig().getDouble(path + "CenterDistance", 4.5));
        this.controlSpeed = Math.max(0.05,
                getConfig().getDouble(path + "ControlSpeed", 0.18));
        this.controlAcceleration = Math.max(0.001,
                getConfig().getDouble(path + "ControlAcceleration", 0.024));
        this.controlDrag = Math.max(0.0, Math.min(0.99,
                getConfig().getDouble(path + "ControlDrag", 0.88)));
        this.aimSmoothing = Math.max(0.01, Math.min(1.0,
                getConfig().getDouble(path + "AimSmoothing", 0.1)));
        this.stormHeight = Math.max(1.5,
                getConfig().getDouble(path + "Height", 3.2));
        this.swirl = Math.max(0.0,
                getConfig().getDouble(path + "Swirl", 0.16));
        this.prepareTimeout = getConfig().getLong(path + "PrepareTimeout", 3000L);
        this.visualRibbons = Math.max(2, Math.min(5,
                getConfig().getInt(path + "Visuals.Ribbons", 3)));
        this.visualRibbonPoints = Math.max(12, Math.min(30,
                getConfig().getInt(path + "Visuals.RibbonPoints", 18)));
        this.visualCloudLobes = Math.max(3, Math.min(10,
                getConfig().getInt(path + "Visuals.CloudLobes", 6)));
        this.visualGroundTendrils = Math.max(2, Math.min(8,
                getConfig().getInt(path + "Visuals.GroundTendrils", 5)));
        this.visualShardCount = Math.max(6, Math.min(24,
                getConfig().getInt(path + "Visuals.Shards", 14)));
        this.visualMinimumShardScale = (float) Math.max(0.08,
                getConfig().getDouble(path + "Visuals.MinimumShardScale", 0.12));
        this.visualMaximumShardScale = (float) Math.max(this.visualMinimumShardScale,
                getConfig().getDouble(path + "Visuals.MaximumShardScale", 0.28));
        this.visualShardOrbitSpeed = Math.max(0.04,
                getConfig().getDouble(path + "Visuals.ShardOrbitSpeed", 0.2));
        this.soundIntervalTicks = Math.max(5,
                getConfig().getInt(path + "Sound.IntervalTicks", 11));
        this.launchSpeed = Math.max(0.1,
                getConfig().getDouble(path + "Launch.Speed", 0.65));
        this.launchRange = Math.max(1.0,
                getConfig().getDouble(path + "Launch.Range", 14.0));
        this.launchDamage = Math.max(0.0,
                getConfig().getDouble(path + "Launch.Damage", 1.5));
        this.launchKnockback = Math.max(0.0,
                getConfig().getDouble(path + "Launch.Knockback", 0.65));
        this.launchImpactRadius = Math.max(0.25,
                getConfig().getDouble(path + "Launch.ImpactRadius", 1.05));
        this.launchMaximumStepHeight = Math.max(0,
                getConfig().getInt(path + "Launch.MaximumStepHeight", 1));
    }

    private boolean begin() {
        if (this.active || this.source == null || !this.player.isSneaking()
                || !this.source.reserve(this)) return false;
        this.active = true;
        this.activeSince = System.currentTimeMillis();
        this.center = this.source.block().getLocation().clone().add(0.5, 0.12, 0.5);
        this.stormDirection = this.horizontalFacing();
        this.centerVelocity = new Vector();
        this.createStormShards();
        this.displaySandBurst(this.source.block().getLocation().clone().add(0.5, 0.8, 0.5),
                26, 0.55, 0.35, 0.55, 0.045, this.isRedSand());
        this.playSandSound(this.source.block().getLocation(), Sound.BLOCK_SAND_BREAK, 1.2F, 0.58F);
        this.playSandSound(this.player.getLocation(), Sound.ENTITY_BREEZE_CHARGE, 0.75F, 0.62F);
        return true;
    }

    private boolean launchStorm() {
        if (!this.active || this.launched || !this.player.isSneaking() || this.center == null) {
            return false;
        }
        this.launchDirection = this.horizontalFacing();
        this.stormDirection = this.launchDirection.clone();
        this.centerVelocity = new Vector();
        this.launchTravelled = 0.0;
        this.launched = true;
        this.launchSince = System.currentTimeMillis();
        this.displaySandBurst(this.center.clone().add(0, this.stormHeight * 0.42, 0),
                22, this.radius * 0.34, 0.48, this.radius * 0.34, 0.04, this.isRedSand());
        this.playSandSound(this.center, Sound.ENTITY_BREEZE_WIND_BURST, 1.0F, 0.56F);
        this.playSandSound(this.center, Sound.BLOCK_SAND_BREAK, 0.92F, 0.62F);
        return true;
    }

    @Override
    public void progress() {
        if (this.player.isDead() || !this.player.isOnline() || this.source == null
                || !this.bPlayer.canBendIgnoreCooldowns(this) || !this.bPlayer.canBind(this)) {
            this.remove();
            return;
        }
        if (!this.active) {
            if (!this.player.isSneaking()
                    || System.currentTimeMillis() > this.getStartTime() + this.prepareTimeout) {
                this.remove();
                return;
            }
            this.renderSelectedSource();
            return;
        }
        final long durationStart = this.launched ? this.launchSince : this.activeSince;
        if (this.duration > 0 && System.currentTimeMillis() > durationStart + this.duration) {
            this.remove();
            return;
        }

        final long activeTicks = Math.max(0L, (System.currentTimeMillis() - this.activeSince) / 50L);
        this.radius = this.maximumRadius * Math.min(1.0, activeTicks / (double) this.growthTicks);
        if (this.launched) {
            if (!this.advanceLaunchedStorm()) {
                this.remove();
                return;
            }
        } else if (this.player.isSneaking()) {
            this.updateStormCenter();
        } else {
            this.centerVelocity = new Vector();
        }
        if (this.center == null
                || GeneralMethods.isRegionProtectedFromBuild(this, this.center)) {
            this.remove();
            return;
        }
        this.renderStorm();
        this.updateStormShards();
        this.damageWithOrbitingShards();
        if (this.launched) this.damageWithLaunchedCore();
        this.deflectProjectiles();
        if (this.getRunningTicks() % this.effectInterval == 0L) this.affectEntities();
        if (this.getRunningTicks() % this.soundIntervalTicks == 0L) {
            this.playSandSound(this.center, Sound.ENTITY_BREEZE_IDLE_GROUND, 0.62F, 0.52F);
            this.playSandSound(this.center, Sound.BLOCK_SAND_BREAK, 0.42F, 0.78F);
        }
    }

    private void updateStormCenter() {
        final Vector facing = this.horizontalFacing();
        if (this.stormDirection == null || this.stormDirection.lengthSquared() <= 1.0E-9) {
            this.stormDirection = facing;
        } else {
            final double currentAngle = Math.atan2(this.stormDirection.getZ(),
                    this.stormDirection.getX());
            final double targetAngle = Math.atan2(facing.getZ(), facing.getX());
            final double difference = Math.atan2(Math.sin(targetAngle - currentAngle),
                    Math.cos(targetAngle - currentAngle));
            final double smoothedAngle = currentAngle + difference * this.aimSmoothing;
            this.stormDirection = new Vector(Math.cos(smoothedAngle), 0, Math.sin(smoothedAngle));
        }
        final Location target = this.player.getLocation().clone()
                .add(this.stormDirection.clone().multiply(this.centerDistance));
        target.setY(this.player.getLocation().getY() + 0.08);
        if (this.center == null || this.center.getWorld() == null
                || !this.center.getWorld().equals(target.getWorld())) {
            this.center = target;
            this.centerVelocity = new Vector();
            return;
        }

        final Vector displacement = target.toVector().subtract(this.center.toVector());
        final Vector desiredVelocity = displacement.clone();
        if (desiredVelocity.lengthSquared() > this.controlSpeed * this.controlSpeed) {
            desiredVelocity.normalize().multiply(this.controlSpeed);
        }
        this.centerVelocity.multiply(this.controlDrag);
        final Vector steering = desiredVelocity.subtract(this.centerVelocity.clone());
        if (steering.lengthSquared() > this.controlAcceleration * this.controlAcceleration) {
            steering.normalize().multiply(this.controlAcceleration);
        }
        this.centerVelocity.add(steering);
        if (this.centerVelocity.lengthSquared() > this.controlSpeed * this.controlSpeed) {
            this.centerVelocity.normalize().multiply(this.controlSpeed);
        }
        if (this.centerVelocity.lengthSquared() > displacement.lengthSquared()) {
            this.centerVelocity = displacement;
        }
        this.center.add(this.centerVelocity);
    }

    private Vector horizontalFacing() {
        Vector facing = this.player.getEyeLocation().getDirection().clone().setY(0);
        if (facing.lengthSquared() <= 1.0E-9) {
            facing = this.player.getLocation().getDirection().clone().setY(0);
        }
        if (facing.lengthSquared() <= 1.0E-9) facing = new Vector(0, 0, 1);
        return facing.normalize();
    }

    private boolean advanceLaunchedStorm() {
        if (this.center == null || this.launchDirection == null) return false;
        double remaining = Math.min(this.launchSpeed, this.launchRange - this.launchTravelled);
        if (remaining <= 1.0E-9) return false;
        final double maximumStep = Math.min(0.25, this.launchSpeed);
        while (remaining > 1.0E-9) {
            final double distance = Math.min(maximumStep, remaining);
            final Location desired = this.center.clone()
                    .add(this.launchDirection.clone().multiply(distance));
            final Location grounded = this.findLaunchGround(desired);
            if (grounded == null || GeneralMethods.isRegionProtectedFromBuild(this, grounded)) {
                return false;
            }
            this.center = grounded;
            this.launchTravelled += distance;
            remaining -= distance;
            if (this.launchTravelled >= this.launchRange - 1.0E-9) return false;
        }
        return true;
    }

    private Location findLaunchGround(final Location desired) {
        if (desired == null || desired.getWorld() == null || this.center == null) return null;
        final int currentGroundY = (int) Math.floor(this.center.getY()) - 1;
        for (int delta = this.launchMaximumStepHeight;
             delta >= -this.launchMaximumStepHeight - 1; delta--) {
            final Block ground = desired.getWorld().getBlockAt(
                    desired.getBlockX(), currentGroundY + delta, desired.getBlockZ());
            if (!GeneralMethods.isSolid(ground) || ground.isLiquid()) continue;
            final Location next = new Location(desired.getWorld(), desired.getX(),
                    ground.getY() + 1.08, desired.getZ());
            if (GeneralMethods.isPassable(next.getBlock())
                    && GeneralMethods.isPassable(next.clone().add(0, 1, 0).getBlock())) {
                return next;
            }
        }
        return null;
    }

    private void renderSelectedSource() {
        final Location center = this.source.block().getLocation().clone().add(0.5, 0.65, 0.5);
        final double phase = this.getRunningTicks() * 0.38;
        for (int index = 0; index < 16; index++) {
            final double angle = phase + index * Math.PI * 2.0 / 16.0;
            final double radius = 0.22 + (index % 4) * 0.09;
            final double y = (index % 6) * 0.11;
            this.displayFineSand(center.clone().add(Math.cos(angle) * radius, y,
                    Math.sin(angle) * radius), 1, 0.02, 0.025, 0.02, 0.0, this.isRedSand());
        }
        if (this.getRunningTicks() % 10L == 0L) {
            this.playSandSound(center, Sound.BLOCK_SAND_BREAK, 0.28F, 1.32F);
        }
    }

    private void renderStorm() {
        final Location origin = this.center.clone();
        final double phase = this.getRunningTicks() * 0.16;
        Vector forward = this.stormDirection == null
                ? this.horizontalFacing() : this.stormDirection.clone();
        if (forward.lengthSquared() <= 1.0E-9) forward = new Vector(0, 0, 1);
        forward.normalize().setY(0);
        final Vector right = new Vector(-forward.getZ(), 0, forward.getX());

        this.renderDustDevilSpirals(origin, forward, right, phase);
        this.renderRisingDustWisps(origin, forward, right, phase);
        this.renderDustDevilCrown(origin, forward, right, phase);
        this.renderGroundSkirtWisps(origin, forward, right, phase);
        if (this.getRunningTicks() % 6L == 0L) {
            final double angle = phase * 1.7;
            final double gustRadius = this.radius * this.dustDevilRadius(0.58) * 0.38;
            final Location gust = origin.clone()
                    .add(forward.clone().multiply(Math.sin(angle) * gustRadius))
                    .add(right.clone().multiply(Math.cos(angle) * gustRadius))
                    .add(0, this.stormHeight * 0.58, 0);
            ParticleUtil.spawn(Particle.SMALL_GUST, gust, 2,
                    this.radius * 0.16, 0.24, this.radius * 0.16, 0.0);
            this.displaySandBurst(gust, 7, this.radius * 0.25, 0.35,
                    this.radius * 0.25, 0.02, this.isRedSand());
        }
    }

    private void renderDustDevilSpirals(final Location origin, final Vector forward,
                                        final Vector right, final double phase) {
        for (int ribbon = 0; ribbon < this.visualRibbons; ribbon++) {
            for (int index = 0; index < this.visualRibbonPoints; index++) {
                final double progress = index / (double) (this.visualRibbonPoints - 1);
                final double angle = phase * (1.28 + ribbon * 0.1)
                        + progress * Math.PI * (4.1 + ribbon * 0.34) + ribbon * 2.17;
                final double breathing = 0.88 + 0.12 * Math.sin(phase * 0.72
                        + progress * Math.PI * 5.0 + ribbon * 1.4);
                final double radial = this.radius * this.dustDevilRadius(progress) * breathing;
                final double along = Math.sin(angle) * radial * 0.82;
                final double side = Math.cos(angle) * radial;
                final double y = 0.05 + progress * this.stormHeight
                        + Math.sin(angle * 1.55) * (0.035 + progress * 0.07);
                final Location point = origin.clone()
                        .add(forward.clone().multiply(along))
                        .add(right.clone().multiply(side)).add(0, y, 0);
                this.displayFineSand(point, 1, 0.04, 0.055, 0.04, 0.0, this.isRedSand());
                if ((index + ribbon * 2) % 6 == 0) {
                    this.displaySandBurst(point, 2, 0.11, 0.14, 0.11, 0.009, this.isRedSand());
                }
            }
        }
    }

    private void renderRisingDustWisps(final Location origin, final Vector forward,
                                       final Vector right, final double phase) {
        final double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int lobe = 0; lobe < this.visualCloudLobes; lobe++) {
            final double progress = ((lobe + 0.5) / this.visualCloudLobes
                    + this.getRunningTicks() * (0.0035 + (lobe % 2) * 0.0008)) % 1.0;
            final double angle = phase * (0.7 + (lobe % 2) * 0.1)
                    + lobe * goldenAngle + progress * Math.PI * 2.2;
            final double orbit = this.radius * this.dustDevilRadius(progress)
                    * (0.42 + (lobe % 3) * 0.1);
            final double side = Math.cos(angle) * orbit;
            final double along = Math.sin(angle) * orbit * 0.82;
            final double y = 0.12 + progress * this.stormHeight
                    + Math.sin(phase * 1.1 + lobe) * 0.08;
            final Location cloud = origin.clone()
                    .add(forward.clone().multiply(along))
                    .add(right.clone().multiply(side)).add(0, y, 0);
            this.displaySandBurst(cloud, 5, this.radius * 0.13, 0.2,
                    this.radius * 0.13, 0.012, this.isRedSand());
            if ((lobe & 1) == 0) {
                ParticleUtil.spawn(Particle.ASH, cloud, 2,
                        this.radius * 0.1, 0.16, this.radius * 0.1, 0.005);
            }
        }
    }

    private void renderDustDevilCrown(final Location origin, final Vector forward,
                                      final Vector right, final double phase) {
        final int arms = Math.max(3, this.visualRibbons);
        final double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int arm = 0; arm < arms; arm++) {
            for (int index = 0; index < 6; index++) {
                final double progress = index / 5.0;
                final double angle = phase * 1.05 + arm * goldenAngle + progress * 0.9;
                final double radial = this.radius * (0.48 + progress * 0.46);
                final double y = this.stormHeight * (0.82 + progress * 0.16)
                        + Math.sin(angle * 2.0) * 0.08;
                final Location point = origin.clone()
                        .add(forward.clone().multiply(Math.sin(angle) * radial * 0.82))
                        .add(right.clone().multiply(Math.cos(angle) * radial)).add(0, y, 0);
                this.displayFineSand(point, 1, 0.065, 0.06, 0.065, 0.0, this.isRedSand());
                if (index == 5) {
                    ParticleUtil.spawn(Particle.ASH, point, 2, 0.12, 0.08, 0.12, 0.008);
                }
            }
        }
    }

    private void renderGroundSkirtWisps(final Location origin, final Vector forward,
                                        final Vector right, final double phase) {
        final int points = 7;
        final double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int tendril = 0; tendril < this.visualGroundTendrils; tendril++) {
            for (int index = 0; index < points; index++) {
                final double progress = index / (double) (points - 1);
                final double angle = -phase * 1.22 + tendril * goldenAngle + progress * 1.18;
                final double radial = this.radius * (0.1 + progress * 0.58);
                final double y = 0.035 + Math.sin(progress * Math.PI) * 0.075;
                final Location point = origin.clone()
                        .add(forward.clone().multiply(Math.sin(angle) * radial * 0.84))
                        .add(right.clone().multiply(Math.cos(angle) * radial)).add(0, y, 0);
                this.displayFineSand(point, 1, 0.06, 0.025, 0.06, 0.0, this.isRedSand());
            }
        }
    }

    private double dustDevilRadius(final double heightProgress) {
        final double clamped = Math.max(0.0, Math.min(1.0, heightProgress));
        return 0.16 + Math.pow(clamped, 0.72) * 0.74;
    }

    private void createStormShards() {
        if (this.center == null || this.center.getWorld() == null || !this.shards.isEmpty()) return;
        final Material sand = this.isRedSand() ? Material.RED_SAND : Material.SAND;
        final Material sandstone = this.isRedSand() ? Material.RED_SANDSTONE : Material.SANDSTONE;
        for (int index = 0; index < this.visualShardCount; index++) {
            final BlockDisplay display = this.center.getWorld().spawn(this.center, BlockDisplay.class);
            display.setBlock((index % 3 == 0 ? sandstone : sand).createBlockData());
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setGravity(false);
            display.setSilent(true);
            display.setBillboard(Display.Billboard.FIXED);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setShadowRadius(0.0F);
            display.setShadowStrength(0.0F);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(2);
            display.setTeleportDuration(2);
            display.setViewRange(32.0F);

            final StormShard shard = new StormShard(display, index);
            this.initializeStormShard(shard);
            display.setTransformation(this.stormShardTransformation(shard));
            this.shards.add(shard);
        }
    }

    private void initializeStormShard(final StormShard shard) {
        shard.angle = sample(shard.index, 1) * Math.PI * 2.0;
        shard.verticalPhase = sample(shard.index, 2) * Math.PI * 2.0;
        shard.orbitRadiusFactor = 0.32 + sample(shard.index, 3) * 0.58;
        shard.orbitSpeedFactor = 0.72 + sample(shard.index, 4) * 0.56;
        shard.verticalSpeedFactor = 0.48 + sample(shard.index, 5) * 0.42;
        shard.rotationX = (float) (sample(shard.index, 6) * Math.PI * 2.0);
        shard.rotationY = (float) (sample(shard.index, 7) * Math.PI * 2.0);
        shard.rotationZ = (float) (sample(shard.index, 8) * Math.PI * 2.0);
        shard.angularX = (float) (0.06 + sample(shard.index, 9) * 0.16);
        shard.angularY = (float) ((sample(shard.index, 10) - 0.5) * 0.34);
        shard.angularZ = (float) ((sample(shard.index, 11) - 0.5) * 0.28);
        final float scale = (float) (this.visualMinimumShardScale
                + sample(shard.index, 12)
                * (this.visualMaximumShardScale - this.visualMinimumShardScale));
        shard.scaleX = scale * (float) (0.65 + sample(shard.index, 13) * 0.7);
        shard.scaleY = scale * (float) (0.45 + sample(shard.index, 14) * 0.55);
        shard.scaleZ = scale * (float) (0.6 + sample(shard.index, 15) * 0.65);
    }

    private void updateStormShards() {
        if (this.center == null) return;
        if (this.shards.isEmpty()) this.createStormShards();
        Vector forward = this.stormDirection == null
                ? this.horizontalFacing() : this.stormDirection.clone().setY(0);
        if (forward.lengthSquared() <= 1.0E-9) forward = new Vector(0, 0, 1);
        forward.normalize();
        final Vector right = new Vector(-forward.getZ(), 0, forward.getX());

        for (final StormShard shard : this.shards) {
            if (shard.display == null || !shard.display.isValid()) continue;
            shard.angle += this.visualShardOrbitSpeed * shard.orbitSpeedFactor;
            shard.verticalPhase += this.visualShardOrbitSpeed * shard.verticalSpeedFactor;
            shard.rotationX += shard.angularX;
            shard.rotationY += shard.angularY;
            shard.rotationZ += shard.angularZ;

            final double lift = 0.5 + 0.5 * Math.sin(shard.verticalPhase);
            final double orbit = this.radius * this.dustDevilRadius(lift)
                    * (0.65 + shard.orbitRadiusFactor * 0.38);
            final double side = Math.cos(shard.angle) * orbit;
            final double along = Math.sin(shard.angle) * orbit * 0.76
                    + Math.cos(shard.verticalPhase * 0.7) * this.radius * 0.12;
            final double y = 0.16 + lift * this.stormHeight * 0.76;
            shard.location = this.center.clone()
                    .add(forward.clone().multiply(along))
                    .add(right.clone().multiply(side)).add(0, y, 0);
            shard.location.setYaw(0.0F);
            shard.location.setPitch(0.0F);
            shard.display.teleport(shard.location);
            shard.display.setTransformation(this.stormShardTransformation(shard));
        }
    }

    private Transformation stormShardTransformation(final StormShard shard) {
        final Quaternionf rotation = new Quaternionf().rotateX(shard.rotationX)
                .rotateY(shard.rotationY).rotateZ(shard.rotationZ);
        final Vector3f translation = new Vector3f(
                shard.scaleX * 0.5F, shard.scaleY * 0.5F, shard.scaleZ * 0.5F);
        rotation.transform(translation);
        translation.negate();
        return new Transformation(translation, rotation,
                new Vector3f(shard.scaleX, shard.scaleY, shard.scaleZ), new Quaternionf());
    }

    private void damageWithOrbitingShards() {
        if (this.center == null || this.shardDamage <= 0.0 || this.radius < 0.5) return;
        final long tick = this.getRunningTicks();
        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(
                this.center, this.radius + this.shardCollisionRadius + 0.5)) {
            if (!(entity instanceof LivingEntity) || entity.equals(this.player)
                    || GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) continue;
            if (entity instanceof Player target && Commands.invincible.contains(target.getName())) continue;
            final long previousHit = this.shardHitTicks.getOrDefault(entity.getUniqueId(), Long.MIN_VALUE);
            if (previousHit != Long.MIN_VALUE && tick - previousHit < this.shardHitCooldownTicks) continue;

            final BoundingBox hitbox = entity.getBoundingBox().expand(this.shardCollisionRadius);
            for (final StormShard shard : this.shards) {
                if (shard.location == null || shard.display == null || !shard.display.isValid()) continue;
                final Vector point = shard.location.toVector();
                if (!hitbox.overlaps(point, point)) continue;
                this.shardHitTicks.put(entity.getUniqueId(), tick);
                DamageHandler.damageEntity(entity, this.shardDamage, this);
                this.displaySandBurst(shard.location, 9, 0.2, 0.24,
                        0.2, 0.035, this.isRedSand());
                this.playSandSound(shard.location, Sound.BLOCK_SAND_BREAK, 0.72F, 0.84F);
                shard.angle += 0.24;
                break;
            }
        }
    }

    private void damageWithLaunchedCore() {
        if (this.center == null || this.launchDirection == null || this.launchDamage <= 0.0) return;
        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(
                this.center.clone().add(0, 0.85, 0), this.launchImpactRadius)) {
            if (!(entity instanceof LivingEntity) || entity.equals(this.player)
                    || GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) continue;
            if (entity instanceof Player target && Commands.invincible.contains(target.getName())) continue;
            if (!this.launchHit.add(entity.getUniqueId())) continue;
            DamageHandler.damageEntity(entity, this.launchDamage, this);
            final Vector impact = this.launchDirection.clone().multiply(this.launchKnockback);
            impact.setY(0.16);
            GeneralMethods.setVelocity(this, entity, entity.getVelocity().clone().add(impact));
            this.displaySandBurst(entity.getLocation().clone().add(0, 0.75, 0),
                    16, 0.36, 0.46, 0.36, 0.055, this.isRedSand());
            this.playSandSound(entity.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 0.78F, 0.72F);
            this.playSandSound(entity.getLocation(), Sound.BLOCK_SAND_BREAK, 0.86F, 0.52F);
        }
    }

    private void destroyStormShards() {
        for (final StormShard shard : this.shards) {
            if (shard.display != null && shard.display.isValid()) shard.display.remove();
        }
        this.shards.clear();
        this.shardHitTicks.clear();
        this.launchHit.clear();
    }

    private static double sample(final int index, final int salt) {
        long value = index * 0x9E3779B97F4A7C15L
                ^ salt * 0x165667B19E3779F9L;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }

    private void affectEntities() {
        final Location origin = this.center;
        if (origin == null) return;
        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(origin, this.radius)) {
            if (!(entity instanceof LivingEntity living) || entity.equals(this.player)
                    || GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) continue;
            if (entity instanceof Player target && Commands.invincible.contains(target.getName())) continue;
            final Vector outward = entity.getLocation().toVector().subtract(origin.toVector()).setY(0);
            if (outward.lengthSquared() > 1.0E-9) {
                outward.normalize();
                final Vector tangent = new Vector(-outward.getZ(), 0, outward.getX());
                final Vector impulse = outward.multiply(this.push).add(tangent.multiply(this.swirl));
                impulse.setY(0.04);
                GeneralMethods.setVelocity(this, entity, entity.getVelocity().clone().add(impulse));
            }
            new TempPotionEffect(living,
                    new PotionEffect(PotionEffectType.BLINDNESS, this.blindnessTicks, 0));
        }
    }

    private void deflectProjectiles() {
        final Location origin = this.center;
        if (origin == null) return;
        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(origin, this.radius + 0.5)) {
            if (!(entity instanceof Projectile) || !this.deflectedProjectiles.add(entity.getUniqueId())
                    || GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) continue;
            final Vector outward = entity.getLocation().toVector().subtract(origin.toVector()).setY(0);
            if (outward.lengthSquared() <= 1.0E-9) continue;
            outward.normalize();
            Vector tangent = new Vector(-outward.getZ(), 0, outward.getX());
            final Vector old = entity.getVelocity().clone();
            if (tangent.dot(old) < 0) tangent.multiply(-1);
            final double speed = Math.max(0.25, old.length() * this.projectileDeflection);
            final Vector redirected = tangent.multiply(0.72).add(outward.multiply(0.28))
                    .normalize().multiply(speed);
            redirected.setY(old.getY() * 0.6);
            GeneralMethods.setVelocity(this, entity, redirected);
            this.displaySandBurst(entity.getLocation(), 12, 0.28, 0.28, 0.28,
                    0.035, this.isRedSand());
            this.playSandSound(entity.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 0.7F, 1.28F);
            this.playSandSound(entity.getLocation(), Sound.BLOCK_SAND_BREAK, 0.55F, 1.12F);
        }
    }

    private boolean isRedSand() {
        return this.source.visualMaterial().toString().startsWith("RED_");
    }

    @Override
    public void handleCollision(final Collision collision) {
        super.handleCollision(collision);
    }

    @Override
    public void remove() {
        super.remove();
        if (!this.isRemoved()) return;
        this.destroyStormShards();
        if (this.source != null) this.source.restore();
        if (this.active) {
            this.bPlayer.addCooldown(this);
            if (this.center != null) {
                this.displaySandBurst(this.center.clone().add(0, this.stormHeight * 0.35, 0),
                        18, this.radius * 0.35, 0.5, this.radius * 0.35, 0.04, this.isRedSand());
                this.playSandSound(this.center, Sound.ENTITY_BREEZE_LAND, 0.65F, 0.62F);
            }
        }
    }

    @Override public String getName() { return "Sandstorm"; }
    @Override public Location getLocation() { return this.center; }
    @Override public long getCooldown() { return this.cooldown; }
    @Override public boolean isSneakAbility() { return true; }
    @Override public boolean isHarmlessAbility() { return false; }
    @Override public double getCollisionRadius() { return Math.max(0.5, this.radius); }

    public boolean isActive() { return this.active; }
    public boolean isLaunched() { return this.launched; }
    public double getRadius() { return this.radius; }
    public double getLaunchTravelled() { return this.launchTravelled; }

    private static final class StormShard {
        private final BlockDisplay display;
        private final int index;
        private Location location;
        private double angle;
        private double verticalPhase;
        private double orbitRadiusFactor;
        private double orbitSpeedFactor;
        private double verticalSpeedFactor;
        private float rotationX;
        private float rotationY;
        private float rotationZ;
        private float angularX;
        private float angularY;
        private float angularZ;
        private float scaleX;
        private float scaleY;
        private float scaleZ;

        private StormShard(final BlockDisplay display, final int index) {
            this.display = display;
            this.index = index;
        }
    }
}
