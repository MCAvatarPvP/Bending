package com.projectkorra.projectkorra.platform.mc.block;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;

import java.util.Collection;
import java.util.List;

public class Block {
    public Material getType() {
        return Material.AIR;
    }

    public void setType(Material m) {
    }

    public void setType(Material m, boolean physics) {
    }

    public Location getLocation() {
        return new Location();
    }

    public World getWorld() {
        return new World();
    }

    public Block getRelative(BlockFace face) {
        return new Block();
    }

    public Block getRelative(BlockFace face, int distance) {
        return new Block();
    }

    public BlockData getBlockData() {
        return new BlockData();
    }

    public void setBlockData(BlockData data) {
    }

    public void setBlockData(BlockData data, boolean physics) {
    }

    public BlockState getState() {
        return new BlockState();
    }

    public int getX() {
        return getLocation().getBlockX();
    }

    public int getY() {
        return getLocation().getBlockY();
    }

    public int getZ() {
        return getLocation().getBlockZ();
    }

    public boolean isLiquid() {
        return false;
    }

    public boolean isSolid() {
        return getType().isSolid();
    }

    public BoundingBox getBoundingBox() {
        return new BoundingBox();
    }

    public boolean breakNaturally() {
        return true;
    }

    public boolean breakNaturally(ItemStack item) {
        return true;
    }

    public Collection<ItemStack> getDrops() {
        return List.of();
    }

    public boolean isPassable() {
        return !isSolid();
    }

    public byte getLightLevel() {
        return 0;
    }

    public Block getRelative(int x, int y, int z) {
        return new Block();
    }

    public boolean isEmpty() {
        return getType() == Material.AIR;
    }

    public byte getData() {
        return 0;
    }

    public BlockFace getFace(Block block) {
        return BlockFace.SELF;
    }

    public Biome getBiome() {
        return Biome.DESERT;
    }

    public Object handle() {
        return this;
    }
}
