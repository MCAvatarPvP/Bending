package com.projectkorra.projectkorra.platform.mc.block.data;

import com.projectkorra.projectkorra.platform.mc.Material;

public class BlockData implements Cloneable {
    private final Material material;
    private String exactState;

    public BlockData() {
        this(Material.AIR);
    }

    public BlockData(Material material) {
        this.material = material;
    }

    public Material getMaterial() {
        return material;
    }

    /**
     * Returns the platform-neutral, namespaced block-state string captured at
     * the adapter boundary. This is intentionally optional: data constructed
     * by an ability represents the material's default state unless the ability
     * explicitly configures one of the common mutable data types.
     */
    public String getExactState() {
        return exactState;
    }

    public void setExactState(final String value) {
        exactState = value == null || value.isBlank() ? null : value.trim();
    }

    public String getAsString() {
        return exactState == null ? material.name().toLowerCase() : exactState;
    }

    @Override
    public BlockData clone() {
        final BlockData copy = new BlockData(material);
        copy.exactState = exactState;
        return copy;
    }
}
