package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackItemCodecNativeTest {
    private static HolderLookup.Provider registries;
    private static PaperRollbackItemCodec codec;

    @BeforeAll static void bootstrapRegistry() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createLookup();
        codec = new PaperRollbackItemCodec(registries);
    }

    @Test void sharedWireFixturePreservesUnknownMetadataAndRemovedDefaultComponents() throws Exception {
        ItemStack item = (ItemStack) codec.decode(fixture());
        assertTrue(item.is(Items.DIAMOND_BOOTS));
        assertEquals(1, item.getCount());
        assertEquals(17, item.getDamageValue());
        assertEquals("Rollback fixture", item.get(DataComponents.CUSTOM_NAME).getString());
        assertTrue(item.get(DataComponents.CUSTOM_NAME).getStyle().isBold());
        assertEquals("gold", item.get(DataComponents.CUSTOM_NAME).getStyle().getColor().serialize());
        assertEquals(3, item.getEnchantments().getLevel(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING)));
        assertFalse(item.has(DataComponents.ATTRIBUTE_MODIFIERS));
        CompoundTag opaque = item.get(DataComponents.CUSTOM_DATA).copyTag();
        assertArrayEquals(new byte[]{0, 2, -1}, opaque.getByteArray("opaque").orElseThrow());
        assertEquals("value", opaque.getCompound("nested").orElseThrow().getString("kept").orElseThrow());
        assertTrue(ItemStack.matches(item, (ItemStack) codec.decode(codec.encode(item))));
        assertTrue(ItemStack.matches(item, (ItemStack) codec.decode(codec.encodeBukkit(codec.decodeBukkit(fixture())))));
    }

    @Test void snapshotsAndDecodedItemsAreDetachedFromBothNativeItemAndByteAliases() throws Exception {
        ItemStack item = (ItemStack) codec.decode(fixture());
        ItemStack original = item.copy();
        RollbackItemData saved = codec.encode(item);
        item.setDamageValue(100);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
        byte[] bytes = saved.bytes();
        bytes[0] = 0;
        ItemStack restored = (ItemStack) codec.decode(saved);
        assertTrue(ItemStack.matches(original, restored));
        restored.setCount(2);
        assertEquals(1, ((ItemStack) codec.decode(saved)).getCount());
        assertEquals(RollbackItemData.EMPTY, codec.encode(ItemStack.EMPTY));
        assertTrue(((ItemStack) codec.decode(RollbackItemData.EMPTY)).isEmpty());
    }

    @Test void malformedUnknownAndTrailingDataNeverBecomePartialItems() throws Exception {
        byte[] valid = fixture().bytes();
        assertThrows(RuntimeException.class, () -> codec.decode(new RollbackItemData(Arrays.copyOf(valid, valid.length - 1))));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new RollbackItemData(Arrays.copyOf(valid, valid.length + 1))));
        CompoundTag missingItem = new CompoundTag();
        missingItem.putString("id", "minecraft:missing_rollback_item");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(raw(missingItem)));
        CompoundTag missingComponent = new CompoundTag();
        missingComponent.putString("id", "minecraft:stone");
        CompoundTag components = new CompoundTag();
        components.putInt("minecraft:missing_rollback_component", 1);
        missingComponent.put("components", components);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(raw(missingComponent)));
    }

    @Test void nativeNbtLimitsBoundAllocationDepthAndEncodingWithoutReplacingText() {
        ItemStack large = new ItemStack(Items.STONE);
        CompoundTag data = new CompoundTag();
        data.putByteArray("tooLarge", new byte[RollbackItemData.MAXIMUM_BYTES]);
        large.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(large));
        ItemStack text = new ItemStack(Items.STONE);
        text.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(70_000)));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(text));
        CompoundTag root = new CompoundTag(), nested = root;
        for (int depth = 0; depth <= RollbackItemData.MAXIMUM_NBT_DEPTH; depth++) {
            CompoundTag child = new CompoundTag();
            nested.put("child", child);
            nested = child;
        }
        assertThrows(RuntimeException.class, () -> codec.decode(raw(root)));
        // Small packet claiming a huge byte array must fail its allocation quota before allocating it.
        assertThrows(RuntimeException.class, () -> codec.decode(new RollbackItemData(new byte[]{10, 7, 0, 1, 'x', 127, -1, -1, -1})));
    }

    private static RollbackItemData raw(CompoundTag tag) throws Exception {
        var bytes = new RollbackItemData.Output();
        try (var output = new DataOutputStream(bytes)) { NbtIo.writeAnyTag(tag, output); }
        return bytes.snapshot();
    }
    private static RollbackItemData fixture() throws Exception {
        try (var input = Objects.requireNonNull(PaperRollbackItemCodecNativeTest.class.getResourceAsStream("/rollback/item-components.base64"))) {
            return new RollbackItemData(Base64.getDecoder().decode(new String(input.readAllBytes(), StandardCharsets.US_ASCII).strip()));
        }
    }
}
