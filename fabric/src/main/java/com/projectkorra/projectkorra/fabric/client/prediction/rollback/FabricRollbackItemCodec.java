package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/** Full item/component data using the same native NBT wire representation as Paper. */
public final class FabricRollbackItemCodec {
    private final RegistryOps<NbtElement> ops;

    public FabricRollbackItemCodec(RegistryWrapper.WrapperLookup registryLookup) {
        ops = Objects.requireNonNull(registryLookup, "registryLookup").getOps(NbtOps.INSTANCE);
    }

    public RollbackItemData encode(ItemStack item) {
        Objects.requireNonNull(item, "item");
        if (item.isEmpty()) return RollbackItemData.EMPTY;
        NbtElement tag = ItemStack.CODEC.encodeStart(ops, item).getOrThrow(IllegalArgumentException::new);
        var bytes = new RollbackItemData.Output();
        try (var output = new DataOutputStream(bytes)) {
            // Strict writer: an unencodable string fails instead of being replaced with an empty string.
            NbtIo.writeForPacket(tag, output);
        } catch (IOException failure) { throw new IllegalArgumentException("Could not encode rollback item", failure); }
        RollbackItemData data = bytes.snapshot();
        if (!ItemStack.areEqual(item, decode(data))) {
            throw new IllegalArgumentException("Native item contains components that do not survive serialization");
        }
        return data;
    }

    public ItemStack decode(RollbackItemData data) {
        Objects.requireNonNull(data, "data");
        if (data.isEmpty()) return ItemStack.EMPTY;
        try (var input = new DataInputStream(new ByteArrayInputStream(data.bytes()))) {
            NbtElement tag = NbtIo.read(input, new NbtSizeTracker(RollbackItemData.MAXIMUM_DECODE_ALLOCATION, RollbackItemData.MAXIMUM_NBT_DEPTH));
            if (input.available() != 0) throw new IllegalArgumentException("Trailing rollback item data");
            ItemStack item = ItemStack.CODEC.parse(ops, tag).getOrThrow(IllegalArgumentException::new);
            if (item.isEmpty()) throw new IllegalArgumentException("Noncanonical empty rollback item");
            return item;
        } catch (IOException failure) { throw new IllegalArgumentException("Could not decode rollback item", failure); }
    }
}
