package com.projectkorra.projectkorra.platform.mc.entity;

import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;

public class Item extends Entity {
    private ItemStack item = new ItemStack();

    public ItemStack getItemStack() {
        return item;
    }

    public void setItemStack(ItemStack value) {
        item = value;
    }

    public void setPickupDelay(int ticks) {
    }
}
