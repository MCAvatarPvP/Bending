package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackInventory;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.fabric.mixin.client.ItemStackRollbackAccess;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Uses the native equipment map, including additional body/saddle slots. */
public final class FabricRollbackInventory {
    private FabricRollbackInventory() { }
    /** Shared inventory/equipment over a private simulation player, never the client player. */
    public static RollbackInventory bind(FabricRollbackNativePlayerState owner, RollbackNativeItems<ItemStack> items) {
        Objects.requireNonNull(items, "items").requireOwnerThread();
        var access = new OwnedInventory(Objects.requireNonNull(owner, "owner"), items);
        if (access.inventory().size() != layout().size()) throw new IllegalArgumentException("Native player inventory layout changed");
        return RollbackInventory.nativeBacked(layout(), items, access);
    }

    private record OwnedInventory(FabricRollbackNativePlayerState player, RollbackNativeItems<ItemStack> items)
            implements RollbackInventory.Source<Void>, RollbackNativeItems.MirrorOwner<ItemStack> {
        PlayerInventory inventory() { items.requireOwnerThread(); return player.ownedPlayer().getInventory(); }
        @Override public void requireOwner() { inventory(); }
        @Override public com.projectkorra.projectkorra.platform.mc.inventory.ItemStack read(int slot) {
            var item = inventory().getStack(slot);
            return item.isEmpty() ? null : items.mirror(item, this);
        }
        @Override public void write(int slot, com.projectkorra.projectkorra.platform.mc.inventory.ItemStack item) {
            var inventory = inventory();
            inventory.setStack(slot, item == null ? ItemStack.EMPTY : items.nativeCopy(item));
        }
        @Override public int selected() { return inventory().getSelectedSlot(); }
        @Override public void selected(int slot) { inventory().setSelectedSlot(slot); }
        @Override public int maximumStack() { return inventory().getMaxCountPerStack(); }
        @Override public void maximumStack(int value) {
            if (value != maximumStack()) throw new IllegalArgumentException("Native Fabric inventory has a fixed stack limit");
        }
        @Override public RollbackStateCell<?> owner() { requireOwner(); return player; }
        @Override public RollbackStateCell<?> root() { requireOwner(); return this; }
        @Override public Void captureRollbackState() { requireOwner(); return null; }
        @Override public void restoreRollbackState(Void ignored) { requireOwner(); }
        @Override public List<?> rollbackReferences() { requireOwner(); return List.of(player); }
        @Override public RollbackStateGraph.Snapshot capture(ItemStack item) { requireOwner(); return player.captureItem(item); }
        @Override public void overwrite(ItemStack target, ItemStack replacement) {
            requireOwner();
            if (target == ItemStack.EMPTY || replacement == ItemStack.EMPTY) throw new IllegalArgumentException("Cannot mutate the native empty singleton");
            var targetAccess = (ItemStackRollbackAccess) (Object) target;
            var replacementAccess = (ItemStackRollbackAccess) (Object) replacement;
            targetAccess.rollback$item(replacementAccess.rollback$item());
            targetAccess.rollback$components(replacementAccess.rollback$components().copy());
            target.setCount(replacement.getCount());
        }
    }
    /** Captures native slots without retaining live item or equipment references. */
    public static RollbackInventory capture(PlayerInventory source, RollbackNativeItems<ItemStack> items) {
        Objects.requireNonNull(items, "items").requireOwnerThread();
        Objects.requireNonNull(source, "inventory");
        RollbackInventory.Layout layout = layout();
        if (source.size() != layout.size()) throw new IllegalArgumentException("Native player inventory layout changed");
        var contents = new com.projectkorra.projectkorra.platform.mc.inventory.ItemStack[layout.size()];
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = source.getStack(slot);
            if (!item.isEmpty()) contents[slot] = items.capture(item);
        }
        return new RollbackInventory(layout, items, contents, source.getSelectedSlot(), source.getMaxCountPerStack());
    }
    public static RollbackInventory.Layout layout() {
        Map<String, Integer> slots = new LinkedHashMap<>();
        PlayerInventory.EQUIPMENT_SLOTS.forEach((slot, equipment) -> slots.put(equipment.name(), slot));
        return RollbackInventory.Layout.fromEquipmentSlots(PlayerInventory.MAIN_SIZE, PlayerInventory.getHotbarSize(), slots);
    }
}
