package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackInventory;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import net.minecraft.world.entity.player.Inventory;
import org.bukkit.craftbukkit.inventory.CraftInventoryPlayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Discovers the native slot layout without constructing a player or touching a live inventory. */
public final class PaperRollbackInventory {
    private PaperRollbackInventory() { }

    /** Shared inventory/equipment over a private simulation player, never a live player. */
    public static RollbackInventory bind(PaperRollbackNativePlayerState owner, RollbackNativeItems<net.minecraft.world.item.ItemStack> items) {
        Objects.requireNonNull(items, "items").requireOwnerThread();
        var access = new OwnedInventory(Objects.requireNonNull(owner, "owner"), items);
        if (access.inventory().getContainerSize() != layout().size()) throw new IllegalArgumentException("Native player inventory layout changed");
        return RollbackInventory.nativeBacked(layout(), items, access);
    }

    private record OwnedInventory(PaperRollbackNativePlayerState player, RollbackNativeItems<net.minecraft.world.item.ItemStack> items)
            implements RollbackInventory.Source<Void>, RollbackNativeItems.MirrorOwner<net.minecraft.world.item.ItemStack> {
        Inventory inventory() { items.requireOwnerThread(); return player.ownedPlayer().getInventory(); }
        @Override public void requireOwner() { inventory(); }
        @Override public ItemStack read(int slot) {
            var item = inventory().getItem(slot);
            return item.isEmpty() ? null : items.mirror(item, this);
        }
        @Override public void write(int slot, ItemStack item) {
            var inventory = inventory();
            inventory.setItem(slot, item == null ? net.minecraft.world.item.ItemStack.EMPTY : items.nativeCopy(item));
        }
        @Override public int selected() { return inventory().getSelectedSlot(); }
        @Override public void selected(int slot) { inventory().setSelectedSlot(slot); }
        @Override public int maximumStack() { return inventory().getMaxStackSize(); }
        @Override public void maximumStack(int value) { inventory().setMaxStackSize(value); }
        @Override public RollbackStateCell<?> owner() { requireOwner(); return player; }
        @Override public RollbackStateCell<?> root() { requireOwner(); return this; }
        @Override public Void captureRollbackState() { requireOwner(); return null; }
        @Override public void restoreRollbackState(Void ignored) { requireOwner(); }
        @Override public List<?> rollbackReferences() { requireOwner(); return List.of(player); }
        @Override public RollbackStateGraph.Snapshot capture(net.minecraft.world.item.ItemStack item) { requireOwner(); return player.captureItem(item); }
        @Override public void overwrite(net.minecraft.world.item.ItemStack target, net.minecraft.world.item.ItemStack replacement) {
            requireOwner();
            if (target == net.minecraft.world.item.ItemStack.EMPTY || replacement == net.minecraft.world.item.ItemStack.EMPTY) {
                throw new IllegalArgumentException("Cannot mutate the native empty singleton");
            }
            int count = replacement.getCount();
            replacement.setCount(1);
            try {
                target.setItem(replacement.getItem());
                target.restorePatch(replacement.getComponentsPatch());
                target.setCount(count);
            } finally { replacement.setCount(count); }
        }
    }

    /** Read-only capture; the returned inventory and every item are detached from the live player. */
    public static RollbackInventory capture(org.bukkit.inventory.PlayerInventory source, RollbackNativeItems<net.minecraft.world.item.ItemStack> items) {
        Objects.requireNonNull(items, "items").requireOwnerThread();
        return captureNative(((CraftInventoryPlayer) Objects.requireNonNull(source, "inventory")).getInventory(), items);
    }
    public static RollbackInventory captureNative(Object source, RollbackNativeItems<net.minecraft.world.item.ItemStack> items) {
        Objects.requireNonNull(items, "items").requireOwnerThread();
        Inventory inventory = (Inventory) Objects.requireNonNull(source, "inventory");
        RollbackInventory.Layout layout = layout();
        int size = inventory.getContainerSize();
        if (size != layout.size()) throw new IllegalArgumentException("Native player inventory layout changed");
        ItemStack[] contents = new ItemStack[size];
        for (int slot = 0; slot < size; slot++) {
            var item = inventory.getItem(slot);
            if (!item.isEmpty()) contents[slot] = items.capture(item);
        }
        return new RollbackInventory(layout, items, contents, inventory.getSelectedSlot(), inventory.getMaxStackSize());
    }
    public static RollbackInventory.Layout layout() {
        Map<String, Integer> slots = new LinkedHashMap<>();
        Inventory.EQUIPMENT_SLOT_MAPPING.forEach((slot, equipment) -> slots.put(equipment.name(), slot));
        return RollbackInventory.Layout.fromEquipmentSlots(Inventory.INVENTORY_SIZE, Inventory.SELECTION_SIZE, slots);
    }
}
