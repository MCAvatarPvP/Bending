package com.projectkorra.projectkorra.platform.mc.inventory;

import com.projectkorra.projectkorra.platform.mc.Material;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class PlayerInventory implements Iterable<ItemStack> {
    private int heldItemSlot;
    private ItemStack[] contents = new ItemStack[36];

    public ItemStack getItemInMainHand() {
        return new ItemStack();
    }

    public void setItemInMainHand(ItemStack item) {
        contents[heldItemSlot] = item;
    }

    public ItemStack getItemInOffHand() {
        return new ItemStack();
    }

    public void setItemInOffHand(ItemStack item) {
    }

    public ItemStack getHelmet() {
        return getArmorContents()[3];
    }

    public ItemStack getChestplate() {
        return getArmorContents()[2];
    }

    public ItemStack getLeggings() {
        return getArmorContents()[1];
    }

    public ItemStack getBoots() {
        return getArmorContents()[0];
    }

    public int getHeldItemSlot() {
        return heldItemSlot;
    }

    public void setHeldItemSlot(int slot) {
        heldItemSlot = slot;
    }

    public boolean containsAtLeast(ItemStack item, int amount) {
        return false;
    }

    public Map<Integer, ItemStack> removeItem(ItemStack... items) {
        return Map.of();
    }

    public ItemStack[] getContents() {
        return contents.clone();
    }

    public void setContents(ItemStack[] items) {
        contents = items.clone();
    }

    public ItemStack[] getArmorContents() {
        return new ItemStack[4];
    }

    public void setArmorContents(ItemStack[] items) {
    }

    public HashMap<Integer, ItemStack> addItem(ItemStack... items) {
        return new HashMap<>();
    }

    public void clear(int slot) {
        setItem(slot, null);
    }

    public boolean contains(Material material) {
        return first(material) >= 0;
    }

    public int first(Material material) {
        for (int i = 0; i < contents.length; i++)
            if (contents[i] != null && contents[i].getType() == material) return i;
        return -1;
    }

    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < contents.length ? contents[slot] : null;
    }

    public void setItem(int slot, ItemStack item) {
        if (slot >= 0 && slot < contents.length) contents[slot] = item;
    }

    public int getSize() {
        return contents.length;
    }

    @Override
    public Iterator<ItemStack> iterator() {
        return Arrays.asList(contents).iterator();
    }
}
