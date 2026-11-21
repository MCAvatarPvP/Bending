package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/** Full native component codec against a session-owned registry, without a live server or inventory. */
public final class PaperRollbackItemCodec {
    private final DynamicOps<Tag> ops;

    public PaperRollbackItemCodec(Object registryLookup) {
        // Respect providers which override serialization ownership during bootstrap.
        ops = ((HolderLookup.Provider) Objects.requireNonNull(registryLookup, "registryLookup"))
                .createSerializationContext(NbtOps.INSTANCE);
    }

    public RollbackItemData encode(Object nativeItem) {
        ItemStack item = (ItemStack) Objects.requireNonNull(nativeItem, "nativeItem");
        if (item.isEmpty()) return RollbackItemData.EMPTY;
        Tag tag = ItemStack.CODEC.encodeStart(ops, item).getOrThrow(IllegalArgumentException::new);
        var bytes = new RollbackItemData.Output();
        try (var output = new DataOutputStream(bytes)) {
            NbtIo.writeAnyTag(tag, output);
        } catch (IOException failure) { throw new IllegalArgumentException("Could not encode rollback item", failure); }
        RollbackItemData data = bytes.snapshot();
        if (!ItemStack.matches(item, decode(data))) {
            throw new IllegalArgumentException("Native item contains components that do not survive serialization");
        }
        return data;
    }

    public ItemStack decode(RollbackItemData data) {
        Objects.requireNonNull(data, "data");
        if (data.isEmpty()) return ItemStack.EMPTY;
        try (var input = new DataInputStream(new ByteArrayInputStream(data.bytes()))) {
            var tracker = new NbtAccounter(RollbackItemData.MAXIMUM_DECODE_ALLOCATION, RollbackItemData.MAXIMUM_NBT_DEPTH);
            Tag tag = NbtIo.readAnyTag(input, tracker);
            if (input.available() != 0) throw new IllegalArgumentException("Trailing rollback item data");
            ItemStack item = ItemStack.CODEC.parse(ops, tag).getOrThrow(IllegalArgumentException::new);
            if (item.isEmpty()) throw new IllegalArgumentException("Noncanonical empty rollback item");
            return item;
        } catch (IOException failure) { throw new IllegalArgumentException("Could not decode rollback item", failure); }
    }

    public RollbackItemData encodeBukkit(org.bukkit.inventory.ItemStack item) {
        return item == null ? RollbackItemData.EMPTY : encode(CraftItemStack.asNMSCopy(item));
    }

    /** Returned stack is detached and can be handed to a logical item view or finalized effect. */
    public org.bukkit.inventory.ItemStack decodeBukkit(RollbackItemData data) {
        return CraftItemStack.asCraftMirror(decode(data));
    }
}
