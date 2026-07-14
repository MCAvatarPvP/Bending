package com.projectkorra.projectkorra.platform.mc.block;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;

public class BlockState {
    public Material getType() {
        return Material.AIR;
    }

    public BlockData getBlockData() {
        return new BlockData();
    }

    public Block getBlock() {
        return new Block();
    }

    public Location getLocation() {
        return getBlock().getLocation();
    }

    public boolean update(boolean force, boolean physics) {
        return true;
    }

    public boolean update(boolean force) {
        return update(force, true);
    }

    public boolean update() {
        return update(false, true);
    }

    public Object handle() {
        return this;
    }
}
