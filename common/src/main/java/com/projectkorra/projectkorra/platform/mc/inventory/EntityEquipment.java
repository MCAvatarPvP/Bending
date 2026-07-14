package com.projectkorra.projectkorra.platform.mc.inventory;

public class EntityEquipment {
    private ItemStack[] armor = new ItemStack[4];
    private ItemStack hand = new ItemStack();

    public ItemStack[] getArmorContents() {
        return armor.clone();
    }

    public void setArmorContents(ItemStack[] value) {
        armor = value == null ? new ItemStack[4] : value.clone();
    }

    public ItemStack getItemInMainHand() {
        return hand;
    }

    public void setItemInMainHand(ItemStack item) {
        hand = item;
    }

    public ItemStack getHelmet() {
        return armor.length > 3 ? armor[3] : null;
    }

    public void setHelmet(ItemStack item) {
        if (armor.length < 4) armor = new ItemStack[4];
        armor[3] = item;
    }
}
