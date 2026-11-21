package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.inventory.EntityEquipment;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Equipment and inventory APIs share the same logical slots and checkpoint. */
public final class RollbackEquipment extends EntityEquipment implements RollbackStateCell<Void> {
    private final RollbackInventory inventory;
    public RollbackEquipment(RollbackInventory inventory) { this.inventory = Objects.requireNonNull(inventory, "inventory"); }
    public RollbackInventory inventory() { return inventory; }
    @Override public ItemStack[] getArmorContents() { return inventory.getArmorContents(); }
    @Override public void setArmorContents(ItemStack[] items) { inventory.setArmorContents(items); }
    @Override public ItemStack getItemInMainHand() { return inventory.getItemInMainHand(); }
    @Override public void setItemInMainHand(ItemStack item) { inventory.setItemInMainHand(item); }
    @Override public ItemStack getHelmet() { return inventory.getHelmet(); }
    @Override public void setHelmet(ItemStack item) { inventory.setItem(inventory.layout().helmet(), item); }
    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void ignored) { }
    @Override public Collection<?> rollbackReferences() { return List.of(inventory); }
}
