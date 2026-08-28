package com.projectkorra.projectkorra.earthbending.sand;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.SandAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay;
import com.projectkorra.projectkorra.platform.mc.entity.Display;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import com.projectkorra.projectkorra.util.DamageHandler;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A broad, ground-following wave of loose sand that carries and releases targets. */
public class SandSurge extends SandAbility {
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.SELECT_RANGE)
    private double sourceRange;
    @Attribute(Attribute.RANGE)
    private double range;
    private double width;
    @Attribute(Attribute.SPEED)
    private double speed;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    private int carryTicks;
    @Attribute(Attribute.KNOCKBACK)
    private double knockback;
    private double knockup;
    private int maximumStepHeight;
    private long prepareTimeout;
    private int visualShardCount;
    private int visualShardRows;
    private float visualMinimumShardScale;
    private float visualMaximumShardScale;
    private double visualShardGravity;
    private double visualShardBounce;
    private int soundIntervalTicks;

    private SandSource source;
    private boolean moving;
    private Location location;
    private Vector direction;
    private double travelled;
    private final Set<UUID> hit = new HashSet<>();
    private final Map<UUID, CarriedTarget> carried = new HashMap<>();
    private final List<SandShard> shards = new ArrayList<>();

    public SandSurge(final Player player) {
        super(player);
        if (player == null || hasAbility(player, SandSurge.class)) return;
        this.loadFields();
        if (!this.bPlayer.canBend(this) || this.bPlayer.isOnCooldown(this)) return;
        this.source = SandSource.select(this, player, this.sourceRange);
        if (this.source == null) return;
        this.start();
    }

    public static boolean launch(final Player player) {
        final SandSurge surge = getAbility(player, SandSurge.class);
        return surge != null && surge.beginMoving();
    }

    private void loadFields() {
        final String path = "Abilities.Earth.SandSurge.";
        this.cooldown = getConfig().getLong(path + "Cooldown", 5000L);
        this.sourceRange = getConfig().getDouble(path + "SourceRange", 8.0);
        this.prepareTimeout = getConfig().getLong(path + "PrepareTimeout", 3000L);
        this.range = Math.max(1.0, getConfig().getDouble(path + "Range", 16.0));
        this.width = Math.max(1.0, getConfig().getDouble(path + "Width", 4.0));
        this.speed = Math.max(0.1, getConfig().getDouble(path + "Speed", 0.8));
        this.damage = Math.max(0.0, getConfig().getDouble(path + "Damage", 2.0));
        this.carryTicks = Math.max(0, getConfig().getInt(path + "CarryTicks", 10));
        this.knockback = Math.max(0.0, getConfig().getDouble(path + "Knockback", 0.8));
        this.knockup = Math.max(0.0, getConfig().getDouble(path + "Knockup", 0.3));
        this.maximumStepHeight = Math.max(0, getConfig().getInt(path + "MaximumStepHeight", 1));
        this.visualShardCount = Math.max(8, Math.min(48,
                getConfig().getInt(path + "Visuals.Shards", 32)));
        this.visualShardRows = Math.max(2, Math.min(6,
                getConfig().getInt(path + "Visuals.Rows", 4)));
        this.visualMinimumShardScale = (float) Math.max(0.08,
                getConfig().getDouble(path + "Visuals.MinimumShardScale", 0.16));
        this.visualMaximumShardScale = (float) Math.max(this.visualMinimumShardScale,
                getConfig().getDouble(path + "Visuals.MaximumShardScale", 0.38));
        this.visualShardGravity = Math.max(0.005,
                getConfig().getDouble(path + "Visuals.ShardGravity", 0.026));
        this.visualShardBounce = Math.max(0.0, Math.min(0.9,
                getConfig().getDouble(path + "Visuals.ShardBounce", 0.46)));
        this.soundIntervalTicks = Math.max(4,
                getConfig().getInt(path + "Sound.IntervalTicks", 7));
    }

    private boolean beginMoving() {
        if (this.moving || this.source == null || !this.source.reserve(this)) return false;
        this.direction = this.player.getEyeLocation().getDirection().clone().setY(0);
        if (this.direction.lengthSquared() <= 1.0E-9) {
            this.source.restore();
            return false;
        }
        this.direction.normalize();
        this.location = this.source.block().getLocation().clone().add(0.5, 1.05, 0.5);
        this.moving = true;
        this.createShardBarrage();
        this.displaySandBurst(this.location, 28, this.width * 0.24, 0.35,
                this.width * 0.24, 0.055, this.isRedSand());
        this.playSandSound(this.location, Sound.BLOCK_SAND_BREAK, 1.25F, 0.52F);
        this.playSandSound(this.location, Sound.ENTITY_BREEZE_WIND_BURST, 0.72F, 0.66F);
        return true;
    }

    @Override
    public void progress() {
        if (this.player.isDead() || !this.player.isOnline() || this.source == null
                || !this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
            this.remove();
            return;
        }
        if (!this.moving) {
            if (!this.player.isSneaking()
                    || System.currentTimeMillis() > this.getStartTime() + this.prepareTimeout) {
                this.remove();
                return;
            }
            this.renderSelectedSource();
            return;
        }

        final double step = Math.min(0.25, this.speed);
        double remaining = this.speed;
        while (remaining > 1.0E-9) {
            final double distance = Math.min(step, remaining);
            if (!this.advance(distance)) {
                this.remove();
                return;
            }
            remaining -= distance;
            this.travelled += distance;
            if (this.travelled >= this.range) {
                this.remove();
                return;
            }
        }
        this.updateShardBarrage();
        this.captureTargets();
        this.progressCarriedTargets();
        if (this.getRunningTicks() % this.soundIntervalTicks == 0L) {
            this.playSandSound(this.location, Sound.BLOCK_SAND_BREAK, 0.48F, 0.68F);
            this.playSandSound(this.location, Sound.ENTITY_BREEZE_IDLE_GROUND, 0.2F, 0.52F);
        }
    }

    private boolean advance(final double distance) {
        final Location desired = this.location.clone().add(this.direction.clone().multiply(distance));
        final Block ground = this.findGround(desired);
        if (ground == null || GeneralMethods.isRegionProtectedFromBuild(this, ground.getLocation())) return false;
        final Location next = new Location(desired.getWorld(), desired.getX(), ground.getY() + 1.05, desired.getZ());
        if (!GeneralMethods.isPassable(next.getBlock())
                || !GeneralMethods.isPassable(next.clone().add(0, 1, 0).getBlock())) return false;
        this.location = next;
        return true;
    }

    private Block findGround(final Location desired) {
        if (desired == null || desired.getWorld() == null) return null;
        final int currentGroundY = this.location == null
                ? desired.getBlockY() - 1 : (int) Math.floor(this.location.getY() - 1.0);
        for (int delta = this.maximumStepHeight; delta >= -this.maximumStepHeight; delta--) {
            final Block candidate = desired.getWorld().getBlockAt(
                    desired.getBlockX(), currentGroundY + delta, desired.getBlockZ());
            if (GeneralMethods.isSolid(candidate) && !candidate.isLiquid()) return candidate;
        }
        return null;
    }

    private void createShardBarrage() {
        if (this.location == null || this.location.getWorld() == null || !this.shards.isEmpty()) return;
        final Material sand = this.isRedSand() ? Material.RED_SAND : Material.SAND;
        final Material sandstone = this.isRedSand() ? Material.RED_SANDSTONE : Material.SANDSTONE;
        for (int index = 0; index < this.visualShardCount; index++) {
            final BlockDisplay display = this.location.getWorld().spawn(this.location, BlockDisplay.class);
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
            final SandShard shard = new SandShard(display, index);
            this.initializeWaveShard(shard);
            final Vector right = new Vector(-this.direction.getZ(), 0, this.direction.getX()).normalize();
            final Location position = this.shardLocation(shard, right);
            position.setYaw(0.0F);
            position.setPitch(0.0F);
            display.teleport(position);
            display.setTransformation(this.shardTransformation(shard));
            this.shards.add(shard);
        }
    }

    private void updateShardBarrage() {
        if (this.location == null || this.direction == null) return;
        if (this.shards.isEmpty()) this.createShardBarrage();
        final Vector right = new Vector(-this.direction.getZ(), 0, this.direction.getX()).normalize();
        for (final SandShard shard : this.shards) {
            if (shard.display == null || !shard.display.isValid()) continue;
            shard.age++;
            shard.forward += shard.forwardVelocity;
            shard.side += shard.sideVelocity;
            shard.height += shard.verticalVelocity;
            shard.verticalVelocity -= this.visualShardGravity;
            shard.forwardVelocity *= 0.988;
            shard.sideVelocity *= 0.985;
            shard.rotationX += shard.angularX;
            shard.rotationY += shard.angularY;
            shard.rotationZ += shard.angularZ;

            if (shard.height <= 0.08) {
                shard.height = 0.08;
                if (shard.bounces < 2 && shard.verticalVelocity < -0.035) {
                    shard.verticalVelocity = -shard.verticalVelocity * this.visualShardBounce;
                    shard.forwardVelocity *= 0.84;
                    shard.sideVelocity *= 0.76;
                    shard.bounces++;
                    final Location impact = this.shardLocation(shard, right);
                    this.displayFineSand(impact, 2, 0.11, 0.035, 0.11,
                            0.008, this.isRedSand());
                } else {
                    shard.verticalVelocity = 0.0;
                    shard.forwardVelocity *= 0.74;
                    shard.sideVelocity *= 0.68;
                }
            }
            shard.side = Math.max(-this.width * 0.58, Math.min(this.width * 0.58, shard.side));
            shard.forward = Math.min(1.45, shard.forward);

            final Location position = this.shardLocation(shard, right);
            position.setYaw(0.0F);
            position.setPitch(0.0F);
            shard.display.teleport(position);
            shard.display.setTransformation(this.shardTransformation(shard));
        }

        if ((this.getRunningTicks() & 1L) == 0L) {
            for (int index = 0; index < 7; index++) {
                final double side = (index / 6.0 - 0.5) * this.width;
                final Location trail = this.location.clone()
                        .add(this.direction.clone().multiply(-0.72 - (index % 2) * 0.28))
                        .add(right.clone().multiply(side)).add(0, 0.08, 0);
                this.displayFineSand(trail, 1, 0.07, 0.025, 0.07,
                        0.0, this.isRedSand());
            }
        }
    }

    private Location shardLocation(final SandShard shard, final Vector right) {
        return this.location.clone()
                .add(this.direction.clone().multiply(shard.forward))
                .add(right.clone().multiply(shard.side))
                .add(0, shard.height, 0);
    }

    private Transformation shardTransformation(final SandShard shard) {
        final float heading = (float) Math.atan2(this.direction.getX(), this.direction.getZ());
        final Quaternionf rotation = new Quaternionf().rotateY(heading)
                .rotateX(shard.rotationX).rotateY(shard.rotationY).rotateZ(shard.rotationZ);
        final Vector3f translation = new Vector3f(
                shard.scaleX * 0.5F, shard.scaleY * 0.5F, shard.scaleZ * 0.5F);
        rotation.transform(translation);
        translation.negate();
        return new Transformation(translation, rotation,
                new Vector3f(shard.scaleX, shard.scaleY, shard.scaleZ), new Quaternionf());
    }

    private void initializeWaveShard(final SandShard shard) {
        shard.age = 0;
        shard.bounces = 0;
        final int rows = Math.min(this.visualShardRows, this.visualShardCount);
        final int columns = (int) Math.ceil(this.visualShardCount / (double) rows);
        final int row = shard.index % rows;
        final int column = shard.index / rows;
        final double columnProgress = columns <= 1 ? 0.5 : column / (double) (columns - 1);
        final double rowProgress = rows <= 1 ? 0.0 : row / (double) (rows - 1);
        shard.side = (columnProgress - 0.5) * this.width * 0.96
                + (sample(shard.index, 2) - 0.5) * 0.1;
        shard.forward = 0.28 - rowProgress * 1.18
                + (sample(shard.index, 3) - 0.5) * 0.08;
        shard.height = 0.08 + rowProgress * 0.11
                + sample(shard.index, 4) * 0.08;
        shard.forwardVelocity = 0.025 + (1.0 - rowProgress) * 0.035
                + sample(shard.index, 5) * 0.018;
        final double outward = columnProgress - 0.5;
        shard.sideVelocity = outward * 0.018 + (sample(shard.index, 6) - 0.5) * 0.012;
        shard.verticalVelocity = 0.16 + (1.0 - rowProgress) * 0.13
                + sample(shard.index, 7) * 0.075;
        shard.rotationX = (float) (sample(shard.index, 8) * Math.PI * 2.0);
        shard.rotationY = (float) (sample(shard.index, 9) * Math.PI * 2.0);
        shard.rotationZ = (float) (sample(shard.index, 10) * Math.PI * 2.0);
        shard.angularX = (float) ((sample(shard.index, 11) - 0.5) * 0.42);
        shard.angularY = (float) ((sample(shard.index, 12) - 0.5) * 0.5);
        shard.angularZ = (float) ((sample(shard.index, 13) - 0.5) * 0.38);
        final float base = (float) (this.visualMinimumShardScale
                + sample(shard.index, 14)
                * (this.visualMaximumShardScale - this.visualMinimumShardScale));
        shard.scaleX = base * (float) (0.62 + sample(shard.index, 15) * 0.75);
        shard.scaleY = base * (float) (0.38 + sample(shard.index, 16) * 0.62);
        shard.scaleZ = base * (float) (0.55 + sample(shard.index, 17) * 0.7);
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

    private void destroyShardBarrage() {
        for (final SandShard shard : this.shards) {
            if (shard.display != null && shard.display.isValid()) shard.display.remove();
        }
        this.shards.clear();
    }

    private void renderSelectedSource() {
        final Location center = this.source.block().getLocation().clone().add(0.5, 0.62, 0.5);
        final Vector aimed = this.player.getEyeLocation().getDirection().clone().setY(0);
        if (aimed.lengthSquared() > 1.0E-9) aimed.normalize();
        final Vector right = new Vector(-aimed.getZ(), 0, aimed.getX());
        final double phase = this.getRunningTicks() * 0.34;
        for (int index = 0; index < 15; index++) {
            final double progress = index / 14.0;
            final double side = Math.sin(phase + progress * Math.PI * 2.0) * 0.34;
            final Location point = center.clone().add(aimed.clone().multiply(progress * 0.8))
                    .add(right.clone().multiply(side)).add(0, progress * 0.36, 0);
            this.displayFineSand(point, 1, 0.025, 0.025, 0.025, 0.0, this.isRedSand());
        }
        if (this.getRunningTicks() % 10L == 0L) {
            this.playSandSound(center, Sound.BLOCK_SAND_BREAK, 0.28F, 1.18F);
        }
    }

    private void captureTargets() {
        final Vector right = new Vector(-this.direction.getZ(), 0, this.direction.getX());
        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.location, this.width / 2.0 + 1.25)) {
            if (!(entity instanceof LivingEntity living) || entity.equals(this.player)
                    || this.hit.contains(entity.getUniqueId())
                    || GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) continue;
            if (entity instanceof Player target && Commands.invincible.contains(target.getName())) continue;
            final Vector offset = entity.getLocation().toVector().subtract(this.location.toVector());
            if (Math.abs(offset.dot(right)) > this.width / 2.0 || Math.abs(offset.dot(this.direction)) > 1.1
                    || Math.abs(offset.getY()) > 1.8) continue;
            this.hit.add(entity.getUniqueId());
            if (this.damage > 0) DamageHandler.damageEntity(entity, this.damage, this);
            this.displaySandBurst(entity.getLocation().clone().add(0, 0.65, 0),
                    14, 0.34, 0.42, 0.34, 0.04, this.isRedSand());
            this.playSandSound(entity.getLocation(), Sound.BLOCK_SAND_BREAK, 0.75F, 0.46F);
            if (this.carryTicks > 0) {
                this.carried.put(entity.getUniqueId(), new CarriedTarget(living, this.carryTicks));
            } else {
                this.release(living);
            }
        }
    }

    private void progressCarriedTargets() {
        final Iterator<CarriedTarget> iterator = this.carried.values().iterator();
        while (iterator.hasNext()) {
            final CarriedTarget target = iterator.next();
            if (target.entity().isDead() || !target.entity().isValid()) {
                iterator.remove();
                continue;
            }
            if (target.ticks() <= 1) {
                this.release(target.entity());
                iterator.remove();
                continue;
            }
            final Vector carriedVelocity = this.direction.clone().multiply(Math.max(0.25, this.speed * 0.72));
            carriedVelocity.setY(Math.max(0.08, target.entity().getVelocity().getY()));
            GeneralMethods.setVelocity(this, target.entity(), carriedVelocity);
            target.decrement();
        }
    }

    private void release(final LivingEntity entity) {
        if (entity == null || !entity.isValid()) return;
        final Vector velocity = this.direction.clone().multiply(this.knockback);
        velocity.setY(this.knockup);
        GeneralMethods.setVelocity(this, entity, velocity);
    }

    private boolean isRedSand() {
        return this.source.visualMaterial().toString().startsWith("RED_");
    }

    @Override
    public List<Location> getLocations() {
        final List<Location> locations = new ArrayList<>();
        if (!this.moving || this.location == null || this.direction == null) return locations;
        final Vector right = new Vector(-this.direction.getZ(), 0, this.direction.getX());
        for (double side = -this.width / 2.0; side <= this.width / 2.0; side += 0.5) {
            locations.add(this.location.clone().add(right.clone().multiply(side)));
        }
        return locations;
    }

    @Override
    public void handleCollision(final Collision collision) {
        super.handleCollision(collision);
    }

    @Override
    public void remove() {
        super.remove();
        if (!this.isRemoved()) return;
        for (final CarriedTarget target : this.carried.values()) this.release(target.entity());
        this.carried.clear();
        this.destroyShardBarrage();
        if (this.source != null) this.source.restore();
        if (this.moving) {
            this.bPlayer.addCooldown(this);
            if (this.location != null) {
                this.displaySandBurst(this.location, 16, this.width * 0.18, 0.18,
                        this.width * 0.18, 0.03, this.isRedSand());
                this.playSandSound(this.location, Sound.BLOCK_SAND_BREAK, 0.62F, 0.58F);
            }
        }
    }

    @Override public String getName() { return "SandSurge"; }
    @Override public Location getLocation() { return this.location; }
    @Override public long getCooldown() { return this.cooldown; }
    @Override public boolean isSneakAbility() { return true; }
    @Override public boolean isHarmlessAbility() { return false; }
    @Override public double getCollisionRadius() { return 0.8; }

    public boolean isMoving() { return this.moving; }
    public double getTravelled() { return this.travelled; }

    private static final class SandShard {
        private final BlockDisplay display;
        private final int index;
        private int age;
        private int bounces;
        private double side;
        private double forward;
        private double height;
        private double sideVelocity;
        private double forwardVelocity;
        private double verticalVelocity;
        private float rotationX;
        private float rotationY;
        private float rotationZ;
        private float angularX;
        private float angularY;
        private float angularZ;
        private float scaleX;
        private float scaleY;
        private float scaleZ;

        private SandShard(final BlockDisplay display, final int index) {
            this.display = display;
            this.index = index;
        }
    }

    private static final class CarriedTarget {
        private final LivingEntity entity;
        private int ticks;

        private CarriedTarget(final LivingEntity entity, final int ticks) {
            this.entity = entity;
            this.ticks = ticks;
        }

        private LivingEntity entity() { return this.entity; }
        private int ticks() { return this.ticks; }
        private void decrement() { this.ticks--; }
    }
}
