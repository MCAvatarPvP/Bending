package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockEntities;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockSnapshot;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.storage.NbtReadView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.BlockPos;

import java.io.*;
import java.util.Objects;

/** Reads private tile data using the same named-compound format as a captured Paper chunk. */
public final class FabricRollbackBlockEntities extends RollbackBlockEntities<BlockEntity, BlockState>
        implements FabricRollbackWorldQueries.BlockEntities<Void> {
    private final RegistryWrapper.WrapperLookup registries;

    private final RollbackStateGraph graph = new RollbackStateGraph(value -> {
        if (value instanceof net.minecraft.world.World || value instanceof net.minecraft.entity.Entity)
            throw new IllegalStateException("A detached block entity retained a world or entity");
        return FabricRollbackNativePlayerState.registryMetadata(value) || value instanceof net.minecraft.block.Block
                || value instanceof net.minecraft.block.entity.BlockEntityType<?>;
    }, FabricRollbackNativePlayerState::simulationField, 65_536);

    public FabricRollbackBlockEntities(RegistryWrapper.WrapperLookup registries) {
        this.registries = Objects.requireNonNull(registries);
    }
    @Override public BlockEntity get(RollbackBlockStore terrain, Position pos, BlockState state) {
        return state.hasBlockEntity() ? owned(terrain, pos, state) : null;
    }
    @Override protected BlockState decodeState(BlockData data) { return FabricRollbackGeometry.decode(data); }
    @Override protected BlockEntity create(Position pos, BlockState state, byte[] bytes) {
        var nativePos = new BlockPos(pos.x(), pos.y(), pos.z());
        if (!(state.getBlock() instanceof BlockEntityProvider provider))
            throw new IllegalArgumentException("No native block entity provider at " + pos);
        var tile = Objects.requireNonNull(provider.createBlockEntity(nativePos, state), "Missing native block entity");
        if (bytes.length > 0) {
            var tag = decode(bytes);
            if (tag.getInt("x").orElseThrow() != pos.x() || tag.getInt("y").orElseThrow() != pos.y()
                    || tag.getInt("z").orElseThrow() != pos.z()) throw new IllegalArgumentException("Captured block entity coordinates differ");
            String id = tag.getString("id").orElseThrow();
            if (!id.equals("DUMMY")) {
                if (!id.equals(Registries.BLOCK_ENTITY_TYPE.getId(tile.getType()).toString()))
                    throw new IllegalArgumentException("Captured block entity type differs from terrain");
                read(tile, tag);
            }
        }
        return tile;
    }
    private void read(BlockEntity tile, NbtCompound tag) {
        var errors = new ErrorReporter.Impl();
        tile.read(NbtReadView.create(errors, registries, tag));
        if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid native data for " + tile.getPos());
    }
    @Override protected void setState(BlockEntity tile, BlockState state) {
        if (!tile.supports(state)) throw new IllegalArgumentException("Native block entity cannot use this block state");
        tile.setCachedState(state);
    }
    @Override protected void restore(BlockEntity tile, BlockState state, byte[] bytes) {
        setState(tile, state);
        tile.cancelRemoval();
        read(tile, decode(bytes));
    }
    @Override protected Runnable checkpoint(BlockEntity tile, BlockState state, byte[] canonical) {
        return graph.capture(java.util.List.of(tile), java.util.List.of())::restore;
    }
    @Override protected byte[] encode(BlockEntity tile) {
        return encode(tile.createNbtWithIdentifyingData(registries));
    }
    @Override protected byte[] merge(byte[] original, byte[] before, byte[] after) {
        if (original.length == 0) return after;
        var result = decode(original);
        var old = decode(before);
        var current = decode(after);
        for (String key : old.getKeys()) if (!current.contains(key)) result.remove(key);
        for (var entry : current.entrySet()) {
            if (!entry.getValue().equals(old.get(entry.getKey())) || entry.getKey().equals("id"))
                result.put(entry.getKey(), entry.getValue());
        }
        return encode(result);
    }
    private static NbtCompound decode(byte[] bytes) {
        if (bytes.length > RollbackBlockSnapshot.MAXIMUM_BLOCK_ENTITY_BYTES)
            throw new IllegalArgumentException("Captured block entity exceeds snapshot byte budget");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            var tag = NbtIo.readCompound(in, new NbtSizeTracker(4L * RollbackBlockSnapshot.MAXIMUM_BLOCK_ENTITY_BYTES, 64));
            if (in.available() != 0) throw new IllegalArgumentException("Trailing captured block entity data");
            return tag;
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid captured block entity NBT", failure); }
    }
    private static byte[] encode(NbtCompound tag) {
        var bytes = new Output();
        try (var out = new DataOutputStream(bytes)) { NbtIo.writeCompound(tag, out); }
        catch (IOException failure) { throw new IllegalArgumentException("Could not encode private block entity NBT", failure); }
        return bytes.toByteArray();
    }
}
