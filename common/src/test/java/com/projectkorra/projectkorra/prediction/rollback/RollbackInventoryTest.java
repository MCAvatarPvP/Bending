package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.inventory.EntityEquipment;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.inventory.PlayerInventory;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.ItemMeta;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEquipment;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackInventory;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItems;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class RollbackInventoryTest {
    // Includes two native equipment slots beyond the common armor/offhand API.
    static final RollbackInventory.Layout LAYOUT = RollbackInventory.Layout.fromEquipmentSlots(36, 9,
            Map.of("FEET", 36, "LEGS", 37, "CHEST", 38, "HEAD", 39, "OFFHAND", 40, "BODY", 41, "SADDLE", 42));

    @Test void publicInventoryAndEquipmentMethodsCannotFallThroughToCommonStubs() throws Exception {
        assertOverrides(PlayerInventory.class, RollbackInventory.class);
        assertOverrides(EntityEquipment.class, RollbackEquipment.class);
    }

    @Test void slotsEquipmentAndRetainedItemReferencesRewindTogether() {
        var inventory = inventory();
        var equipment = new RollbackEquipment(inventory);
        FixtureItem source = item(Material.STONE, 3, "held");
        source.setDurability((short) 7);
        source.getItemMeta().setCustomData("fixture:key", "before");
        inventory.setItemInMainHand(source);
        inventory.setArmorContents(new ItemStack[]{item(Material.LEATHER_BOOTS, 1, "boots")});
        inventory.setItem(41, item(Material.STONE, 1, "body"));
        inventory.setItem(42, item(Material.STONE, 1, "saddle"));
        FixtureItem held = (FixtureItem) equipment.getItemInMainHand();
        ItemMeta heldMeta = held.getItemMeta();
        assertNotSame(source, held);
        source.setAmount(100);
        source.getItemMeta().setCustomData("fixture:key", "outside");
        assertEquals(3, held.getAmount());
        assertEquals("before", heldMeta.getCustomData("fixture:key"));
        var saved = capture(equipment);
        held.setAmount(0);
        assertNull(inventory.getItem(0));
        held.setAmount(4);
        held.setDurability((short) 20);
        heldMeta.setCustomData("fixture:key", "after");
        inventory.setHeldItemSlot(2);
        inventory.maximumStack(16);
        inventory.setArmorContents(null);
        inventory.clear(41);
        inventory.clear(42);
        inventory.getContents()[0] = null;
        assertSame(held, inventory.getItem(0));
        saved.restore();
        assertSame(held, equipment.getItemInMainHand());
        assertSame(heldMeta, held.getItemMeta());
        assertEquals(3, held.getAmount());
        assertEquals(7, held.getDurability());
        assertEquals("before", heldMeta.getCustomData("fixture:key"));
        assertEquals(Material.LEATHER_BOOTS, equipment.getArmorContents()[0].getType());
        assertEquals("body", inventory.getItem(41).getItemMeta().getDisplayName());
        assertEquals("saddle", inventory.getItem(42).getItemMeta().getDisplayName());
        assertEquals(0, inventory.getHeldItemSlot());
        assertEquals(64, inventory.maximumStack());
    }

    @Test void metadataSensitiveStackingUsesStorageSlotsAndBothStackLimits() {
        var inventory = inventory();
        inventory.maximumStack(16);
        inventory.setItem(0, item(Material.STONE, 15, "different"));
        inventory.setItem(1, item(Material.STONE, 15, "same"));
        inventory.setItemInOffHand(item(Material.STONE, 1, "same"));
        FixtureItem request = item(Material.STONE, 20, "same");
        assertTrue(inventory.addItem(request).isEmpty());
        assertEquals(20, request.getAmount(), "the request belongs to the caller");
        assertEquals(15, inventory.getItem(0).getAmount());
        assertEquals(16, inventory.getItem(1).getAmount());
        assertEquals(16, inventory.getItem(2).getAmount());
        assertEquals(3, inventory.getItem(3).getAmount());
        assertEquals(1, inventory.getItemInOffHand().getAmount());
        assertTrue(inventory.containsAtLeast(item(Material.STONE, 1, "same"), 35));
        assertFalse(inventory.containsAtLeast(item(Material.STONE, 1, "same"), 36));
        FixtureItem nativeLimit = item(Material.STONE, 12, "limited");
        nativeLimit.maximumStack = 5; // The production adapter obtains this from native item components.
        inventory.addItem(nativeLimit);
        assertEquals(5, inventory.getItem(4).getAmount());
        assertEquals(5, inventory.getItem(5).getAmount());
        assertEquals(2, inventory.getItem(6).getAmount());
    }

    @Test void removalAndOverflowDoNotConsumeEquipmentAndPreserveRequestIndices() {
        var inventory = inventory();
        inventory.setItemInOffHand(item(Material.STONE, 64, "same"));
        inventory.setItem(41, item(Material.STONE, 64, "same"));
        assertFalse(inventory.contains(Material.STONE));
        var missing = inventory.removeItem(item(Material.STONE, 2, "same"));
        assertEquals(2, missing.get(0).getAmount());
        assertEquals(64, inventory.getItemInOffHand().getAmount());
        inventory.setItem(0, item(Material.STONE, 4, "different"));
        inventory.setItem(1, item(Material.STONE, 4, "same"));
        inventory.setItem(2, item(Material.STONE, 3, "same"));
        ItemStack alias = inventory.getItem(2);
        assertTrue(inventory.removeItem(item(Material.STONE, 6, "same")).isEmpty());
        assertEquals(4, inventory.getItem(0).getAmount());
        assertNull(inventory.getItem(1));
        assertEquals(1, inventory.getItem(2).getAmount());
        assertEquals(1, alias.getAmount(), "partial native-style mutation is visible through a retained item");
        for (int slot = 0; slot < LAYOUT.storageSize(); slot++) inventory.setItem(slot, item(Material.STONE, 64, "full"));
        var overflow = inventory.addItem(item(Material.AIR, 0, ""), item(Material.STONE, 2, "same"), item(Material.STONE, 3, "same"));
        assertEquals(List.of(1, 2), List.copyOf(overflow.keySet()));
        assertEquals(2, overflow.get(1).getAmount());
        assertEquals(3, overflow.get(2).getAmount());
    }

    @Test void emptyItemAliasesAreStillPartOfTheStateGraph() {
        var inventory = inventory();
        inventory.setItem(0, item(Material.STONE, 1, "held"));
        ItemStack alias = inventory.getItem(0);
        alias.setAmount(0);
        var emptyCheckpoint = capture(inventory);
        alias.setAmount(10);
        assertNotNull(inventory.getItem(0));
        emptyCheckpoint.restore();
        assertNull(inventory.getItem(0));
        assertEquals(0, alias.getAmount());
        assertEquals(Material.AIR, inventory.getItemInMainHand().getType());
    }

    @Test void invalidLayoutsNativeItemsAndForeignCheckpointsFailExplicitly() {
        assertEquals(43, LAYOUT.size());
        assertThrows(IllegalArgumentException.class, () -> RollbackInventory.Layout.fromEquipmentSlots(36, 9,
                Map.of("FEET", 36, "LEGS", 37, "CHEST", 38, "HEAD", 39, "OFFHAND", 41)));
        assertThrows(IllegalArgumentException.class, () -> new RollbackInventory.Layout(43, 36, 9, 36, 37, 38, 39, 39));
        var inventory = inventory();
        inventory.setItem(0, item(Material.STONE, 2, "before"));
        assertThrows(IllegalArgumentException.class, () -> inventory.setContents(new ItemStack[]{item(Material.STONE, 1, "after"), new ItemStack(Material.STONE)}));
        assertEquals(2, inventory.getItem(0).getAmount(), "bulk replacement is installed only after every copy succeeds");
        assertThrows(IllegalArgumentException.class, () -> inventory.setHeldItemSlot(9));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.getItem(43));
        assertThrows(IllegalArgumentException.class, () -> inventory().restoreRollbackState(inventory.captureRollbackState()));
        CompletionException failure = assertThrows(CompletionException.class,
                () -> CompletableFuture.runAsync(() -> inventory.setHeldItemSlot(1)).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    static void assertOverrides(Class<?> api, Class<?> view) throws Exception {
        for (var method : api.getMethods()) {
            if (method.getDeclaringClass() == Object.class || Modifier.isStatic(method.getModifiers()) || method.getDeclaringClass() == Iterable.class) continue;
            assertEquals(view, view.getMethod(method.getName(), method.getParameterTypes()).getDeclaringClass(), method.toString());
        }
    }

    static RollbackStateGraph.Snapshot capture(Object root) {
        return new RollbackStateGraph(value -> false, field -> true, 10_000).capture(List.of(root), List.of());
    }
    static RollbackInventory inventory() { return new RollbackInventory(LAYOUT, ITEMS, new ItemStack[0], 0, 64); }
    static FixtureItem item(Material material, int amount, String name) {
        FixtureItem item = new FixtureItem(material, amount);
        item.getItemMeta().setDisplayName(name);
        return item;
    }
    /** Explicit test codec; opaque bytes stand for components, not a native serialization implementation. */
    static final RollbackItems ITEMS = new RollbackItems() {
        @Override public ItemStack copy(ItemStack source) {
            if (!(source instanceof FixtureItem item)) throw new IllegalArgumentException("Not a fixture item");
            return item.clone();
        }
        @Override public ItemStack emptyStack() { return item(Material.AIR, 0, ""); }
        @Override public boolean isEmpty(ItemStack item) { return item.getType() == Material.AIR || item.getAmount() <= 0; }
        @Override public boolean similar(ItemStack a, ItemStack b) {
            return a.getType() == b.getType() && a.getDurability() == b.getDurability()
                    && a.getItemMeta().getDisplayName().equals(b.getItemMeta().getDisplayName())
                    && a.getItemMeta().getLore().equals(b.getItemMeta().getLore())
                    && a.getItemMeta().getCustomData().equals(b.getItemMeta().getCustomData())
                    && Arrays.equals(((FixtureItem) a).components, ((FixtureItem) b).components);
        }
        @Override public int maximumStack(ItemStack item) { return ((FixtureItem) item).maximumStack; }
    };

    static final class FixtureItem extends ItemStack implements RollbackStateCell<FixtureItem.State> {
        private byte[] components = {1, 2, 3};
        private int maximumStack = 64;
        private FixtureItem(Material material, int count) { super(material, count); super.setItemMeta(new ItemMeta()); }
        record State(Material material, int amount, short durability, ItemMeta meta, byte[] components, int maximumStack) { }
        @Override public State captureRollbackState() {
            return new State(getType(), getAmount(), getDurability(), getItemMeta(), components.clone(), maximumStack);
        }
        @Override public void restoreRollbackState(State state) {
            setType(state.material); setAmount(state.amount); setDurability(state.durability); setItemMeta(state.meta);
            components = state.components.clone(); maximumStack = state.maximumStack;
        }
        @Override public Collection<?> rollbackReferences() { return List.of(getItemMeta()); }
        @Override public FixtureItem clone() {
            FixtureItem copy = new FixtureItem(getType(), getAmount());
            copy.setDurability(getDurability());
            copy.getItemMeta().setDisplayName(getItemMeta().getDisplayName());
            copy.getItemMeta().setLore(getItemMeta().getLore());
            copy.getItemMeta().setCustomModelData(getItemMeta().getCustomModelData());
            getItemMeta().getCustomData().forEach(copy.getItemMeta()::setCustomData);
            copy.components = components.clone(); copy.maximumStack = maximumStack;
            return copy;
        }
    }
}
