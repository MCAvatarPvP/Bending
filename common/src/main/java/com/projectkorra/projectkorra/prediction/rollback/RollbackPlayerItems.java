package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Detached inventory/component data with an identity table shared by every native item root. */
public final class RollbackPlayerItems {
    public static final int VERSION = 1, MAXIMUM_ITEMS = 256, MAXIMUM_SLOTS = 128, MAXIMUM_COOLDOWNS = 1024;
    public static final int MAXIMUM_ITEM_BYTES = 4_194_304, MAXIMUM_BYTES = MAXIMUM_ITEM_BYTES + 524_288;
    public record Item(RollbackItemData data, int popTime) { public Item { Objects.requireNonNull(data); } }
    public record Cooldown(int startTick, int endTick) { }
    private final List<Item> items;
    private final List<Integer> inventory, enderChest;
    private final int selected, maximumStack, useItem, lastItem, spinItem, cooldownTick;
    private final SortedMap<String, Integer> lastEquipment;
    private final SortedMap<String, Cooldown> cooldowns;

    public RollbackPlayerItems(List<Item> items, List<Integer> inventory, List<Integer> enderChest,
            int selected, int maximumStack, int useItem, int lastItem, int spinItem,
            Map<String, Integer> lastEquipment, int cooldownTick, Map<String, Cooldown> cooldowns) {
        if (items.isEmpty() || items.size() > MAXIMUM_ITEMS || inventory.isEmpty() || inventory.size() > MAXIMUM_SLOTS
                || enderChest.size() > MAXIMUM_SLOTS || lastEquipment.size() > 32 || cooldowns.size() > MAXIMUM_COOLDOWNS
                || selected < 0 || selected >= Math.min(9, inventory.size()) || maximumStack < 1) throw invalid("layout/budget");
        this.items = List.copyOf(items); this.inventory = List.copyOf(inventory); this.enderChest = List.copyOf(enderChest);
        this.selected = selected; this.maximumStack = maximumStack; this.useItem = useItem; this.lastItem = lastItem; this.spinItem = spinItem;
        this.cooldownTick = cooldownTick;
        this.lastEquipment = Collections.unmodifiableSortedMap(new TreeMap<>(lastEquipment));
        this.cooldowns = Collections.unmodifiableSortedMap(new TreeMap<>(cooldowns));
        int bytes = 0;
        for (Item item : items) { bytes = Math.addExact(bytes, item.data().size()); if (bytes > MAXIMUM_ITEM_BYTES) throw invalid("item bytes"); }
        var used = new BitSet(items.size());
        for (int index : inventory) reference(index, used);
        for (int index : enderChest) reference(index, used);
        reference(useItem, used); reference(lastItem, used);
        if (spinItem != -1) reference(spinItem, used);
        this.lastEquipment.forEach((key, index) -> { slot(key); reference(index, used); });
        this.cooldowns.forEach((key, value) -> { key(key); Objects.requireNonNull(value); });
        if (used.cardinality() != items.size()) throw invalid("unreferenced item");
    }
    private void reference(int index, BitSet used) {
        if (index < 0 || index >= items.size()) throw invalid("item reference");
        used.set(index);
    }
    public List<Item> items() { return items; }
    public List<Integer> inventory() { return inventory; }
    public List<Integer> enderChest() { return enderChest; }
    public int selected() { return selected; }
    public int maximumStack() { return maximumStack; }
    public int useItem() { return useItem; }
    public int lastItem() { return lastItem; }
    public int spinItem() { return spinItem; }
    public SortedMap<String, Integer> lastEquipment() { return lastEquipment; }
    public int cooldownTick() { return cooldownTick; }
    public SortedMap<String, Cooldown> cooldowns() { return cooldowns; }

    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(new FilterOutputStream(bytes) {
                int count;
                private void reserve(int n) { if (n < 0 || n > MAXIMUM_BYTES - count) throw invalid("wire budget"); count += n; }
                @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
                @Override public void write(byte[] value, int offset, int length) throws IOException { reserve(length); out.write(value, offset, length); }
            });
            out.writeInt(VERSION); out.writeInt(items.size());
            for (Item item : items) { out.writeInt(item.data().size()); out.write(item.data().bytes()); out.writeInt(item.popTime()); }
            indices(out, inventory); indices(out, enderChest);
            out.writeInt(selected); out.writeInt(maximumStack); out.writeInt(useItem); out.writeInt(lastItem); out.writeInt(spinItem);
            out.writeInt(lastEquipment.size());
            for (var entry : lastEquipment.entrySet()) { text(out, entry.getKey()); out.writeInt(entry.getValue()); }
            out.writeInt(cooldownTick); out.writeInt(cooldowns.size());
            for (var entry : cooldowns.entrySet()) { text(out, entry.getKey()); out.writeInt(entry.getValue().startTick()); out.writeInt(entry.getValue().endTick()); }
            return bytes.toByteArray();
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    public static RollbackPlayerItems decode(byte[] bytes) {
        if (bytes.length > MAXIMUM_BYTES) throw invalid("wire budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw invalid("version");
            int count = bounded(in.readInt(), MAXIMUM_ITEMS), total = 0;
            var items = new ArrayList<Item>(count);
            for (int i = 0; i < count; i++) {
                int length = bounded(in.readInt(), RollbackItemData.MAXIMUM_BYTES); total += length;
                if (total > MAXIMUM_ITEM_BYTES || length > in.available()) throw invalid("item bytes");
                items.add(new Item(new RollbackItemData(in.readNBytes(length)), in.readInt()));
            }
            var inventory = indices(in); var enderChest = indices(in);
            int selected = in.readInt(), maximumStack = in.readInt(), useItem = in.readInt(), lastItem = in.readInt(), spinItem = in.readInt();
            int equipmentCount = bounded(in.readInt(), 32); var equipment = new TreeMap<String, Integer>(); String previous = "";
            for (int i = 0; i < equipmentCount; i++) {
                String key = text(in); slot(key); if (key.compareTo(previous) <= 0) throw invalid("equipment order/duplicate"); previous = key;
                equipment.put(key, in.readInt());
            }
            int tick = in.readInt(), cooldownCount = bounded(in.readInt(), MAXIMUM_COOLDOWNS);
            var cooldowns = new TreeMap<String, Cooldown>(); previous = "";
            for (int i = 0; i < cooldownCount; i++) {
                String key = text(in); key(key); if (key.compareTo(previous) <= 0) throw invalid("cooldown order/duplicate"); previous = key;
                cooldowns.put(key, new Cooldown(in.readInt(), in.readInt()));
            }
            if (in.available() != 0) throw invalid("trailing data");
            return new RollbackPlayerItems(items, inventory, enderChest, selected, maximumStack, useItem, lastItem, spinItem, equipment, tick, cooldowns);
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid player items: malformed/truncated data", failure); }
    }
    private static void indices(DataOutputStream out, List<Integer> values) throws IOException {
        out.writeInt(values.size()); for (int value : values) out.writeInt(value);
    }
    private static List<Integer> indices(DataInputStream in) throws IOException {
        int count = bounded(in.readInt(), MAXIMUM_SLOTS); var values = new ArrayList<Integer>(count);
        for (int i = 0; i < count; i++) values.add(in.readInt()); return values;
    }
    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII); out.writeShort(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in) throws IOException {
        int length = bounded(in.readUnsignedShort(), 256); if (length > in.available()) throw invalid("key length");
        return new String(in.readNBytes(length), StandardCharsets.US_ASCII);
    }
    private static void key(String value) { if (value == null || value.length() > 256 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw invalid("cooldown key"); }
    private static void slot(String value) { if (value == null || !value.matches("[A-Z_]{1,32}")) throw invalid("equipment key"); }
    private static int bounded(int value, int max) { if (value < 0 || value > max) throw invalid("count"); return value; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("Invalid player items: " + message); }
}
