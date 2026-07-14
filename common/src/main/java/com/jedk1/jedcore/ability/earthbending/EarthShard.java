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
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import com.projectkorra.projectkorra.util.colliders.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class EarthShard extends EarthAbility implements AddonAbility {
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
        animationSpeed = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.EarthShard.AnimationSpeed");
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

    public void raiseEarthBlock(Block block) {
        if (block == null) {
            return;
        }

        // A rapidly repeated selection can ray trace the raised grass/dirt from
        // this (or another) shard before its authoritative revert arrives. That
        // temporary display block must never become a new earth source: doing so
        // stacks TempBlocks with different originals and can restore grass in air.
        if (TempBlock.isTempBlock(block)) {
            return;
        }

        if (tblockTracker.size() >= maxShards) {
            return;
        }

        Vector blockVector = block.getLocation().toVector().toBlockVector().setY(0);

        // Don't select from locations that already have an EarthShard block.
        for (TempBlock tempBlock : tblockTracker) {
            if (tempBlock.getLocation().getWorld() != block.getWorld()) {
                continue;
            }

            Vector tempBlockVector = tempBlock.getLocation().toVector().toBlockVector().setY(0);

            if (tempBlockVector.equals(blockVector)) {
                return;
            }
        }

        for (int i = 1; i < 4; i++) {
            if (!isTransparent(block.getRelative(BlockFace.UP, i))) {
                return;
            }
        }

        if (isEarthbendable(block)) {
            if (isMetal(block)) {
                playMetalbendingSound(block.getLocation());
            } else {
                ParticleEffect.BLOCK_CRACK.display(block.getLocation().add(0, 1, 0), 20, 0.0, 0.0, 0.0, 0.0, block.getBlockData());
                playEarthbendingSound(block.getLocation());
            }

            Material material = getCorrectType(block);

            if (DensityShift.isPassiveSand(block)) {
                DensityShift.revertSand(block);
            }

            Location loc = block.getLocation().add(0.5, 0, 0.5);
            new TempFallingBlock(loc, material.createBlockData(), new Vector(0, animationSpeed, 0), this);
            TempBlock tb = new TempBlock(block, Material.AIR.createBlockData(), this);
            tblockTracker.add(tb);
        }
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

        if (!isThrown) {
            if (!bPlayer.canBendIgnoreCooldowns(this)) {
                remove();
                return;
            }

            if (tblockTracker.isEmpty()) {
                remove();
                return;
            }

            for (TempFallingBlock tfb : TempFallingBlock.getFromAbility(this)) {
                FallingBlock fb = tfb.getFallingBlock();

                if (fb.isDead() || fb.getLocation().getBlockY() == origin.getBlockY() + 2) {
                    TempBlock tb = new TempBlock(fb.getLocation().getBlock(), fb.getBlockData(), this);
                    readyBlocksTracker.add(tb);
                    tfb.remove();
                }
            }

            if (hasBufferedShoot()) {
                throwShard(false);
            }
        } else {
            for (TempFallingBlock tfb : TempFallingBlock.getFromAbility(this)) {
                FallingBlock fb = tfb.getFallingBlock();
                if (maxDistance != 0 && origin.distance(fb.getLocation()) > maxDistance) {
                    tfb.remove();
                }

                AABB collider = BlockUtil.getFallingBlockBoundsFull(fb, entityCollisionRadius);

                CollisionDetector.checkEntityCollisions(player, collider, (e) -> {
                    DamageHandler.damageEntity(e, isMetal(fb.getBlockData().getMaterial()) ? metalDmg : normalDmg, this);
                    ((LivingEntity) e).setNoDamageTicks(0);
                    ParticleEffect.BLOCK_CRACK.display(fb.getLocation(), 20, 0, 0, 0, 0, fb.getBlockData());
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
        boolean notReady = tblockTracker.size() > readyBlocksTracker.size();

        if (isThrown) {
            return;
        }

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

                TempBlock tb = new TempBlock(new Location(
                        origin.getWorld(),
                        fb.getLocation().getBlockX(), origin.getBlockY() + 2, fb.getLocation().getBlockZ()).getBlock(), fb.getBlockData(), this);
                readyBlocksTracker.add(tb);
                tfb.remove();
            }
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
            fallingBlocks.add(new TempFallingBlock(tb.getLocation(), tb.getBlock().getBlockData(), vel, this));
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
