package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.*;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import java.util.*;

/** Portable block identity. Contents and mutations always resolve through the rebound world's current terrain. */
public final class RollbackBlockReference extends Block implements BlockValue, RollbackBlockSnapshot.Target {
    private final World world;
    private final int x, y, z;
    public RollbackBlockReference(World world, int x, int y, int z) {
        this.world = Objects.requireNonNull(world); this.x = x; this.y = y; this.z = z;
    }
    private Block view() {
        Block block = Objects.requireNonNull(world.getBlockAt(x, y, z));
        if (block instanceof RollbackBlockReference) throw new IllegalStateException("Block reference has no backing world view");
        return block;
    }
    @Override public boolean restoreBlockSnapshot(BlockData data, byte[] blockEntity, boolean force, boolean physics) {
        if (!(view() instanceof RollbackBlockSnapshot.Target target))
            throw new IllegalStateException("World cannot restore a block snapshot");
        return target.restoreBlockSnapshot(data, blockEntity, force, physics);
    }
    @Override public World getWorld() { return world; }
    @Override public Location getLocation() { return new Location(world, x, y, z); }
    @Override public int getX() { return x; }
    @Override public int getY() { return y; }
    @Override public int getZ() { return z; }
    @Override public boolean equals(Object other) { return BlockValue.same(this, other); }
    @Override public int hashCode() { return BlockValue.hash(this); }
    @Override public Material getType() { return view().getType(); }
    @Override public void setType(Material m) { view().setType(m); }
    @Override public void setType(Material m, boolean physics) { view().setType(m, physics); }
    @Override public Block getRelative(BlockFace face) { return view().getRelative(face); }
    @Override public Block getRelative(BlockFace face, int distance) { return view().getRelative(face, distance); }
    @Override public BlockData getBlockData() { return view().getBlockData(); }
    @Override public void setBlockData(BlockData data) { view().setBlockData(data); }
    @Override public void setBlockData(BlockData data, boolean physics) { view().setBlockData(data, physics); }
    @Override public BlockState getState() { return view().getState(); }
    @Override public boolean isLiquid() { return view().isLiquid(); }
    @Override public boolean isSolid() { return view().isSolid(); }
    @Override public BoundingBox getBoundingBox() { return view().getBoundingBox(); }
    @Override public List<BoundingBox> getCollisionBoxes() { return view().getCollisionBoxes(); }
    @Override public boolean breakNaturally() { return view().breakNaturally(); }
    @Override public boolean breakNaturally(ItemStack item) { return view().breakNaturally(item); }
    @Override public Collection<ItemStack> getDrops() { return view().getDrops(); }
    @Override public boolean isPassable() { return view().isPassable(); }
    @Override public byte getLightLevel() { return view().getLightLevel(); }
    @Override public Block getRelative(int x, int y, int z) { return view().getRelative(x, y, z); }
    @Override public boolean isEmpty() { return view().isEmpty(); }
    @Override public byte getData() { return view().getData(); }
    @Override public BlockFace getFace(Block block) { return view().getFace(block); }
    @Override public Biome getBiome() { return view().getBiome(); }
    @Override public Object handle() { return view().handle(); }
}
