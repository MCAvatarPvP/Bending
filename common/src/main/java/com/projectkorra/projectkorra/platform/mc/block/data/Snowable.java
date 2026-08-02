package com.projectkorra.projectkorra.platform.mc.block.data;

import com.projectkorra.projectkorra.platform.mc.Material;

public class Snowable extends BlockData {
    private boolean snowy;

    public Snowable() {
    }

    public Snowable(final Material material) {
        super(material);
    }

    public boolean isSnowy() {
        return snowy;
    }

    public void setSnowy(boolean value) {
        snowy = value;
    }

    @Override
    public Snowable clone() {
        final Snowable copy = new Snowable(getMaterial());
        copy.snowy = snowy;
        return copy;
    }
}
