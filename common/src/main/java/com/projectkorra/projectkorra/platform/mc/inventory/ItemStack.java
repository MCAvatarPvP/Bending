package com.projectkorra.projectkorra.platform.mc.inventory;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.ItemMeta;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.LeatherArmorMeta;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.PotionMeta;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.SkullMeta;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemScope;

public class ItemStack implements Cloneable {
    private Material type;
    private int amount;
    private short durability;
    private ItemMeta meta;
    private final ItemStack simulationView;

    public ItemStack() {
        this(null, 1);
    }

    public ItemStack(Object type) {
        this(type, 1);
    }

    public ItemStack(Object type, int amount) {
        this.type = type instanceof Material material ? material : null;
        this.amount = amount;
        // Native platform/logical subclasses own their backing and must not recurse.
        this.simulationView = getClass() == ItemStack.class ? RollbackItemScope.createIfActive(this.type, amount) : null;
    }

    /** Resolves a scoped common constructor to its logical backing; other wrappers retain identity. */
    public final ItemStack simulationView() { return simulationView == null ? this : simulationView; }

    public Material getType() {
        if (simulationView != null) return simulationView.getType();
        return type;
    }

    public void setType(Material value) {
        if (simulationView != null) { simulationView.setType(value); return; }
        type = value;
    }

    public int getAmount() {
        if (simulationView != null) return simulationView.getAmount();
        return amount;
    }

    public void setAmount(int value) {
        if (simulationView != null) { simulationView.setAmount(value); return; }
        amount = value;
    }

    public short getDurability() {
        if (simulationView != null) return simulationView.getDurability();
        return durability;
    }

    public void setDurability(short value) {
        if (simulationView != null) { simulationView.setDurability(value); return; }
        durability = value;
    }

    public boolean hasItemMeta() {
        if (simulationView != null) return simulationView.hasItemMeta();
        return meta != null;
    }

    public ItemMeta getItemMeta() {
        if (simulationView != null) return simulationView.getItemMeta();
        if (meta == null) {
            meta = type == Material.POTION
                    ? new PotionMeta()
                    : type == Material.PLAYER_HEAD ? new SkullMeta() : new LeatherArmorMeta();
        }
        return meta;
    }

    public boolean setItemMeta(ItemMeta value) {
        if (simulationView != null) return simulationView.setItemMeta(value);
        meta = value;
        return true;
    }

    public boolean isSimilar(ItemStack other) {
        if (simulationView != null) return simulationView.isSimilar(other);
        Boolean scoped = RollbackItemScope.similarIfActive(this, other);
        if (scoped != null) return scoped;
        return other != null && type == other.type;
    }

    public ItemStack clone() {
        if (simulationView != null) return simulationView.clone();
        ItemStack copy = new ItemStack(type, amount);
        if (copy.simulationView != null) {
            if (durability != 0) copy.setDurability(durability);
            if (meta != null && !copy.setItemMeta(meta)) throw new IllegalArgumentException("Item metadata could not be copied into the simulation");
            return copy;
        }
        copy.durability = durability;
        copy.meta = meta;
        return copy;
    }
}
