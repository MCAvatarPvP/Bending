package com.projectkorra.projectkorra.platform.mc.block.data;

import com.projectkorra.projectkorra.platform.mc.Material;

public class Levelled extends BlockData implements Waterlogged {
    private int level;
    private boolean waterlogged;

    public Levelled() {
    }

    public Levelled(Material material) {
        super(material);
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int value) {
        level = value;
    }

    public int getMaximumLevel() {
        return 15;
    }

    public boolean isWaterlogged() {
        return waterlogged;
    }

    public void setWaterlogged(boolean value) {
        waterlogged = value;
    }

    @Override
    public Levelled clone() {
        Levelled copy = new Levelled(getMaterial());
        copy.level = level;
        copy.waterlogged = waterlogged;
        return copy;
    }
}
