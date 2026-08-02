package com.projectkorra.projectkorra.earthbending;

import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.ClickType;

import java.util.HashSet;
import java.util.Set;

public class RaiseEarthWall extends EarthAbility {

    @Attribute(Attribute.SELECT_RANGE)
    private int selectRange;
    @Attribute(Attribute.HEIGHT)
    private int height;
    @Attribute(Attribute.WIDTH)
    private int width;
    private double speed;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private Location location;
    private Vector wallDirection;

    public RaiseEarthWall(final Player player) {
        super(player);
        this.selectRange = getConfig().getInt("Abilities.Earth.RaiseEarth.Wall.SelectRange");
        this.height = getConfig().getInt("Abilities.Earth.RaiseEarth.Wall.Height");
        this.width = getConfig().getInt("Abilities.Earth.RaiseEarth.Wall.Width");
        this.speed = getConfig().getInt("Abilities.Earth.RaiseEarth.Wall.Speed");
        this.cooldown = getConfig().getLong("Abilities.Earth.RaiseEarth.Wall.Cooldown");

        if (!this.bPlayer.canBend(this) || this.bPlayer.isOnCooldown("RaiseEarthWall")) {
            return;
        }

        if (!this.captureWall()) {
            return;
        }
        this.start();
    }

    /**
     * Captures the wall geometry on the input frame. Reading the player's
     * source and eye direction from the later progress tick makes Paper use a
     * latency-shifted pose and can produce a different set of wall columns.
     */
    private boolean captureWall() {
        final Vector direction = this.player.getEyeLocation().getDirection().normalize();
        direction.setY(0);

        Vector orthogonal = new Vector(-direction.getZ(), 0, direction.getX());
        orthogonal = getDegreeRoundedVector(orthogonal.normalize(), 0.25);

        final Block selected = BlockSource.getEarthSourceBlock(
                this.player, this.selectRange, ClickType.SHIFT_DOWN);
        final Block source = selected == null
                ? this.getTargetEarthBlock(this.selectRange) : selected;
        if (source == null || orthogonal == null) {
            return false;
        }

        this.location = source.getLocation();
        this.wallDirection = orthogonal;
        return true;
    }

    private static Vector getDegreeRoundedVector(Vector vec, final double degreeIncrement) {
        if (vec == null) {
            return null;
        }
        vec = vec.normalize();
        final double[] dims = {vec.getX(), vec.getY(), vec.getZ()};

        for (int i = 0; i < dims.length; i++) {
            final double dim = dims[i];
            final int sign = dim >= 0 ? 1 : -1;
            final int dimDivIncr = (int) (dim / degreeIncrement);

            final double lowerBound = dimDivIncr * degreeIncrement;
            final double upperBound = (dimDivIncr + (1 * sign)) * degreeIncrement;

            if (Math.abs(dim - lowerBound) < Math.abs(dim - upperBound)) {
                dims[i] = lowerBound;
            } else {
                dims[i] = upperBound;
            }
        }
        return new Vector(dims[0], dims[1], dims[2]);
    }

    @Override
    public String getName() {
        return "RaiseEarth";
    }

    @Override
    public void progress() {
        if (this.location == null || this.wallDirection == null) {
            this.remove();
            return;
        }

        final World world = this.location.getWorld();
        final Vector orth = this.wallDirection.clone();
        boolean shouldAddCooldown = false;
        final Set<Block> startedSources = new HashSet<>();

        for (int i = 0; i < this.width; i++) {
            final double adjustedI = i - this.width / 2.0;
            Block block = world.getBlockAt(this.location.clone().add(orth.clone().multiply(adjustedI)));

            if (this.isTransparent(block)) {
                for (int j = 1; j < this.height; j++) {
                    block = block.getRelative(BlockFace.DOWN);
                    if (this.isEarthbendable(block)) {
                        shouldAddCooldown |= this.raiseColumn(block, startedSources);
                        // This is a surface search for one wall column. Without
                        // stopping here, every bendable block below the first
                        // surface starts another overlapping RaiseEarth pillar,
                        // multiplying source-air writes and leaving ghost holes.
                        break;
                    } else if (!this.isTransparent(block)) {
                        break;
                    }
                }
            } else if (this.isEarthbendable(block.getRelative(BlockFace.UP))) {
                for (int j = 1; j < this.height; j++) {
                    block = block.getRelative(BlockFace.UP);
                    if (this.isTransparent(block)) {
                        shouldAddCooldown |= this.raiseColumn(
                                block.getRelative(BlockFace.DOWN), startedSources);
                        // Only the first air block above the surface identifies
                        // this column's source.
                        break;
                    } else if (!this.isEarthbendable(block)) {
                        break;
                    }
                }
            } else if (this.isEarthbendable(block)) {
                shouldAddCooldown |= this.raiseColumn(block, startedSources);
            }
        }

        if (shouldAddCooldown) {
            this.bPlayer.addCooldown("RaiseEarthWall", this.cooldown);
        }
        this.remove();
    }

    private boolean raiseColumn(final Block source, final Set<Block> startedSources) {
        if (source == null || startedSources == null || !startedSources.add(source)) {
            return false;
        }
        final RaiseEarth raiseEarth = new RaiseEarth(
                this.player, source.getLocation(), this.height, this.speed);
        raiseEarth.setRaisedByWall(true);
        raiseEarth.setNoiseReduction(this.width / 2);
        return true;
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

    public int getRange() {
        return this.selectRange;
    }

    public void setRange(final int range) {
        this.selectRange = range;
    }

    public int getHeight() {
        return this.height;
    }

    public void setHeight(final int height) {
        this.height = height;
    }

    public int getWidth() {
        return this.width;
    }

    public void setWidth(final int width) {
        this.width = width;
    }

    public int getSelectRange() {
        return this.selectRange;
    }

    public void setSelectRange(final int selectRange) {
        this.selectRange = selectRange;
    }
}
