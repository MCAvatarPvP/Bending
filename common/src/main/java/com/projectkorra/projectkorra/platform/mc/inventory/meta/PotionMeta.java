package com.projectkorra.projectkorra.platform.mc.inventory.meta;

import com.projectkorra.projectkorra.platform.mc.potion.PotionData;

public class PotionMeta extends ItemMeta {
    private PotionData data = new PotionData();

    public PotionData getBasePotionData() {
        return data;
    }

    public void setBasePotionData(PotionData data) {
        this.data = data;
    }
}
