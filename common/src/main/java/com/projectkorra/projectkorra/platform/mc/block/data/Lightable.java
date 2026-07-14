package com.projectkorra.projectkorra.platform.mc.block.data;

public class Lightable extends BlockData {
    private boolean lit;

    public boolean isLit() {
        return lit;
    }

    public void setLit(boolean value) {
        lit = value;
    }
}
