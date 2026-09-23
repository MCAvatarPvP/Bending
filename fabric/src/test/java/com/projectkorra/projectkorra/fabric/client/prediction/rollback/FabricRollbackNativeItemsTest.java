package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.*;
import com.projectkorra.projectkorra.platform.mc.potion.PotionData;
import com.projectkorra.projectkorra.platform.mc.potion.PotionType;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackInventory;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemScope;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems.Kind;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackNativeItemsTest {
    private static RegistryWrapper.WrapperLookup registries;
    // The live client receives this metadata schema from Paper; this is a test schema.
    private static final Map<String, Kind> KINDS = Map.of("minecraft:stone", Kind.GENERIC, "minecraft:diamond_boots", Kind.GENERIC,
            "minecraft:leather_helmet", Kind.LEATHER, "minecraft:potion", Kind.POTION, "minecraft:player_head", Kind.SKULL, "minecraft:air", Kind.GENERIC);
    @BeforeAll static void bootstrapRegistry() {
        SharedConstants.createGameVersion(); Bootstrap.initialize();
        registries = BuiltinRegistries.createWrapperLookup();
    }
    private static RollbackNativeItems<ItemStack> items() { return new RollbackNativeItems<>(new FabricRollbackItems(registries, KINDS)); }


    @Test void nativeInventoryCaptureDetachesAllSlotsAndRestoresRetainedItemReferences() {
        var items = items();
        var source = new net.minecraft.entity.player.PlayerInventory(null, new net.minecraft.entity.EntityEquipment());
        for (int slot = 0; slot < 43; slot++) {
            var stack = new ItemStack(Items.STONE, slot + 1);
            stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("captured"));
            source.setStack(slot, stack);
        }
        source.setSelectedSlot(7);
        
        var inventory = FabricRollbackInventory.capture(source, items);
        assertEquals(43, inventory.getSize());
        assertEquals(7, inventory.getHeldItemSlot());
        assertEquals(source.getMaxCountPerStack(), inventory.maximumStack());
        for (int slot = 0; slot < 43; slot++) {
            assertEquals(slot + 1, inventory.getItem(slot).getAmount());
            assertEquals("captured", inventory.getItem(slot).getItemMeta().getDisplayName());
            source.getStack(slot).setCount(1);
        }
        var retained = inventory.getItem(41);
        var saved = capture(inventory, retained);
        retained.setAmount(3);
        inventory.clear(42);
        inventory.setHeldItemSlot(2);
        saved.restore();
        assertSame(retained, inventory.getItem(41));
        assertEquals(42, retained.getAmount());
        assertEquals(43, inventory.getItem(42).getAmount());
        assertEquals(7, inventory.getHeldItemSlot());
        for (int slot = 0; slot < 43; slot++) assertEquals(1, source.getStack(slot).getCount());
        assertEquals(7, source.getSelectedSlot());
    }

    @Test void emptyNativeInventoryStillRejectsCaptureFromAnotherThread() {
        var items = items();
        var source = new net.minecraft.entity.player.PlayerInventory(null, new net.minecraft.entity.EntityEquipment());
        var failure = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> {
            FabricRollbackInventory.capture(source, items);
        }).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    @Test void itemAndTypedMetadataApisDoNotFallThroughToLossyCommonImplementations() throws Exception {
        var items = items();
        var generic = items.create(Material.STONE, 1);
        overrides(com.projectkorra.projectkorra.platform.mc.inventory.ItemStack.class, generic.getClass());
        overrides(ItemMeta.class, generic.getItemMeta().getClass());
        overrides(LeatherArmorMeta.class, items.create(Material.LEATHER_HELMET, 1).getItemMeta().getClass());
        overrides(PotionMeta.class, items.create(Material.POTION, 1).getItemMeta().getClass());
        overrides(SkullMeta.class, items.create(Material.PLAYER_HEAD, 1).getItemMeta().getClass());
        assertInstanceOf(Damageable.class, generic.getItemMeta());
    }

    @Test void nativeCaptureMetadataEditsClonesAndRollbackPreserveUnexposedComponents() {
        var items = items();
        ItemStack nativeStack = new ItemStack(Items.DIAMOND_BOOTS);
        nativeStack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("original").formatted(Formatting.GOLD, Formatting.BOLD));
        nativeStack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        NbtCompound custom = new NbtCompound(), persistent = new NbtCompound();
        custom.putByteArray("opaque", new byte[]{1, 2, 3});
        persistent.putString("test:remove", "before");
        persistent.putInt("test:numeric", 42);
        custom.put("PublicBukkitValues", persistent);
        nativeStack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(custom));
        var item = items.capture(nativeStack);
        nativeStack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("outside"));
        ItemMeta meta = item.getItemMeta();
        assertEquals("\u00a76\u00a7loriginal", meta.getDisplayName());
        var saved = capture(item, meta);
        meta.setCustomData("test:remove", null);
        meta.setCustomData("test:add", "new");
        assertTrue(item.setItemMeta(meta));
        meta.setDisplayName("not applied yet");
        ItemStack edited = items.nativeCopy(item);
        assertEquals("original", edited.get(DataComponentTypes.CUSTOM_NAME).getString());
        assertTrue(edited.get(DataComponentTypes.CUSTOM_NAME).getStyle().isBold());
        assertTrue(edited.get(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE));
        NbtCompound data = edited.get(DataComponentTypes.CUSTOM_DATA).copyNbt();
        assertArrayEquals(new byte[]{1, 2, 3}, data.getByteArray("opaque").orElseThrow());
        assertFalse(data.getCompound("PublicBukkitValues").orElseThrow().contains("test:remove"));
        assertEquals(42, data.getCompound("PublicBukkitValues").orElseThrow().getInt("test:numeric").orElseThrow());
        var clone = item.clone();
        var cloneMeta = clone.getItemMeta();
        cloneMeta.setDisplayName("clone");
        clone.setItemMeta(cloneMeta);
        assertEquals("\u00a76\u00a7loriginal", item.getItemMeta().getDisplayName());
        saved.restore();
        assertEquals("before", item.getItemMeta().getCustomData("test:remove"));
        assertNull(item.getItemMeta().getCustomData("test:add"));
        assertEquals("\u00a76\u00a7loriginal", meta.getDisplayName());
    }

    @Test void zeroCountAliasesCanBeRestoredAndRevivedWithoutLosingNativeComponents() {
        var items = items();
        var item = items.create(Material.DIAMOND_BOOTS, 1);
        item.setDurability((short) 17);
        var meta = item.getItemMeta();
        meta.setCustomData("test:key", "retained");
        item.setItemMeta(meta);
        item.setAmount(0);
        var saved = capture(item);
        var clonedWhileEmpty = item.clone();
        item.setAmount(5);
        item.setDurability((short) 100);
        saved.restore();
        assertEquals(0, item.getAmount());
        item.setAmount(1);
        clonedWhileEmpty.setAmount(1);
        assertEquals(Material.DIAMOND_BOOTS, item.getType());
        assertEquals(17, item.getDurability());
        assertEquals(17, clonedWhileEmpty.getDurability());
        assertEquals("retained", item.getItemMeta().getCustomData("test:key"));
        var empty = items.emptyStack();
        empty.setType(Material.STONE);
        assertEquals(Material.STONE, empty.getType());
        assertEquals(1, empty.getAmount());
    }

    @Test void productionInventoryUsesNativeComponentEqualityAndRetainsItsItemAliases() {
        var items = items();
        var inventory = new RollbackInventory(FabricRollbackInventory.layout(), items,
                new com.projectkorra.projectkorra.platform.mc.inventory.ItemStack[0], 0, 64);
        var first = items.create(Material.STONE, 60);
        var different = items.create(Material.STONE, 2);
        var meta = different.getItemMeta(); meta.setCustomData("test:different", "yes"); different.setItemMeta(meta);
        inventory.setItem(0, first); inventory.setItem(1, different);
        var alias = inventory.getItem(0);
        var before = capture(inventory);
        assertTrue(inventory.addItem(new com.projectkorra.projectkorra.platform.mc.inventory.ItemStack(Material.STONE, 6)).isEmpty());
        assertEquals(64, inventory.getItem(0).getAmount());
        assertEquals(2, inventory.getItem(1).getAmount());
        assertEquals(2, inventory.getItem(2).getAmount());
        assertEquals(64, alias.getAmount());
        before.restore();
        assertSame(alias, inventory.getItem(0));
        assertEquals(60, alias.getAmount());
        assertNull(inventory.getItem(2));
    }

    @Test void potionLeatherAndSkullEditsUseTheirNativeComponentsWithoutProfileLookup() {
        var items = items();
        var leather = items.create(Material.LEATHER_HELMET, 1);
        var color = (LeatherArmorMeta) leather.getItemMeta();
        color.setColor(Color.RED); leather.setItemMeta(color);
        assertEquals(Color.RED.asRGB(), items.nativeCopy(leather).get(DataComponentTypes.DYED_COLOR).rgb());
        var potion = items.create(Material.POTION, 1);
        var potionMeta = (PotionMeta) potion.getItemMeta();
        potionMeta.setBasePotionData(new PotionData(PotionType.valueOf("LONG_SWIFTNESS")));
        potion.setItemMeta(potionMeta);
        assertEquals("LONG_SWIFTNESS", ((PotionMeta) potion.getItemMeta()).getBasePotionData().getType().name());
        assertFalse(leather.setItemMeta(potionMeta));
        var head = items.create(Material.PLAYER_HEAD, 1);
        var skull = (SkullMeta) head.getItemMeta();
        UUID id = new UUID(0, 123);
        skull.setProfileId(id);
        skull.setTextureUrl("https://textures.minecraft.net/texture/fixture");
        assertTrue(head.setItemMeta(skull));
        var restored = (SkullMeta) head.getItemMeta();
        assertEquals(id, restored.getProfileId());
        assertEquals("https://textures.minecraft.net/texture/fixture", restored.getTextureUrl());
        assertFalse(restored.getTexture().isBlank());
        ProfileComponent profile = items.nativeCopy(head).get(DataComponentTypes.PROFILE);
        assertEquals(id, profile.getGameProfile().id());
        String complex = "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/old\",\"metadata\":{\"model\":\"slim\"}},\"CAPE\":{\"url\":\"https://textures.minecraft.net/texture/cape\"}},\"timestamp\":123}";
        restored.setTexture(Base64.getEncoder().encodeToString(complex.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        head.setItemMeta(restored);
        SkullMeta urlOnly = (SkullMeta) head.getItemMeta();
        urlOnly.setTextureUrl("https://textures.minecraft.net/texture/new"); head.setItemMeta(urlOnly);
        SkullMeta changed = (SkullMeta) head.getItemMeta();
        String payload = new String(Base64.getDecoder().decode(changed.getTexture()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(payload.contains("slim"));
        assertTrue(payload.contains("cape"));
        assertTrue(payload.contains("123"));
        assertEquals("https://textures.minecraft.net/texture/new", changed.getTextureUrl());
        changed.setTextureUrl(""); head.setItemMeta(changed);
        assertEquals("", ((SkullMeta) head.getItemMeta()).getTextureUrl());
        assertTrue(new String(Base64.getDecoder().decode(((SkullMeta) head.getItemMeta()).getTexture()), java.nio.charset.StandardCharsets.UTF_8).contains("cape"));
    }

    @Test void nativeItemContextAndThreadBoundariesAreEnforced() {
        var first = items(); var second = items();
        var item = first.create(Material.STONE, 1);
        assertThrows(IllegalArgumentException.class, () -> second.copy(item));
        assertThrows(IllegalArgumentException.class, () -> second.create(Material.STONE, 1).setItemMeta(item.getItemMeta()));
        var failure = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> item.setAmount(5)).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        var schemaMissing = new RollbackNativeItems<>(new FabricRollbackItems(registries, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> schemaMissing.create(Material.STONE, 1).getItemMeta());
    }

    @Test void existingAbilityConstructorsUseNativeItemsOnlyInsideTheirSessionScope() {
        var items = items();
        var other = items();
        var outside = new com.projectkorra.projectkorra.platform.mc.inventory.ItemStack(Material.STONE, 2);
        assertSame(outside, outside.simulationView());
        com.projectkorra.projectkorra.platform.mc.inventory.ItemStack inside;
        try (var scope = RollbackItemScope.using(items)) {
            inside = new com.projectkorra.projectkorra.platform.mc.inventory.ItemStack(Material.LEATHER_HELMET, 1);
            assertNotSame(inside, inside.simulationView());
            var meta = (LeatherArmorMeta) inside.getItemMeta();
            meta.setColor(Color.RED); inside.setItemMeta(meta);
            inside.setAmount(0);
            var emptyCopy = items.copy(inside);
            emptyCopy.setAmount(1);
            assertEquals(Material.LEATHER_HELMET, emptyCopy.getType());
            assertEquals(Color.RED.asRGB(), ((LeatherArmorMeta) emptyCopy.getItemMeta()).getColor().asRGB());
            var saved = capture(inside);
            inside.setAmount(3);
            saved.restore();
            assertEquals(0, inside.getAmount());
            try (var nested = RollbackItemScope.using(other)) {
                var nestedItem = new com.projectkorra.projectkorra.platform.mc.inventory.ItemStack(Material.STONE, 1);
                assertThrows(IllegalArgumentException.class, () -> items.copy(nestedItem));
                assertDoesNotThrow(() -> other.copy(nestedItem));
            }
            assertDoesNotThrow(() -> items.copy(new com.projectkorra.projectkorra.platform.mc.inventory.ItemStack(Material.STONE, 1)));
            var convertedClone = outside.clone();
            assertNotSame(convertedClone, convertedClone.simulationView());
            assertEquals(2, convertedClone.getAmount());
            assertTrue(outside.isSimilar(items.create(Material.STONE, 5)));
            var named = items.create(Material.STONE, 5);
            var namedMeta = named.getItemMeta(); namedMeta.setDisplayName("different"); named.setItemMeta(namedMeta);
            assertFalse(outside.isSimilar(named));
            assertTrue(items.isEmpty(new com.projectkorra.projectkorra.platform.mc.inventory.ItemStack()));
        }
        var later = new com.projectkorra.projectkorra.platform.mc.inventory.ItemStack(Material.STONE, 1);
        assertSame(later, later.simulationView());
        inside.setAmount(1);
        assertEquals(Material.LEATHER_HELMET, inside.getType(), "an item retains its captured native backing after scope exit");
    }


    @Test void richTextMetadataHasTheSameLegacyProjectionOnBothPlatforms() {
        var items = items();
        ItemStack source = new ItemStack(Items.STONE);
        source.set(DataComponentTypes.CUSTOM_NAME, Text.empty().formatted(Formatting.RED)
                .append(Text.literal("child").formatted(Formatting.BOLD))
                .append(Text.literal("tail").formatted(Formatting.BLUE, Formatting.ITALIC)));
        var item = items.capture(source);
        assertEquals("\u00a7c\u00a7r\u00a7lchild\u00a79\u00a7otail", item.getItemMeta().getDisplayName());
        source.set(DataComponentTypes.CUSTOM_NAME, Text.literal("styles").formatted(Formatting.OBFUSCATED, Formatting.BOLD,
                Formatting.UNDERLINE, Formatting.STRIKETHROUGH, Formatting.ITALIC));
        assertEquals("\u00a7l\u00a7o\u00a7n\u00a7m\u00a7kstyles", items.capture(source).getItemMeta().getDisplayName());
        var meta = item.getItemMeta();
        meta.setDisplayName("\u00a7cfirst\u00a79second\nline");
        item.setItemMeta(meta);
        assertEquals("\u00a7cfirst\u00a79second\nline", item.getItemMeta().getDisplayName());
    }

    @Test void everyNativeItemHasAnExactCommonMaterialName() {
        List<String> missing = Registries.ITEM.getIds().stream().map(id -> id.getPath().toUpperCase(Locale.ROOT))
                .filter(name -> { try { Material.valueOf(name); return false; } catch (IllegalArgumentException ignored) { return true; } }).sorted().toList();
        assertTrue(missing.isEmpty(), () -> "Unrepresented native items: " + missing);
    }
    private static RollbackStateGraph.Snapshot capture(Object... roots) {
        return new RollbackStateGraph(value -> false, field -> true, 10_000).capture(Arrays.asList(roots), List.of());
    }
    private static void overrides(Class<?> api, Class<?> view) throws Exception {
        for (var method : api.getMethods()) {
            if (method.getDeclaringClass() == Object.class || Modifier.isStatic(method.getModifiers()) || Modifier.isFinal(method.getModifiers())) continue;
            assertFalse(method.getDeclaringClass().equals(view.getMethod(method.getName(), method.getParameterTypes()).getDeclaringClass()), method.toString());
        }
    }
}
