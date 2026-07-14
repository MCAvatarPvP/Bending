package com.projectkorra.projectkorra.earthbending;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.TempBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RaiseEarth extends EarthAbility {

    private static final Set<Block> WALL_BLOCKS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Block, Integer> ACTIVE_BLOCKS = new ConcurrentHashMap<>();

    private int distance;
    @Attribute(Attribute.HEIGHT)
    private int height;
    private long time;
    private long interval;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.SELECT_RANGE)
    private double selectRange;
    @Attribute(Attribute.SPEED)
    private double speed;
    private Block block;
    private Vector direction;
    private Location origin;
    private Location location;
    private boolean raisedByWall;
    private ConcurrentHashMap<Block, Block> affectedBlocks;
    private Set<Block> wallBlocks;

    public RaiseEarth(final Player player) {
        super(player);
        this.setFields();

        if (!this.bPlayer.canBend(this) || this.bPlayer.isOnCooldown("RaiseEarthPillar")) {
            return;
        }

        try {
            this.recalculateAttributes();
            this.block = BlockSource.getEarthSourceBlock(player, this.selectRange, ClickType.LEFT_CLICK);
            if (this.block == null) {
                return;
            }

            this.origin = this.block.getLocation();
            this.location = this.origin.clone();
            this.distance = this.getEarthbendableBlocksLength(this.block, this.direction.clone().multiply(-1), this.height);
        } catch (final IllegalStateException e) {
            return;
        }

        this.loadAffectedBlocks();

        if (this.distance != 0 && this.canInstantiate()) {
            this.bPlayer.addCooldown("RaiseEarthPillar", this.cooldown);
            this.time = System.currentTimeMillis() - this.interval;
            this.start();
        } else {
            this.discardAffectedBlocks();
        }
    }

    public RaiseEarth(final Player player, final Location origin) {
        this(player, origin, ConfigManager.getConfig(BendingPlayer.getBendingPlayer(player)).getInt("Abilities.Earth.RaiseEarth.Column.Height"));
    }

    public RaiseEarth(final Player player, final Location origin, final int height) {
        super(player);
        this.setFields();

        this.height = height;
        this.origin = origin;
        this.location = origin.clone();
        this.block = this.location.getBlock();
        this.recalculateAttributes(); // Recalculate attributes to get the correct height
        this.distance = this.getEarthbendableBlocksLength(this.block, this.direction.clone().multiply(-1), height);

        this.loadAffectedBlocks();

        if (this.distance != 0 && this.canInstantiate()) {
            this.time = System.currentTimeMillis() - this.interval;
            this.start();
        } else {
            this.discardAffectedBlocks();
        }
    }

    public RaiseEarth(final Player player, final Location origin, final int height, final double speed) {
        super(player);
        this.setFields();

        this.height = height;
        this.speed = speed;
        this.interval = (long) (1000.0 / this.speed);
        this.origin = origin;
        this.location = origin.clone();
        this.block = this.location.getBlock();
        this.distance = this.getEarthbendableBlocksLength(this.block, this.direction.clone().multiply(-1), height);

        this.loadAffectedBlocks();

        if (this.distance != 0 && this.canInstantiate()) {
            this.time = System.currentTimeMillis() - this.interval;
            this.start();
        } else {
            this.discardAffectedBlocks();
        }
    }

    public static boolean blockInAllAffectedBlocks(final Block block) {
        return block != null && ACTIVE_BLOCKS.containsKey(block);
    }

    public static boolean isBlockingAbility(final Block block) {
        return blockInAllAffectedBlocks(block);
    }

    public static boolean blockInWallAffectedBlocks(final Block block) {
        if (containsWallBlock(block)) {
            return true;
        }

        for (final RaiseEarth raiseEarth : getAbilities(RaiseEarth.class)) {
            if (raiseEarth.raisedByWall && containsAffectedBlock(raiseEarth, block)) {
                return true;
            }
        }
        return false;
    }

    public static void revertWallAffectedBlock(final Block block) {
        Block matched = null;
        for (final Block wallBlock : WALL_BLOCKS) {
            if (isSameBlock(wallBlock, block)) {
                matched = wallBlock;
                break;
            }
        }
        if (matched != null) {
            WALL_BLOCKS.remove(matched);
        }

        for (final RaiseEarth raiseEarth : getAbilities(RaiseEarth.class)) {
            raiseEarth.removeTrackedWallBlock(block);
        }
    }

    public static void revertAffectedBlock(final Block block) {
        for (final RaiseEarth raiseEarth : getAbilities(RaiseEarth.class)) {
            Block matched = null;
            for (final Block affected : raiseEarth.affectedBlocks.keySet()) {
                if (isSameBlock(affected, block)) {
                    matched = affected;
                    break;
                }
            }
            if (matched != null) {
                raiseEarth.affectedBlocks.remove(matched);
                untrackActiveBlock(matched);
            }
        }
    }

    private static void untrackActiveBlock(final Block block) {
        ACTIVE_BLOCKS.computeIfPresent(block, (ignored, references) -> references > 1 ? references - 1 : null);
    }

    private static boolean containsAffectedBlock(final RaiseEarth raiseEarth, final Block block) {
        for (final Block affected : raiseEarth.affectedBlocks.keySet()) {
            if (isSameBlock(affected, block)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWallBlock(final Block block) {
        for (final Block wallBlock : WALL_BLOCKS) {
            if (isSameBlock(wallBlock, block)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSameBlock(final Block first, final Block second) {
        return first.getX() == second.getX()
                && first.getY() == second.getY()
                && first.getZ() == second.getZ()
                && first.getWorld().equals(second.getWorld());
    }

    private void setFields() {
        this.speed = getConfig().getDouble("Abilities.Earth.RaiseEarth.Speed");
        this.height = getConfig().getInt("Abilities.Earth.RaiseEarth.Column.Height");
        this.selectRange = getConfig().getDouble("Abilities.Earth.RaiseEarth.Column.SelectRange");
        this.cooldown = getConfig().getLong("Abilities.Earth.RaiseEarth.Column.Cooldown");
        this.direction = new Vector(0, 1, 0);
        this.interval = (long) (1000.0 / this.speed);
        this.affectedBlocks = new ConcurrentHashMap<>();
        this.wallBlocks = ConcurrentHashMap.newKeySet();
    }

    private boolean canInstantiate() {
        for (final Block block : this.affectedBlocks.keySet()) {
            if (!this.isEarthbendable(block) || (TempBlock.isTempBlock(block) && !EarthAbility.isBendableEarthTempBlock(block))) {
                return false;
            }
        }
        return true;
    }

    private void loadAffectedBlocks() {
        if (this.raisedByWall) {
            this.clearTrackedWallBlocks();
        }

        this.untrackAffectedBlocks();
        this.affectedBlocks.clear();
        Block thisBlock;
        for (int i = 0; i <= this.distance; i++) {
            thisBlock = this.block.getWorld().getBlockAt(this.location.clone().add(this.direction.clone().multiply(-i)));
            this.affectedBlocks.put(thisBlock, thisBlock);
            ACTIVE_BLOCKS.merge(thisBlock, 1, Integer::sum);
            if (Collapse.blockInAllAffectedBlocks(thisBlock)) {
                Collapse.revert(thisBlock);
            }
        }

        if (this.raisedByWall) {
            this.trackWallBlocks();
        }
    }

    @Override
    public void progress() {
        if (System.currentTimeMillis() - this.time >= this.interval) {
            this.time = System.currentTimeMillis();
            final Block block = this.location.getBlock();
            this.location = this.location.add(this.direction);
            if (!block.isLiquid()) {
                this.moveEarth(block, this.direction, this.distance);
            }

            this.loadAffectedBlocks();

            if (this.location.distanceSquared(this.origin) >= this.distance * this.distance) {
                this.remove();
                return;
            }
        }
    }

    private void untrackAffectedBlocks() {
        for (final Block affected : this.affectedBlocks.keySet()) {
            untrackActiveBlock(affected);
        }
    }

    private void discardAffectedBlocks() {
        this.untrackAffectedBlocks();
        this.affectedBlocks.clear();
    }

    private void trackWallBlocks() {
        for (final Block affected : this.affectedBlocks.keySet()) {
            this.wallBlocks.add(affected);
            WALL_BLOCKS.add(affected);
        }
    }

    private void clearTrackedWallBlocks() {
        for (final Block wallBlock : this.wallBlocks) {
            RaiseEarth.revertWallAffectedBlock(wallBlock);
        }
        this.wallBlocks.clear();
    }

    private void removeTrackedWallBlock(final Block block) {
        Block matched = null;
        for (final Block wallBlock : this.wallBlocks) {
            if (isSameBlock(wallBlock, block)) {
                matched = wallBlock;
                break;
            }
        }
        if (matched != null) {
            this.wallBlocks.remove(matched);
        }
    }

    @Override
    public String getName() {
        return "RaiseEarth";
    }

    @Override
    public Location getLocation() {
        return this.location;
    }

    public void setLocation(final Location location) {
        this.location = location;
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
    public List<Location> getLocations() {
        final ArrayList<Location> locations = new ArrayList<>();
        for (final Block block : this.affectedBlocks.values()) {
            locations.add(block.getLocation());
        }
        return locations;
    }

    public int getDistance() {
        return this.distance;
    }

    public void setDistance(final int distance) {
        this.distance = distance;
    }

    public int getHeight() {
        return this.height;
    }

    public void setHeight(final int height) {
        this.height = height;
    }

    public long getTime() {
        return this.time;
    }

    public void setTime(final long time) {
        this.time = time;
    }

    public long getInterval() {
        return this.interval;
    }

    public void setInterval(final long interval) {
        this.interval = interval;
    }

    public double getSpeed() {
        return this.speed;
    }

    public void setSpeed(final double speed) {
        this.speed = speed;
    }

    public Block getBlock() {
        return this.block;
    }

    public void setBlock(final Block block) {
        this.block = block;
    }

    public Vector getDirection() {
        return this.direction;
    }

    public void setDirection(final Vector direction) {
        this.direction = direction;
    }

    public Location getOrigin() {
        return this.origin;
    }

    public void setOrigin(final Location origin) {
        this.origin = origin;
    }

    public ConcurrentHashMap<Block, Block> getAffectedBlocks() {
        return this.affectedBlocks;
    }

    @Override
    public void remove() {
        this.discardAffectedBlocks();
        this.clearTrackedWallBlocks();
        super.remove();
    }

    public boolean isRaisedByWall() {
        return this.raisedByWall;
    }

    public void setRaisedByWall(final boolean raisedByWall) {
        if (this.raisedByWall && !raisedByWall) {
            this.clearTrackedWallBlocks();
        }

        this.raisedByWall = raisedByWall;
        if (this.raisedByWall) {
            this.clearTrackedWallBlocks();
            this.trackWallBlocks();
        }
    }

    public double getSelectRange() {
        return this.selectRange;
    }

    public void setSelectRange(final double selectRange) {
        this.selectRange = selectRange;
    }
}
