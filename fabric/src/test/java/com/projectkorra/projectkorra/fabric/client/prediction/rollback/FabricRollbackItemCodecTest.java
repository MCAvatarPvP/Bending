package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackItemCodecTest {
    private static RegistryWrapper.WrapperLookup registries;
    private static FabricRollbackItemCodec codec;

    @BeforeAll static void bootstrapRegistry() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        registries = BuiltinRegistries.createWrapperLookup();
        codec = new FabricRollbackItemCodec(registries);
    }

    @Test void sharedWireFixturePreservesUnknownMetadataAndRemovedDefaultComponents() throws Exception {
        ItemStack item = codec.decode(fixture());
        assertTrue(item.isOf(Items.DIAMOND_BOOTS));
        assertEquals(1, item.getCount());
        assertEquals(17, item.getDamage());
        assertEquals("Rollback fixture", item.get(DataComponentTypes.CUSTOM_NAME).getString());
        assertTrue(item.get(DataComponentTypes.CUSTOM_NAME).getStyle().isBold());
        assertEquals("gold", item.get(DataComponentTypes.CUSTOM_NAME).getStyle().getColor().getName());
        assertEquals(3, item.getEnchantments().getLevel(registries.getOrThrow(RegistryKeys.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING)));
        assertFalse(item.contains(DataComponentTypes.ATTRIBUTE_MODIFIERS));
        NbtCompound opaque = item.get(DataComponentTypes.CUSTOM_DATA).copyNbt();
        assertArrayEquals(new byte[]{0, 2, -1}, opaque.getByteArray("opaque").orElseThrow());
        assertEquals("value", opaque.getCompound("nested").orElseThrow().getString("kept").orElseThrow());
        assertTrue(ItemStack.areEqual(item, codec.decode(codec.encode(item))));
    }

    @Test void snapshotsAndDecodedItemsAreDetachedFromBothNativeItemAndByteAliases() throws Exception {
        ItemStack item = codec.decode(fixture());
        ItemStack original = item.copy();
        RollbackItemData saved = codec.encode(item);
        item.setDamage(100);
        item.set(DataComponentTypes.CUSTOM_NAME, Text.literal("changed"));
        byte[] bytes = saved.bytes();
        bytes[0] = 0;
        ItemStack restored = codec.decode(saved);
        assertTrue(ItemStack.areEqual(original, restored));
        restored.setCount(2);
        assertEquals(1, codec.decode(saved).getCount());
        assertEquals(RollbackItemData.EMPTY, codec.encode(ItemStack.EMPTY));
        assertTrue(codec.decode(RollbackItemData.EMPTY).isEmpty());
    }

    @Test void malformedUnknownAndTrailingDataNeverBecomePartialItems() throws Exception {
        byte[] valid = fixture().bytes();
        assertThrows(RuntimeException.class, () -> codec.decode(new RollbackItemData(Arrays.copyOf(valid, valid.length - 1))));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new RollbackItemData(Arrays.copyOf(valid, valid.length + 1))));
        NbtCompound missingItem = new NbtCompound();
        missingItem.putString("id", "minecraft:missing_rollback_item");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(raw(missingItem)));
        NbtCompound missingComponent = new NbtCompound();
        missingComponent.putString("id", "minecraft:stone");
        NbtCompound components = new NbtCompound();
        components.putInt("minecraft:missing_rollback_component", 1);
        missingComponent.put("components", components);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(raw(missingComponent)));
    }

    @Test void nativeNbtLimitsBoundAllocationDepthAndEncodingWithoutReplacingText() {
        ItemStack large = new ItemStack(Items.STONE);
        NbtCompound data = new NbtCompound();
        data.putByteArray("tooLarge", new byte[RollbackItemData.MAXIMUM_BYTES]);
        large.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(data));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(large));
        ItemStack text = new ItemStack(Items.STONE);
        text.set(DataComponentTypes.CUSTOM_NAME, Text.literal("x".repeat(70_000)));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(text));
        NbtCompound root = new NbtCompound(), nested = root;
        for (int depth = 0; depth <= RollbackItemData.MAXIMUM_NBT_DEPTH; depth++) {
            NbtCompound child = new NbtCompound();
            nested.put("child", child);
            nested = child;
        }
        assertThrows(RuntimeException.class, () -> codec.decode(raw(root)));
        assertThrows(RuntimeException.class, () -> codec.decode(new RollbackItemData(new byte[]{10, 7, 0, 1, 'x', 127, -1, -1, -1})));
    }

    private static RollbackItemData raw(NbtCompound tag) throws Exception {
        var bytes = new RollbackItemData.Output();
        try (var output = new DataOutputStream(bytes)) { NbtIo.writeForPacket(tag, output); }
        return bytes.snapshot();
    }
    private static RollbackItemData fixture() throws Exception {
        try (var input = Objects.requireNonNull(FabricRollbackItemCodecTest.class.getResourceAsStream("/rollback/item-components.base64"))) {
            return new RollbackItemData(Base64.getDecoder().decode(new String(input.readAllBytes(), StandardCharsets.US_ASCII).strip()));
        }
    }
}
