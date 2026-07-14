package com.projectkorra.projectkorra.platform.mc.block.data.type;

import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;

public class TrapDoor extends BlockData {
    private boolean open;

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean value) {
        open = value;
    }
}
