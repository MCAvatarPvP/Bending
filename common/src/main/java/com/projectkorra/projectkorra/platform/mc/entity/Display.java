package com.projectkorra.projectkorra.platform.mc.entity;

import com.projectkorra.projectkorra.platform.mc.util.Transformation;

public class Display extends Entity {
    public void setBrightness(Brightness brightness) {
    }

    public void setTransformation(Transformation transformation) {
    }

    public void setBillboard(Billboard billboard) {
    }

    public enum Billboard {FIXED, CENTER, VERTICAL, HORIZONTAL}

    public record Brightness(int blockLight, int skyLight) {
    }
}
