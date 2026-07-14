package com.projectkorra.projectkorra.platform.mc.block.data.type;

import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;

public class Campfire extends BlockData {
    private boolean lit;

    public boolean isLit() {
        return lit;
    }

    public void setLit(boolean value) {
        lit = value;
    }
}
