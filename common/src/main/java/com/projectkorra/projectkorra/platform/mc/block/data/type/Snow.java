package com.projectkorra.projectkorra.platform.mc.block.data.type;

import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;

public class Snow extends BlockData {
    private int layers = 1;

    public int getLayers() {
        return layers;
    }

    public void setLayers(int value) {
        layers = value;
    }

    public int getMinimumLayers() {
        return 1;
    }
}
