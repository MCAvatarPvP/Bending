package com.projectkorra.projectkorra.platform.mc.potion;

public class PotionData {
    private final PotionType type;

    public PotionData() {
        this(PotionType.WATER);
    }

    public PotionData(PotionType type) {
        this.type = type;
    }

    public PotionType getType() {
        return type;
    }
}
