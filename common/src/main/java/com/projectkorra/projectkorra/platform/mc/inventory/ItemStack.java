package com.projectkorra.projectkorra.platform.mc.inventory;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.ItemMeta;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.LeatherArmorMeta;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.PotionMeta;

public class ItemStack implements Cloneable {
    private Material type;
    private int amount;
    private short durability;
    private ItemMeta meta;

    public ItemStack() {
        this(null, 1);
    }

    public ItemStack(Object type) {
        this(type, 1);
    }

    public ItemStack(Object type, int amount) {
        this.type = type instanceof Material material ? material : null;
        this.amount = amount;
    }

    public Material getType() {
        return type;
    }

    public void setType(Material value) {
        type = value;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int value) {
        amount = value;
    }

    public short getDurability() {
        return durability;
    }

    public void setDurability(short value) {
        durability = value;
    }

    public boolean hasItemMeta() {
        return meta != null;
    }

    public ItemMeta getItemMeta() {
        if (meta == null) {
            meta = type == Material.POTION ? new PotionMeta() : new LeatherArmorMeta();
        }
        return meta;
    }

    public boolean setItemMeta(ItemMeta value) {
        meta = value;
        return true;
    }

    public boolean isSimilar(ItemStack other) {
        return other != null && type == other.type;
    }

    public ItemStack clone() {
        ItemStack copy = new ItemStack(type, amount);
        copy.durability = durability;
        copy.meta = meta;
        return copy;
    }
}
