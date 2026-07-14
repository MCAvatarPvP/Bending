package com.projectkorra.projectkorra.platform.mc.block.data.type;

import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;

public class Switch extends BlockData {
    private boolean powered;

    public boolean isPowered() {
        return powered;
    }

    public void setPowered(boolean value) {
        powered = value;
    }
}
