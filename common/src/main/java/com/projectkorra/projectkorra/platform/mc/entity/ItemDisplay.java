package com.projectkorra.projectkorra.platform.mc.entity;

import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;

public class ItemDisplay extends Display {
    public ItemStack getItemStack() {
        return new ItemStack();
    }

    public void setItemStack(ItemStack item) {
    }

    public void setItemDisplayTransform(ItemDisplayTransform transform) {
    }

    public enum ItemDisplayTransform {
        NONE,
        THIRDPERSON_LEFTHAND,
        THIRDPERSON_RIGHTHAND,
        FIRSTPERSON_LEFTHAND,
        FIRSTPERSON_RIGHTHAND,
        HEAD,
        GUI,
        GROUND,
        FIXED
    }
}
