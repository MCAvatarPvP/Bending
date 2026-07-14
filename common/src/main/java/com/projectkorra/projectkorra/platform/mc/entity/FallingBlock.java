package com.projectkorra.projectkorra.platform.mc.entity;

import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;

public class FallingBlock extends Entity {
    private BlockData data = new BlockData();

    public void setDropItem(boolean value) {
    }

    public void setHurtEntities(boolean value) {
    }

    public BlockData getBlockData() {
        return data;
    }
}
