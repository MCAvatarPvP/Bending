package com.projectkorra.projectkorra.platform.mc.block.data.type;

import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;

public class Door extends BlockData {
    private boolean open;

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean value) {
        open = value;
    }

    public BlockFace getFacing() {
        return BlockFace.NORTH;
    }
}
