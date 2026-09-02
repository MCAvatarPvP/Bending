package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.SandAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.earthbending.passive.DensityShift;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay;
import com.projectkorra.projectkorra.platform.mc.entity.Display;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/** Launches a spray of independently simulated, tumbling sand fragments. */
public class SandBlast extends SandAbility implements AddonAbility {

    private static final double PHYSICS_SUBSTEP = 0.18;

    @Attribute(Attribute.DAMAGE)
    private static double damage;
    private final List<Entity> affectedEntities = new ArrayList<>();
    private final List<SandShard> shards = new ArrayList<>();
    private final Random rand;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.SELECT_RANGE)
    private double sourceRange;
    @Attribute(Attribute.RANGE)
    private int range;
    @Attribute("MaxShots")
    private int maxBlasts;
    private int shardsPerBlast;
    private float minimumShardScale;
    private float maximumShardScale;
    private double shardSpeed;
    private double shardSpread;
    private double shardLift;
    private double shardGravity;
    private double shardDrag;
    private double shardBounce;
    private int maximumBounces;
    private int shardLifetimeTicks;
    private double hitRadius;
    private Block source;
    private BlockData sourceData;
    private int blasts;
    private boolean blasting;
    private Vector direction;
    private Location launchOrigin;
    private TempBlock tempBlock;

    public SandBlast(final Player player) {
        super(player);
        this.rand = PredictionDeterminism.random(player == null ? null : player.getUniqueId(),
                getClass().getName() + ":display-shard-physics");

        if (!this.bPlayer.canBend(this)) return;

        if (hasAbility(player, SandBlast.class)) {
            getAbility(player, SandBlast.class).remove();
        }

        this.setFields();
        if (this.prepare()) this.start();
    }

    public static void blastSand(final Player player) {
        if (!hasAbility(player, SandBlast.class)) return;
        final SandBlast blast = getAbility(player, SandBlast.class);
        if (!blast.blasting) blast.blastSand();
    }

    public static double getDamage() {
        return damage;
    }

    public static void setDamage(final double damage) {
        SandBlast.damage = damage;
    }

    public void setFields() {
        final String path = "Abilities.Earth.SandBlast.";
        this.cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong(path + "Cooldown");
        this.sourceRange = JedCoreConfig.getConfig(this.bPlayer).getDouble(path + "SourceRange");
        this.range = Math.max(1, JedCoreConfig.getConfig(this.bPlayer).getInt(path + "Range"));
        this.maxBlasts = Math.max(1, JedCoreConfig.getConfig(this.bPlayer).getInt(path + "MaxSandBlocks"));
        damage = Math.max(0.0, JedCoreConfig.getConfig(this.bPlayer).getDouble(path + "Damage"));
        this.shardsPerBlast = Math.max(2, Math.min(8,
                JedCoreConfig.getConfig(this.bPlayer).getInt(path + "Visuals.ShardsPerBlast", 4)));
        this.minimumShardScale = (float) Math.max(0.06,
                JedCoreConfig.getConfig(this.bPlayer).getDouble(path + "Visuals.MinimumShardScale", 0.13));
        this.maximumShardScale = (float) Math.max(this.minimumShardScale,
                JedCoreConfig.getConfig(this.bPlayer).getDouble(path + "Visuals.MaximumShardScale", 0.31));
        this.shardSpeed = Math.max(0.1,
                JedCoreConfig.getConfig(this.bPlayer).getDouble(path + "Physics.Speed", 0.9));
        this.shardSpread = Math.max(0.0,
                JedCoreConfig.getConfig(this.bPlayer).getDouble(path + "Physics.Spread", 0.13));
        this.shardLift = JedCoreConfig.getConfig(this.bPlayer).getDouble(path + "Physics.Lift", 0.18);
        this.shardGravity = Math.max(0.0,
                JedCoreConfig.getConfig(this.bPlayer).getDouble(path + "Physics.Gravity", 0.026));
        this.shardDrag = clamp(JedCoreConfig.getConfig(this.bPlayer).getDouble(path + "Physics.Drag", 0.986), 0.0, 1.0);
        this.shardBounce = clamp(JedCoreConfig.getConfig(this.bPlayer).getDouble(path + "Physics.Bounce", 0.42), 0.0, 0.95);
        this.maximumBounces = Math.max(0,
                JedCoreConfig.getConfig(this.bPlayer).getInt(path + "Physics.MaximumBounces", 3));
        this.shardLifetimeTicks = Math.max(10,
                JedCoreConfig.getConfig(this.bPlayer).getInt(path + "Physics.LifetimeTicks", 60));
        this.hitRadius = Math.max(0.25,
                JedCoreConfig.getConfig(this.bPlayer).getDouble(path + "HitRadius", 0.9));
    }

    private boolean prepare() {
        this.source = this.getEarthSourceBlock(this.sourceRange);
        if (this.source == null || !isSand(this.source)
                || !ElementalAbility.isAir(this.source.getRelative(BlockFace.UP).getType())) return false;

        this.sourceData = this.source.getBlockData().clone();
        if (DensityShift.isPassiveSand(this.source)) DensityShift.revertSand(this.source);
        this.tempBlock = new TempBlock(this.source, Material.SANDSTONE.createBlockData());
        return true;
    }

    @Override
    public void progress() {
        if (!hasAbility(this.player, SandBlast.class)) return;
        if (this.player.isDead() || !this.player.isOnline()
                || this.source == null || this.player.getWorld() != this.source.getWorld()) {
            this.remove();
            return;
        }
        if (!this.blasting) return;

        if (this.blasts < this.maxBlasts) this.blastSand();
        this.progressShards();
        if (this.blasts >= this.maxBlasts && this.shards.isEmpty()) this.remove();
    }

    private void blastSand() {
        if (!this.blasting) {
            this.blasting = true;
            this.launchOrigin = this.source.getLocation().clone().add(0.5, 1.42, 0.5);
            this.direction = GeneralMethods.getDirection(this.launchOrigin,
                    GeneralMethods.getTargetedLocation(this.player, this.range));
            if (this.direction.lengthSquared() <= 1.0E-9) {
                this.remove();
                return;
            }
            this.direction.normalize();
            this.tempBlock.revertBlock();
            this.bPlayer.addCooldown(this);
            this.displaySandBurst(this.launchOrigin, 18, 0.34, 0.24, 0.34, 0.035, this.isRedSand());
            this.playSandSound(this.launchOrigin, Sound.BLOCK_SAND_BREAK, 1.0F, 0.62F);
        }

        for (int index = 0; index < this.shardsPerBlast; index++) this.spawnShard();
        this.blasts++;
        if (this.rand.nextInt(3) == 0) DensityShift.playSandbendingSound(this.launchOrigin);
    }

    private void spawnShard() {
        final Location location = this.launchOrigin.clone().add(this.direction.clone().multiply(0.24));
        location.add((this.rand.nextDouble() - 0.5) * 0.28,
                (this.rand.nextDouble() - 0.5) * 0.18,
                (this.rand.nextDouble() - 0.5) * 0.28);

        final Vector reference = Math.abs(this.direction.getY()) < 0.92
                ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
        final Vector right = this.direction.clone().crossProduct(reference).normalize();
        final Vector up = right.clone().crossProduct(this.direction).normalize();
        final Vector velocity = this.direction.clone().multiply(this.shardSpeed)
                .add(right.multiply((this.rand.nextDouble() * 2.0 - 1.0) * this.shardSpread))
                .add(up.multiply((this.rand.nextDouble() * 2.0 - 1.0) * this.shardSpread))
                .add(new Vector(0, this.shardLift, 0));

        final BlockDisplay display = location.getWorld().spawn(location, BlockDisplay.class);
        display.setBlock(this.sourceData.clone());
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);
        display.setSilent(true);
        display.setBillboard(Display.Billboard.FIXED);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTeleportDuration(1);
        display.setViewRange(48.0F);

        final float base = (float) (this.minimumShardScale
                + this.rand.nextDouble() * (this.maximumShardScale - this.minimumShardScale));
        final SandShard shard = new SandShard(display, location, velocity,
                base * randomScale(0.62, 1.28),
                base * randomScale(0.4, 0.9),
                base * randomScale(0.58, 1.2));
        shard.rotationX = randomAngle();
        shard.rotationY = randomAngle();
        shard.rotationZ = randomAngle();
        shard.angularX = randomAngularVelocity(0.42F);
        shard.angularY = randomAngularVelocity(0.5F);
        shard.angularZ = randomAngularVelocity(0.38F);
        display.setShadowRadius(base * 0.5F);
        display.setShadowStrength(0.72F);
        display.setTransformation(this.transformation(shard));
        this.shards.add(shard);
    }

    private void progressShards() {
        final Iterator<SandShard> iterator = this.shards.iterator();
        while (iterator.hasNext()) {
            final SandShard shard = iterator.next();
            if (shard.display == null || !shard.display.isValid() || shard.location.getWorld() == null
                    || ++shard.age > this.shardLifetimeTicks
                    || this.launchOrigin.distanceSquared(shard.location) > (this.range + 4.0) * (this.range + 4.0)
                    || RegionProtection.isRegionProtected(this.player, shard.location, this)) {
                this.removeShard(iterator, shard);
                continue;
            }

            shard.velocity.multiply(this.shardDrag);
            shard.velocity.setY(shard.velocity.getY() - this.shardGravity);
            final Vector movement = shard.velocity.clone();
            final int steps = Math.max(1, (int) Math.ceil(movement.length() / PHYSICS_SUBSTEP));
            final Vector step = movement.multiply(1.0 / steps);
            boolean collided = false;

            for (int index = 0; index < steps; index++) {
                final boolean hitX = this.isBlocked(shard.location.clone().add(step.getX(), 0, 0), shard.radius());
                if (!hitX) shard.location.add(step.getX(), 0, 0);
                final boolean hitY = this.isBlocked(shard.location.clone().add(0, step.getY(), 0), shard.radius());
                if (!hitY) shard.location.add(0, step.getY(), 0);
                final boolean hitZ = this.isBlocked(shard.location.clone().add(0, 0, step.getZ()), shard.radius());
                if (!hitZ) shard.location.add(0, 0, step.getZ());

                if (hitX || hitY || hitZ) {
                    if (hitX) shard.velocity.setX(-shard.velocity.getX() * this.shardBounce);
                    if (hitY) shard.velocity.setY(-shard.velocity.getY() * this.shardBounce);
                    if (hitZ) shard.velocity.setZ(-shard.velocity.getZ() * this.shardBounce);
                    if (hitY) {
                        shard.velocity.setX(shard.velocity.getX() * 0.76);
                        shard.velocity.setZ(shard.velocity.getZ() * 0.76);
                    } else {
                        shard.velocity.setY(shard.velocity.getY() * 0.82);
                    }
                    collided = true;
                    break;
                }
            }

            if (collided) {
                shard.bounces++;
                shard.angularX *= 0.86F;
                shard.angularY *= 0.86F;
                shard.angularZ *= 0.86F;
                this.displayFineSand(shard.location, 3, 0.11, 0.08, 0.11, 0.012, this.isRedSand());
            }
            if (shard.bounces > this.maximumBounces
                    || (collided && shard.velocity.lengthSquared() < 0.006)) {
                this.removeShard(iterator, shard);
                continue;
            }
            this.affect(shard.location);

            shard.rotationX += shard.angularX;
            shard.rotationY += shard.angularY;
            shard.rotationZ += shard.angularZ;
            shard.angularX *= 0.992F;
            shard.angularY *= 0.992F;
            shard.angularZ *= 0.992F;
            shard.location.setYaw(0.0F);
            shard.location.setPitch(0.0F);
            shard.display.teleport(shard.location);
            shard.display.setTransformation(this.transformation(shard));
        }
    }

    private boolean isBlocked(final Location center, final double radius) {
        if (center == null || center.getWorld() == null) return true;
        final double[] offsets = {-radius, radius};
        if (this.isSolidObstacle(center.getBlock())) return true;
        for (final double x : offsets) {
            for (final double y : offsets) {
                for (final double z : offsets) {
                    if (this.isSolidObstacle(center.clone().add(x, y, z).getBlock())) return true;
                }
            }
        }
        return false;
    }

    private boolean isSolidObstacle(final Block block) {
        return block != null && !block.isLiquid() && !GeneralMethods.isPassable(block);
    }

    private void affect(final Location location) {
        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(location, this.hitRadius)) {
            if (!(entity instanceof LivingEntity living) || entity instanceof ArmorStand
                    || entity.equals(this.player) || entity.isDead() || this.affectedEntities.contains(entity)) continue;
            if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) continue;

            DamageHandler.damageEntity(entity, damage, this);
            this.affectedEntities.add(entity);
            if (living.hasPotionEffect(PotionEffectType.BLINDNESS)) living.removePotionEffect(PotionEffectType.BLINDNESS);
            living.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 1));
            this.displaySandBurst(entity.getLocation().clone().add(0, 0.65, 0),
                    10, 0.26, 0.32, 0.26, 0.025, this.isRedSand());
        }
    }

    private Transformation transformation(final SandShard shard) {
        final Quaternionf rotation = new Quaternionf().rotateX(shard.rotationX)
                .rotateY(shard.rotationY).rotateZ(shard.rotationZ);
        final Vector3f translation = new Vector3f(
                shard.scaleX * 0.5F, shard.scaleY * 0.5F, shard.scaleZ * 0.5F);
        rotation.transform(translation);
        translation.negate();
        return new Transformation(translation, rotation,
                new Vector3f(shard.scaleX, shard.scaleY, shard.scaleZ), new Quaternionf());
    }

    private void removeShard(final Iterator<SandShard> iterator, final SandShard shard) {
        if (shard.display != null && shard.display.isValid()) shard.display.remove();
        iterator.remove();
    }

    private boolean isRedSand() {
        return this.sourceData != null && this.sourceData.getMaterial().toString().startsWith("RED_");
    }

    private float randomScale(final double minimum, final double maximum) {
        return (float) (minimum + this.rand.nextDouble() * (maximum - minimum));
    }

    private float randomAngle() {
        return (float) (this.rand.nextDouble() * Math.PI * 2.0);
    }

    private float randomAngularVelocity(final float maximum) {
        return (this.rand.nextFloat() * 2.0F - 1.0F) * maximum;
    }

    private static double clamp(final double value, final double minimum, final double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    public void remove() {
        if (this.tempBlock != null) this.tempBlock.revertBlock();
        for (final SandShard shard : this.shards) {
            if (shard.display != null && shard.display.isValid()) shard.display.remove();
        }
        this.shards.clear();
        super.remove();
    }

    @Override public long getCooldown() { return this.cooldown; }
    public void setCooldown(final long cooldown) { this.cooldown = cooldown; }

    @Override
    public Location getLocation() {
        return this.shards.isEmpty() ? null : this.shards.get(0).location.clone();
    }

    @Override
    public List<Location> getLocations() {
        final List<Location> locations = new ArrayList<>(this.shards.size());
        for (final SandShard shard : this.shards) locations.add(shard.location.clone());
        return locations;
    }

    @Override
    public void handleCollision(final Collision collision) {
        if (!collision.isRemovingFirst() || collision.getLocationFirst() == null) return;
        final Location collisionLocation = collision.getLocationFirst();
        final Iterator<SandShard> iterator = this.shards.iterator();
        while (iterator.hasNext()) {
            final SandShard shard = iterator.next();
            if (shard.location.getWorld() == collisionLocation.getWorld()
                    && shard.location.distanceSquared(collisionLocation) <= 0.36) {
                this.removeShard(iterator, shard);
                return;
            }
        }
    }

    @Override public String getName() { return "SandBlast"; }
    @Override public boolean isHarmlessAbility() { return false; }
    @Override public boolean isSneakAbility() { return true; }
    @Override public String getAuthor() { return JedCore.dev; }
    @Override public String getVersion() { return JedCore.version; }

    @Override
    public String getDescription() {
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer)
                .getString("Abilities.Earth.SandBlast.Description");
    }

    public double getSourceRange() { return this.sourceRange; }
    public void setSourceRange(final double sourceRange) { this.sourceRange = sourceRange; }
    public int getRange() { return this.range; }
    public void setRange(final int range) { this.range = range; }
    public int getMaxBlasts() { return this.maxBlasts; }
    public void setMaxBlasts(final int maxBlasts) { this.maxBlasts = maxBlasts; }
    public Block getSource() { return this.source; }
    public void setSource(final Block source) { this.source = source; }
    public BlockData getSourceData() { return this.sourceData; }
    public void setSourceData(final BlockData sourceData) { this.sourceData = sourceData; }
    public int getBlasts() { return this.blasts; }
    public void setBlasts(final int blasts) { this.blasts = blasts; }
    public boolean isBlasting() { return this.blasting; }
    public void setBlasting(final boolean blasting) { this.blasting = blasting; }
    public Vector getDirection() { return this.direction; }
    public void setDirection(final Vector direction) { this.direction = direction; }
    public TempBlock getTempBlock() { return this.tempBlock; }
    public void setTempBlock(final TempBlock tempBlock) { this.tempBlock = tempBlock; }
    public List<Entity> getAffectedEntities() { return this.affectedEntities; }

    /** The live visual projectiles, retained under the legacy accessor name for addon compatibility. */
    public List<BlockDisplay> getFallingBlocks() {
        final List<BlockDisplay> displays = new ArrayList<>(this.shards.size());
        for (final SandShard shard : this.shards) displays.add(shard.display);
        return displays;
    }

    @Override public void load() { }
    @Override public void stop() { }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.SandBlast.Enabled");
    }

    private static final class SandShard {
        private final BlockDisplay display;
        private final Location location;
        private final Vector velocity;
        private final float scaleX;
        private final float scaleY;
        private final float scaleZ;
        private int age;
        private int bounces;
        private float rotationX;
        private float rotationY;
        private float rotationZ;
        private float angularX;
        private float angularY;
        private float angularZ;

        private SandShard(final BlockDisplay display, final Location location, final Vector velocity,
                          final float scaleX, final float scaleY, final float scaleZ) {
            this.display = display;
            this.location = location;
            this.velocity = velocity;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.scaleZ = scaleZ;
        }

        private double radius() {
            return Math.max(this.scaleX, Math.max(this.scaleY, this.scaleZ)) * 0.5;
        }
    }
}
