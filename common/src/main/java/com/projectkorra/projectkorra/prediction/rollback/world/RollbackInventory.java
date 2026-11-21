package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.inventory.PlayerInventory;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Slot storage behind the existing inventory API. A platform-supplied layout retains
 * extra native equipment slots; storage searches never accidentally consume armor.
 * Checkpoint through RollbackStateGraph to restore item state and retained item aliases.
 */
public final class RollbackInventory extends PlayerInventory implements RollbackStateCell<RollbackInventory.State> {
    /** Slots live in the private native player; returned items are mirrors of owned stacks. */
    public interface Source<S> extends RollbackStateCell<S> {
        ItemStack read(int slot);
        void write(int slot, ItemStack item);
        int selected();
        void selected(int slot);
        int maximumStack();
        void maximumStack(int value);
        RollbackStateCell<?> owner();
    }

    public record Layout(int size, int storageSize, int hotbarSize,
                         int boots, int leggings, int chestplate, int helmet, int offhand) {
        public Layout {
            if (size < 1 || size > 256 || storageSize < 1 || storageSize > size || hotbarSize < 1 || hotbarSize > storageSize) {
                throw new IllegalArgumentException("Inventory layout");
            }
            HashSet<Integer> equipment = new HashSet<>();
            for (int slot : new int[]{boots, leggings, chestplate, helmet, offhand}) {
                if (slot < storageSize || slot >= size || !equipment.add(slot)) throw new IllegalArgumentException("Equipment slot layout");
            }
        }
        public int[] armorSlots() { return new int[]{boots, leggings, chestplate, helmet}; }
        /** Native equipment maps may include additional slots beyond the exposed armor/offhand API. */
        public static Layout fromEquipmentSlots(int storageSize, int hotbarSize, Map<String, Integer> equipment) {
            int size = Math.addExact(storageSize, equipment.size());
            HashSet<Integer> covered = new HashSet<>(equipment.values());
            if (covered.size() != equipment.size()) throw new IllegalArgumentException("Aliased native equipment slots");
            for (int slot = storageSize; slot < size; slot++) {
                if (!covered.contains(slot)) throw new IllegalArgumentException("Noncontiguous native equipment layout");
            }
            return new Layout(size, storageSize, hotbarSize, required(equipment, "FEET"), required(equipment, "LEGS"),
                    required(equipment, "CHEST"), required(equipment, "HEAD"), required(equipment, "OFFHAND"));
        }
        private static int required(Map<String, Integer> equipment, String key) {
            Integer value = equipment.get(key);
            if (value == null) throw new IllegalArgumentException("Native equipment layout is missing " + key);
            return value;
        }
    }

    public static final class State {
        private final RollbackInventory owner;
        private final ItemStack[] contents;
        private final int selected, maximumStack;
        private State(RollbackInventory owner) {
            this.owner = owner;
            contents = owner.contents == null ? null : owner.contents.clone();
            selected = owner.selected;
            maximumStack = owner.maximumStack;
        }
    }

    private final Thread thread = Thread.currentThread();
    private final Layout layout;
    private final RollbackItems items;
    private final Source<?> source;
    private ItemStack[] contents;
    private int selected, maximumStack;

    public RollbackInventory(Layout layout, RollbackItems items, ItemStack[] contents, int selected, int maximumStack) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.items = Objects.requireNonNull(items, "items");
        this.source = null;
        this.contents = new ItemStack[layout.size];
        maximumStack(maximumStack);
        setContents(contents);
        setHeldItemSlot(selected);
    }

    private RollbackInventory(Layout layout, RollbackItems items, Source<?> source) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.items = Objects.requireNonNull(items, "items");
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(source.owner(), "native owner");
    }
    public static RollbackInventory nativeBacked(Layout layout, RollbackItems items, Source<?> source) {
        return new RollbackInventory(layout, items, source);
    }
    public RollbackStateCell<?> nativeOwner() { checkThread(); return source == null ? null : source.owner(); }

    public Layout layout() { checkThread(); return layout; }
    public int maximumStack() { checkThread(); return source == null ? maximumStack : source.maximumStack(); }
    public void maximumStack(int value) {
        checkThread();
        if (value < 1 || value > 1_024) throw new IllegalArgumentException("Inventory stack limit");
        if (source == null) maximumStack = value;
        else source.maximumStack(value);
    }
    @Override public int getSize() { checkThread(); return layout.size; }
    @Override public int getHeldItemSlot() { checkThread(); return source == null ? selected : source.selected(); }
    @Override public void setHeldItemSlot(int slot) {
        checkThread();
        if (slot < 0 || slot >= layout.hotbarSize) throw new IllegalArgumentException("Selected slot outside hotbar");
        if (source == null) selected = slot;
        else source.selected(slot);
    }
    @Override public ItemStack getItem(int slot) { checkSlot(slot); return present(raw(slot)); }
    @Override public void setItem(int slot, ItemStack value) { checkSlot(slot); store(slot, copy(value)); }
    @Override public void clear(int slot) { setItem(slot, null); }
    @Override public ItemStack getItemInMainHand() { return hand(getHeldItemSlot()); }
    @Override public ItemStack getItemInOffHand() { return hand(layout.offhand); }
    @Override public void setItemInMainHand(ItemStack value) { setItem(getHeldItemSlot(), value); }
    @Override public void setItemInOffHand(ItemStack value) { setItem(layout.offhand, value); }
    @Override public ItemStack getHelmet() { return getItem(layout.helmet); }
    @Override public ItemStack getChestplate() { return getItem(layout.chestplate); }
    @Override public ItemStack getLeggings() { return getItem(layout.leggings); }
    @Override public ItemStack getBoots() { return getItem(layout.boots); }
    @Override public ItemStack[] getContents() {
        checkThread();
        ItemStack[] result = new ItemStack[layout.size];
        for (int slot = 0; slot < result.length; slot++) result[slot] = getItem(slot);
        return result;
    }
    @Override public void setContents(ItemStack[] values) {
        checkThread();
        if (Objects.requireNonNull(values, "contents").length > layout.size) throw new IllegalArgumentException("Too many inventory slots");
        ItemStack[] replacement = new ItemStack[layout.size];
        for (int slot = 0; slot < values.length; slot++) replacement[slot] = copy(values[slot]);
        if (source == null) contents = replacement;
        else for (int slot = 0; slot < replacement.length; slot++) store(slot, replacement[slot]);
    }
    @Override public ItemStack[] getArmorContents() {
        return Arrays.stream(layout.armorSlots()).mapToObj(this::getItem).toArray(ItemStack[]::new);
    }
    @Override public void setArmorContents(ItemStack[] values) {
        checkThread();
        if (values != null && values.length > 4) throw new IllegalArgumentException("Too many armor slots");
        ItemStack[] replacement = new ItemStack[4];
        if (values != null) for (int slot = 0; slot < values.length; slot++) replacement[slot] = copy(values[slot]);
        int[] slots = layout.armorSlots();
        for (int index = 0; index < slots.length; index++) store(slots[index], replacement[index]);
    }
    @Override public int first(Material material) {
        checkThread();
        Objects.requireNonNull(material, "material");
        for (int slot = 0; slot < layout.storageSize; slot++) {
            ItemStack item = getItem(slot);
            if (item != null && item.getType().canonical() == material.canonical()) return slot;
        }
        return -1;
    }
    @Override public boolean contains(Material material) { return first(material) >= 0; }
    @Override public boolean containsAtLeast(ItemStack item, int amount) {
        checkThread();
        if (item == null) return false;
        if (amount <= 0) return true;
        long remaining = amount;
        for (int slot = 0; slot < layout.storageSize; slot++) {
            ItemStack candidate = getItem(slot);
            if (candidate != null && items.similar(candidate, item)) {
                remaining -= candidate.getAmount();
                if (remaining <= 0) return true;
            }
        }
        return false;
    }

    /** Requests are copied; leftover keys refer to request indices, never slot indices. */
    @Override public HashMap<Integer, ItemStack> addItem(ItemStack... requested) {
        checkThread();
        checkRequests(requested);
        HashMap<Integer, ItemStack> leftover = new LinkedHashMap<>();
        for (int index = 0; index < requested.length; index++) {
            ItemStack remaining = copy(Objects.requireNonNull(requested[index], "item"));
            if (remaining == null) continue;
            while (remaining.getAmount() > 0) {
                int partial = -1;
                for (int slot = 0; slot < layout.storageSize; slot++) {
                    ItemStack candidate = getItem(slot);
                    if (candidate != null && candidate.getAmount() < limit(candidate) && items.similar(candidate, remaining)) {
                        partial = slot;
                        break;
                    }
                }
                if (partial >= 0) {
                    ItemStack candidate = raw(partial);
                    int moved = Math.min(remaining.getAmount(), limit(candidate) - candidate.getAmount());
                    candidate.setAmount(candidate.getAmount() + moved);
                    store(partial, copy(candidate));
                    remaining.setAmount(remaining.getAmount() - moved);
                    continue;
                }
                int empty = -1;
                for (int slot = 0; slot < layout.storageSize; slot++) {
                    if (getItem(slot) == null) { empty = slot; break; }
                }
                if (empty < 0) { leftover.put(index, remaining); break; }
                int moved = Math.min(remaining.getAmount(), limit(remaining));
                ItemStack inserted = copy(remaining);
                inserted.setAmount(moved);
                store(empty, inserted);
                remaining.setAmount(remaining.getAmount() - moved);
            }
        }
        return leftover;
    }
    @Override public HashMap<Integer, ItemStack> removeItem(ItemStack... requested) {
        checkThread();
        checkRequests(requested);
        HashMap<Integer, ItemStack> leftover = new LinkedHashMap<>();
        for (int index = 0; index < requested.length; index++) {
            ItemStack remaining = copy(Objects.requireNonNull(requested[index], "item"));
            if (remaining == null) continue;
            for (int slot = 0; slot < layout.storageSize && remaining.getAmount() > 0; slot++) {
                ItemStack candidate = getItem(slot);
                if (candidate == null || !items.similar(candidate, remaining)) continue;
                int removed = Math.min(remaining.getAmount(), candidate.getAmount());
                remaining.setAmount(remaining.getAmount() - removed);
                if (removed == candidate.getAmount()) store(slot, null);
                else {
                    candidate.setAmount(candidate.getAmount() - removed);
                    store(slot, copy(candidate));
                }
            }
            if (remaining.getAmount() > 0) leftover.put(index, remaining);
        }
        return leftover;
    }
    @Override public Iterator<ItemStack> iterator() { return Arrays.asList(getContents()).iterator(); }

    @Override public State captureRollbackState() { checkThread(); return new State(this); }
    @Override public void restoreRollbackState(State state) {
        checkThread();
        if (state.owner != this) throw new IllegalArgumentException("Inventory checkpoint belongs to another inventory");
        contents = state.contents == null ? null : state.contents.clone();
        selected = state.selected;
        maximumStack = state.maximumStack;
    }
    @Override public Collection<?> rollbackReferences() {
        checkThread();
        if (source != null) return List.of(source);
        List<ItemStack> result = new ArrayList<>();
        // Include zero-count items too: retained aliases can be mutated back to nonempty.
        for (ItemStack item : contents) if (item != null) result.add(item);
        return result;
    }

    private ItemStack hand(int slot) {
        ItemStack value = getItem(slot);
        if (value != null) return value;
        ItemStack empty = items.emptyStack();
        requireLogical(empty);
        if (!items.isEmpty(empty)) throw new IllegalStateException("Item adapter returned a nonempty empty stack");
        return empty;
    }
    private ItemStack present(ItemStack value) { return value == null || items.isEmpty(value) ? null : value; }
    private ItemStack copy(ItemStack source) {
        if (present(source) == null) return null;
        ItemStack value = items.copy(source);
        requireLogical(value);
        if (value == source) throw new IllegalStateException("Item adapter returned a shared item");
        return value;
    }
    private int limit(ItemStack value) {
        int limit = items.maximumStack(value);
        if (limit < 1 || limit > 1_024) throw new IllegalStateException("Invalid native item stack limit");
        return Math.min(limit, maximumStack());
    }
    private ItemStack raw(int slot) { return source == null ? contents[slot] : source.read(slot); }
    private void store(int slot, ItemStack value) {
        if (source == null) contents[slot] = value;
        else source.write(slot, value);
    }
    private static void requireLogical(ItemStack item) {
        if (!(item instanceof RollbackStateCell<?>)) throw new IllegalStateException("Item adapter must return checkpointable logical items");
    }
    private static void checkRequests(ItemStack[] values) {
        if (Objects.requireNonNull(values, "items").length > 1_024) throw new IllegalArgumentException("Inventory request budget");
    }
    private void checkSlot(int slot) {
        checkThread();
        if (slot < 0 || slot >= layout.size) throw new IndexOutOfBoundsException("Inventory slot: " + slot);
    }
    private void checkThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Logical inventory crossed threads");
    }
}
