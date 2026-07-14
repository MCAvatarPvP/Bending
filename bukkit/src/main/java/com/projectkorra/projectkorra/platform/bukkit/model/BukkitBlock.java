package com.projectkorra.projectkorra.platform.bukkit.model;

import com.projectkorra.projectkorra.platform.model.PKBlock;
import com.projectkorra.projectkorra.platform.model.PKLocation;
import com.projectkorra.projectkorra.platform.model.PKWorld;
import org.bukkit.block.Block;

public final class BukkitBlock implements PKBlock {
    private final Block block;

    public BukkitBlock(final Block block) {
        this.block = block;
    }

    @Override
    public Object handle() {
        return this.block;
    }

    @Override
    public PKWorld world() {
        return new BukkitWorld(this.block.getWorld());
    }

    @Override
    public int x() {
        return this.block.getX();
    }

    @Override
    public int y() {
        return this.block.getY();
    }

    @Override
    public int z() {
        return this.block.getZ();
    }

    @Override
    public String materialKey() {
        return this.block.getType().getKey().toString();
    }

    @Override
    public boolean liquid() {
        return this.block.isLiquid();
    }

    @Override
    public boolean passable() {
        return this.block.isPassable();
    }

    @Override
    public PKLocation location() {
        return new BukkitLocation(this.block.getLocation());
    }
}
