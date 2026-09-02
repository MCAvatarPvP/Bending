package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.collision.CollisionDetector;
import com.jedk1.jedcore.collision.CollisionUtil;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.BlockUtil;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.earthbending.passive.DensityShift;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import com.projectkorra.projectkorra.prediction.hit.EntityHitboxProvider;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import com.projectkorra.projectkorra.util.colliders.AABB;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class EarthShard extends EarthAbility implements AddonAbility, EntityHitboxProvider {
    private static final int MAX_READY_HANDOFF_ATTEMPTS = 20;
    private static final int MAX_RISE_TICKS = 40;

    @Attribute(Attribute.RANGE)
    public static int range;
    public static int abilityRange;

    @Attribute(Attribute.DAMAGE)
    public static double normalDmg;
    @Attribute(Attribute.DAMAGE)
    public static double metalDmg;
    @Attribute("MaxShots")
    public static int maxShards;
    @Attribute(Attribute.COOLDOWN)
    public static long cooldown;
    private final List<TempBlock> tblockTracker = new ArrayList<>();
    private final List<TempBlock> readyBlocksTracker = new ArrayList<>();
    private final List<TempFallingBlock> fallingBlocks = new ArrayList<>();
    private final Map<TempFallingBlock, Integer> readyHandoffAttempts = new IdentityHashMap<>();
    private final Map<TempFallingBlock, Integer> riseTicks = new IdentityHashMap<>();
    private final Map<TempFallingBlock, Integer> riseTickLimits = new IdentityHashMap<>();
    private double animationSpeed;
    private double maxDistance;
    private long shootBuffer;
    private boolean isThrown = false;
    private Location origin;
    private double abilityCollisionRadius;
    private double entityCollisionRadius;
    private boolean waitTillShardsRise;
    private double waitForOffset;
    private long bufferedShootUntil;

    public EarthShard(Player player) {
        super(player);

        if (!bPlayer.canBend(this)) {
            return;
        }

        if (hasAbility(player, EarthShard.class)) {
            for (EarthShard es : EarthShard.getAbilities(player, EarthShard.class)) {
                if (es.isThrown && System.currentTimeMillis() - es.getStartTime() >= 20000) {
                    // Remove the old instance because it got into a broken state.
                    // This shouldn't affect normal gameplay because the cooldown is long enough that the
                    // shards should have already hit their target.
                    es.remove();
                } else {
                    es.select();
                    return;
                }
            }
        }

        setFields();
        origin = player.getLocation().clone();
        raiseEarthBlock(getEarthSourceBlock(range));
        start();
    }

    public static void throwShard(Player player) {
        if (hasAbility(player, EarthShard.class)) {
            for (EarthShard es : EarthShard.getAbilities(player, EarthShard.class)) {
                if (!es.isThrown) {
                    es.throwShard(true);
                    break;
                }
            }
        }
    }

    public static int getRange() {
        return range;
    }

    public static void setRange(int range) {
        EarthShard.range = range;
    }

    public static int getAbilityRange() {
        return abilityRange;
    }

    public static void setAbilityRange(int abilityRange) {
        EarthShard.abilityRange = abilityRange;
    }

    public static double getNormalDmg() {
        return normalDmg;
    }

    public static void setNormalDmg(double normalDmg) {
        EarthShard.normalDmg = normalDmg;
    }

    public static double getMetalDmg() {
        return metalDmg;
    }

    public static void setMetalDmg(double metalDmg) {
        EarthShard.metalDmg = metalDmg;
    }

    public static int getMaxShards() {
        return maxShards;
    }

    public static void setMaxShards(int maxShards) {
        EarthShard.maxShards = maxShards;
    }

    public void setFields() {
        range = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.EarthShard.PrepareRange");
        abilityRange = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.EarthShard.AbilityRange");
        normalDmg = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.EarthShard.Damage.Normal");
        metalDmg = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.EarthShard.Damage.Metal");
        animationSpeed = Math.max(0.05,
                JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.EarthShard.AnimationSpeed"));
        maxDistance = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.EarthShard.MaxDistance");
        maxShards = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.EarthShard.MaxShards");
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Earth.EarthShard.Cooldown");
        shootBuffer = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Earth.EarthShard.ShootBuffer");
        abilityCollisionRadius = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.EarthShard.AbilityCollisionRadius");
        entityCollisionRadius = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.EarthShard.EntityCollisionRadius");
        waitTillShardsRise = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.EarthShard.WaitForShards");
        waitForOffset = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.EarthShard.WaitForOffset");
    }

    public void select() {
        raiseEarthBlock(getEarthSourceBlock(range));
    }

    public void raiseEarthBlock(final Block block) {
        if (block == null) {
            return;
        }

        // Never use an existing temporary layer as another shard source.
        if (TempBlock.isTempBlock(block)) {
            return;
        }

        if (this.tblockTracker.size() >= maxShards) {
            return;
        }

        // A column may contain only one prepared/rising EarthShard.
        if (hasShardInColumn(block)) {
            return;
        }

        // Validate the complete route to the held height. A lower source can be
        // much farther than three blocks below the player's original eye level.
        final int targetY = this.origin.getBlockY() + 2;
        final int clearanceHeight = Math.max(3, targetY - block.getY());
        for (int i = 1; i <= clearanceHeight; i++) {
            if (!isTransparent(block.getRelative(BlockFace.UP, i))) {
                return;
            }
        }

        if (!isEarthbendable(block)) {
            return;
        }

        if (isMetal(block)) {
            playMetalbendingSound(block.getLocation());
        } else {
            ParticleEffect.BLOCK_CRACK.display(
                    block.getLocation().add(0, 1, 0),
                    20,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    block.getBlockData()
            );

            playEarthbendingSound(block.getLocation());
        }

        final Material material = getCorrectType(block);

        if (DensityShift.isPassiveSand(block)) {
            DensityShift.revertSand(block);
        }

        final Location spawn = block.getLocation().add(0.5, 0, 0.5);

        final TempBlock sourceLayer = new TempBlock(
                block,
                Material.AIR.createBlockData(),
                this
        );
        if (sourceLayer.isReverted()) return;

        TempFallingBlock risingShard = null;
        try {
            risingShard = new TempFallingBlock(
                    spawn,
                    material.createBlockData(),
                    new Vector(0, this.animationSpeed, 0),
                    this
            );
            final int verticalDistance = Math.max(1, targetY - block.getY());
            final int riseTickLimit = Math.max(2, Math.min(MAX_RISE_TICKS,
                    (int) Math.ceil(verticalDistance / this.animationSpeed) + 2));
            this.riseTicks.put(risingShard, 0);
            this.riseTickLimits.put(risingShard, riseTickLimit);
            this.tblockTracker.add(sourceLayer);
        } catch (RuntimeException | Error failure) {
            if (risingShard != null) risingShard.remove();
            sourceLayer.revertBlock();
            throw failure;
        }
    }

    private boolean hasShardInColumn(final Block block) {
        if (block == null) {
            return false;
        }

        // Original/source blocks.
        for (final TempBlock tempBlock : this.tblockTracker) {
            if (tempBlock == null || tempBlock.isReverted()) {
                continue;
            }

            if (sameColumn(tempBlock.getLocation(), block)) {
                return true;
            }
        }

        // Shards that have finished rising and are waiting to be fired.
        for (final TempBlock tempBlock : this.readyBlocksTracker) {
            if (tempBlock == null || tempBlock.isReverted()) {
                continue;
            }

            if (sameColumn(tempBlock.getLocation(), block)) {
                return true;
            }
        }

        // Shards that are currently moving upward.
        for (final TempFallingBlock tempFallingBlock :
                TempFallingBlock.getFromAbility(this)) {

            if (tempFallingBlock == null) {
                continue;
            }

            final FallingBlock fallingBlock =
                    tempFallingBlock.getFallingBlock();

            if (fallingBlock == null || fallingBlock.isDead()) {
                continue;
            }

            if (sameColumn(fallingBlock.getLocation(), block)) {
                return true;
            }
        }

        return false;
    }

    private static boolean sameColumn(
            final Location location,
            final Block block) {

        if (location == null || block == null) {
            return false;
        }

        return location.getWorld() == block.getWorld()
                && location.getBlockX() == block.getX()
                && location.getBlockZ() == block.getZ();
    }

    public Material getCorrectType(Block block) {
        if (block.getType() == Material.SAND) {
            return Material.SANDSTONE;
        }
        if (block.getType() == Material.RED_SAND) {
            return Material.RED_SANDSTONE;
        }
        if (block.getType() == Material.GRAVEL) {
            return Material.COBBLESTONE;
        }
        if (block.getType().name().endsWith("CONCRETE_POWDER")) {
            return Material.getMaterial(block.getType().name().replace("_POWDER", ""));
        }

        return block.getType();
    }

    public void progress() {
        if (player == null || !player.isOnline() || player.isDead()) {
            remove();
            return;
        }

        // Explicit external authority can discard a coordinate without
        // running callbacks. Never let retired handles keep this ability in a
        // prepared state or get launched later as a ghost shard.
        tblockTracker.removeIf(TempBlock::isReverted);
        readyBlocksTracker.removeIf(TempBlock::isReverted);

        if (!isThrown) {
            if (!bPlayer.canBendIgnoreCooldowns(this)) {
                remove();
                return;
            }

            if (tblockTracker.isEmpty()) {
                remove();
                return;
            }

            final int targetY = this.origin.getBlockY() + 2;

            for (final TempFallingBlock tfb :
                    TempFallingBlock.getFromAbility(this)) {

                final FallingBlock fb = tfb.getFallingBlock();

                if (fb == null) {
                    this.forgetRisingShard(tfb);
                    tfb.remove();
                    continue;
                }

                if (fb.isDead()) {
                    this.forgetRisingShard(tfb);
                    tfb.remove();
                    continue;
                }

                final int riseTick = this.riseTicks.merge(tfb, 1, Integer::sum);
                final int riseTickLimit = this.riseTickLimits.getOrDefault(tfb, MAX_RISE_TICKS);

                /*
                 * Never rely on:
                 *
                 *     fb.getLocation().getBlockY() == targetY
                 *
                 * With a sufficiently large AnimationSpeed, the falling block can
                 * cross the target plane between two progress calls and permanently
                 * miss that equality.
                 *
                 * Instead, crossing the target Y means the rise has completed.
                 */
                if (fb.getLocation().getY() >= targetY || riseTick >= riseTickLimit) {

                    // Freeze at the handoff plane. The falling entity remains
                    // alive until the replacement TempBlock acknowledges that
                    // it was registered, so a rejected write cannot consume the
                    // only representation of this shard.
                    fb.setVelocity(new Vector(0, 0, 0));

                    /*
                     * Clamp the shard to its intended fixed Y.
                     *
                     * Do NOT use fb.getLocation().getBlock() here. By the time this
                     * executes, the entity may already be slightly above targetY.
                     */
                    final Location destination = new Location(
                            this.origin.getWorld(),
                            fb.getLocation().getBlockX(),
                            targetY,
                            fb.getLocation().getBlockZ()
                    );

                    if (this.createReadyBlock(destination, fb.getBlockData())) {
                        this.forgetRisingShard(tfb);
                        tfb.remove();
                    } else if (this.readyHandoffAttempts.merge(tfb, 1, Integer::sum)
                            >= MAX_READY_HANDOFF_ATTEMPTS) {
                        // A permanently obstructed/rejected destination must
                        // restore its source instead of trapping the ability.
                        this.forgetRisingShard(tfb);
                        tfb.remove();
                    }
                }
            }

            // If another lifecycle boundary removed a rising entity before the
            // handoff, restore its source instead of retaining an unthrowable
            // prepared ability forever.
            this.pruneOrphanedSources();
            if (this.tblockTracker.isEmpty()) {
                this.remove();
                return;
            }

            if (hasBufferedShoot()) {
                throwShard(false);
            }
        } else {
            for (TempFallingBlock tfb : TempFallingBlock.getFromAbility(this)) {
                FallingBlock fb = tfb.getFallingBlock();

                if (maxDistance != 0
                        && origin.distance(fb.getLocation()) > maxDistance) {
                    tfb.remove();
                }

                AABB collider =
                        BlockUtil.getFallingBlockBoundsFull(
                                fb,
                                entityCollisionRadius
                        );

                CollisionDetector.checkEntityCollisions(player, collider, (e) -> {
                    if (!CooldownSync.isAuthoritative()) return true;

                    DamageHandler.damageEntity(
                            e,
                            isMetal(fb.getBlockData().getMaterial())
                                    ? metalDmg
                                    : normalDmg,
                            this
                    );

                    ((LivingEntity) e).setNoDamageTicks(0);

                    ParticleEffect.BLOCK_CRACK.display(
                            fb.getLocation(),
                            20,
                            0,
                            0,
                            0,
                            0,
                            fb.getBlockData()
                    );

                    tfb.remove();
                    return false;
                });

                if (fb.isDead()) {
                    tfb.remove();
                }
            }

            if (TempFallingBlock.getFromAbility(this).isEmpty()) {
                remove();
            }
        }
    }

    public void throwShard() {
        throwShard(true);
    }

    private void throwShard(boolean allowBuffer) {
        if (isThrown) {
            return;
        }

        this.pruneOrphanedSources();
        boolean notReady = tblockTracker.size() > readyBlocksTracker.size();

        if (notReady && waitTillShardsRise) {
            bufferThrow(allowBuffer);
            return;
        }

        if (!waitTillShardsRise && notReady) {
            for (TempFallingBlock tfb : TempFallingBlock.getFromAbility(this)) {
                FallingBlock fb = tfb.getFallingBlock();

                if (fb.isDead() || fb.getLocation().getBlockY() < origin.getBlockY() + waitForOffset) {
                    bufferThrow(allowBuffer);
                    return;
                }

                final Location destination = new Location(
                        origin.getWorld(),
                        fb.getLocation().getBlockX(), origin.getBlockY() + 2,
                        fb.getLocation().getBlockZ());
                fb.setVelocity(new Vector(0, 0, 0));
                if (!this.createReadyBlock(destination, fb.getBlockData())) {
                    bufferThrow(allowBuffer);
                    return;
                }
                this.forgetRisingShard(tfb);
                tfb.remove();
            }
            this.pruneOrphanedSources();
            notReady = tblockTracker.size() > readyBlocksTracker.size();
            if (notReady) {
                bufferThrow(allowBuffer);
                return;
            }
        }

        if (readyBlocksTracker.isEmpty()) {
            if (TempFallingBlock.getFromAbility(this).isEmpty()) this.remove();
            else bufferThrow(allowBuffer);
            return;
        }

        Location targetLocation = GeneralMethods.getTargetedLocation(player, abilityRange);

        if (GeneralMethods.getTargetedEntity(player, abilityRange, new ArrayList<>()) != null) {
            targetLocation = GeneralMethods.getTargetedEntity(player, abilityRange, new ArrayList<>()).getLocation();
        }

        Vector vel = null;

        for (TempBlock tb : readyBlocksTracker) {
            Location target = player.getTargetBlock(null, 30).getLocation();

            if (target.getBlockX() == tb.getBlock().getX() && target.getBlockY() == tb.getBlock().getY() && target.getBlockZ() == tb.getBlock().getZ()) {
                vel = player.getEyeLocation().getDirection().multiply(2).add(new Vector(0, 0.2, 0));
                break;
            }

            vel = GeneralMethods.getDirection(tb.getLocation(), targetLocation).normalize().multiply(2).add(new Vector(0, 0.2, 0));
        }

        for (TempBlock tb : readyBlocksTracker) {
            // The physical server state is hidden from the predicting owner.
            // Spawn from the exact registered layer data so Fabric and Paper
            // render the same shard even when another layer occupies the
            // coordinate physically.
            fallingBlocks.add(new TempFallingBlock(tb.getLocation(), tb.getBlockData(), vel, this));
        }

        // Revert every source/raised block exactly once. readyBlocksTracker is
        // also handled by revertBlocks(); reverting it in both loops produced
        // duplicate authoritative operations during rapid throws.
        revertBlocks();

        isThrown = true;
        bufferedShootUntil = 0;

        if (player.isOnline()) {
            bPlayer.addCooldown(this);
        }
    }

    private boolean createReadyBlock(final Location destination, final BlockData data) {
        if (destination == null || destination.getWorld() == null || data == null) return false;
        final Block destinationBlock = destination.getBlock();
        final TempBlock readyBlock;
        try {
            readyBlock = new TempBlock(destinationBlock, data, this);
        } catch (RuntimeException failure) {
            return false;
        }
        if (readyBlock.isReverted() || TempBlock.get(destinationBlock) != readyBlock) {
            if (!readyBlock.isReverted()) readyBlock.revertBlock();
            return false;
        }
        this.readyBlocksTracker.add(readyBlock);
        return true;
    }

    private void forgetRisingShard(final TempFallingBlock fallingBlock) {
        this.readyHandoffAttempts.remove(fallingBlock);
        this.riseTicks.remove(fallingBlock);
        this.riseTickLimits.remove(fallingBlock);
    }

    private void pruneOrphanedSources() {
        final List<TempFallingBlock> rising = TempFallingBlock.getFromAbility(this);
        this.readyHandoffAttempts.keySet().removeIf(fallingBlock -> !rising.contains(fallingBlock));
        this.riseTicks.keySet().removeIf(fallingBlock -> !rising.contains(fallingBlock));
        this.riseTickLimits.keySet().removeIf(fallingBlock -> !rising.contains(fallingBlock));
        final Iterator<TempBlock> iterator = this.tblockTracker.iterator();
        while (iterator.hasNext()) {
            final TempBlock sourceLayer = iterator.next();
            if (sourceLayer == null || sourceLayer.isReverted()) {
                iterator.remove();
                continue;
            }
            final Block sourceBlock = sourceLayer.getBlock();
            if (this.hasReadyShardInColumn(sourceBlock) || hasRisingShardInColumn(rising, sourceBlock)) continue;
            sourceLayer.revertBlock();
            iterator.remove();
        }
    }

    private boolean hasReadyShardInColumn(final Block sourceBlock) {
        for (final TempBlock readyBlock : this.readyBlocksTracker) {
            if (readyBlock != null && !readyBlock.isReverted()
                    && sameColumn(readyBlock.getLocation(), sourceBlock)) return true;
        }
        return false;
    }

    private static boolean hasRisingShardInColumn(
            final List<TempFallingBlock> rising,
            final Block sourceBlock) {
        for (final TempFallingBlock tempFallingBlock : rising) {
            if (tempFallingBlock == null || tempFallingBlock.getFallingBlock() == null
                    || tempFallingBlock.getFallingBlock().isDead()) continue;
            if (sameColumn(tempFallingBlock.getLocation(), sourceBlock)) return true;
        }
        return false;
    }

    private void bufferThrow(boolean allowBuffer) {
        if (!allowBuffer || shootBuffer <= 0) {
            return;
        }

        bufferedShootUntil = Math.max(bufferedShootUntil, System.currentTimeMillis() + shootBuffer);
    }

    private boolean hasBufferedShoot() {
        if (bufferedShootUntil <= 0) {
            return false;
        }

        if (System.currentTimeMillis() > bufferedShootUntil) {
            bufferedShootUntil = 0;
            return false;
        }

        return true;
    }

    public void revertBlocks() {
        bufferedShootUntil = 0;
        this.readyHandoffAttempts.clear();
        this.riseTicks.clear();
        this.riseTickLimits.clear();

        for (TempBlock tb : tblockTracker) {
            tb.revertBlock();
        }

        for (TempBlock tb : readyBlocksTracker) {
            tb.revertBlock();
        }

        tblockTracker.clear();
        readyBlocksTracker.clear();
    }

    @Override
    public void remove() {
        // Destroy any remaining falling blocks.
        for (TempFallingBlock tfb : TempFallingBlock.getFromAbility(this)) {
            tfb.remove();
        }

        revertBlocks();

        super.remove();
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    public static void setCooldown(long cooldown) {
        EarthShard.cooldown = cooldown;
    }

    @Override
    public Location getLocation() {
        return null;
    }

    @Override
    public List<Location> getLocations() {
        return fallingBlocks.stream().map(TempFallingBlock::getLocation).collect(Collectors.toList());
    }

    @Override
    public void handleCollision(Collision collision) {
        CollisionUtil.handleFallingBlockCollisions(collision, fallingBlocks);
    }

    @Override
    public double getCollisionRadius() {
        return abilityCollisionRadius;
    }

    @Override
    public List<Location> getEntityHitLocations() {
        return getLocations();
    }

    @Override
    public double getEntityHitRadius() {
        return entityCollisionRadius;
    }

    @Override
    public String getName() {
        return "EarthShard";
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public String getAuthor() {
        return JedCore.dev;
    }

    @Override
    public String getVersion() {
        return JedCore.version;
    }

    @Override
    public String getDescription() {
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Earth.EarthShard.Description");
    }

    public boolean isThrown() {
        return isThrown;
    }

    public void setThrown(boolean thrown) {
        isThrown = thrown;
    }

    public Location getOrigin() {
        return origin;
    }

    public void setOrigin(Location origin) {
        this.origin = origin;
    }

    public double getAbilityCollisionRadius() {
        return abilityCollisionRadius;
    }

    public void setAbilityCollisionRadius(double abilityCollisionRadius) {
        this.abilityCollisionRadius = abilityCollisionRadius;
    }

    public double getEntityCollisionRadius() {
        return entityCollisionRadius;
    }

    public void setEntityCollisionRadius(double entityCollisionRadius) {
        this.entityCollisionRadius = entityCollisionRadius;
    }

    public List<TempBlock> getTblockTracker() {
        return tblockTracker;
    }

    public List<TempBlock> getReadyBlocksTracker() {
        return readyBlocksTracker;
    }

    public List<TempFallingBlock> getFallingBlocks() {
        return fallingBlocks;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.EarthShard.Enabled");
    }
}
