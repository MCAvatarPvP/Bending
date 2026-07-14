package com.projectkorra.projectkorra.platform.mc.block.data;

import com.projectkorra.projectkorra.platform.mc.Material;

public class BlockData implements Cloneable {
    private final Material material;

    public BlockData() {
        this(Material.AIR);
    }

    public BlockData(Material material) {
        this.material = material;
    }

    public Material getMaterial() {
        return material;
    }

    public String getAsString() {
        return material.name().toLowerCase();
    }

    @Override
    public BlockData clone() {
        return new BlockData(material);
    }
}
