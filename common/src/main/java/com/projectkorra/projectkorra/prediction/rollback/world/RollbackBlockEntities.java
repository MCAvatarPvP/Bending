package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Cell;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;

import java.io.ByteArrayOutputStream;
import java.util.*;

/** Private native handles for the persisted block entity data owned by one rollback terrain. */
public abstract class RollbackBlockEntities<T, S> implements RollbackStateCell<Void> {
    private record Entry<T, S>(T tile, S state, byte[] canonical) { }
    private final Map<Position, Entry<T, S>> opened = new LinkedHashMap<>();
    private RollbackBlockStore terrain;

    public final void bind(RollbackBlockStore store) {
        Objects.requireNonNull(store);
        if (terrain != null && terrain != store) throw new IllegalStateException("Block entities belong to another terrain");
        store.bindBlockEntities(this);
        terrain = store;
    }

    protected abstract S decodeState(BlockData data);
    protected abstract T create(Position pos, S state, byte[] bytes);
    protected abstract byte[] encode(T tile);
    protected abstract void setState(T tile, S state);
    protected abstract void restore(T tile, S state, byte[] bytes);
    /** Apply the difference between native saves onto the original captured tag, retaining loader metadata. */
    protected abstract byte[] merge(byte[] original, byte[] before, byte[] after);

    protected final T owned(RollbackBlockStore store, Position position, S state) {
        if (terrain != store) throw new IllegalStateException("Native block queries require the bound private terrain");
        Cell cell = store.storedCell(position);
        if (!Objects.equals(state, decodeState(cell.data()))) {
            // Shape queries can inspect the prospective state while a logical replacement is still being validated.
            return Objects.requireNonNull(create(position, state, new byte[0]));
        }
        var entry = opened.get(position);
        if (entry != null) return entry.tile();
        if (!cell.hasBlockEntity()) return null;
        T tile = Objects.requireNonNull(create(position, state, cell.blockEntity()));
        opened.put(position, new Entry<>(tile, state, bounded(encode(tile))));
        return tile;
    }

    void flush(Position position) {
        var entry = opened.get(position);
        if (entry == null) return;
        byte[] current = bounded(encode(entry.tile()));
        if (Arrays.equals(entry.canonical(), current)) return;
        byte[] bytes = bounded(merge(terrain.storedCell(position).blockEntity(), entry.canonical(), current));
        terrain.recordBlockEntity(position, bytes);
        opened.put(position, new Entry<>(entry.tile(), entry.state(), current));
    }
    void flushAll() { opened.keySet().forEach(this::flush); }

    void replacing(Position position, Cell replacement, boolean preserveTile) {
        var entry = opened.get(position);
        if (entry == null) return;
        if (!preserveTile) {
            opened.remove(position);
            return;
        }
        S state = decodeState(replacement.data());
        setState(entry.tile(), state);
        opened.put(position, new Entry<>(entry.tile(), state, entry.canonical()));
    }

    /** Native loaders can also retain transient state and references that are absent from saved NBT. */
    protected Runnable checkpoint(T tile, S state, byte[] canonical) {
        return () -> restore(tile, state, canonical);
    }
    Runnable checkpoint() {
        var saved = new LinkedHashMap<>(opened);
        var restores = new ArrayList<Runnable>(saved.size());
        for (var entry : saved.values()) restores.add(checkpoint(entry.tile(), entry.state(), entry.canonical()));
        return () -> {
            restores.forEach(Runnable::run);
            opened.putAll(saved);
        };
    }
    void clear() { opened.clear(); }

    // The terrain captures handles and bytes together. Graph visitation order cannot separate the two.
    @Override public final Void captureRollbackState() { return null; }
    @Override public final void restoreRollbackState(Void ignored) { }

    public static final class Output extends ByteArrayOutputStream {
        @Override public void write(int value) { room(1); super.write(value); }
        @Override public void write(byte[] value, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, value.length);
            room(length);
            super.write(value, offset, length);
        }
        private void room(int length) {
            if (length > RollbackBlockSnapshot.MAXIMUM_BLOCK_ENTITY_BYTES - count)
                throw new IllegalArgumentException("Native block entity exceeds snapshot byte budget");
        }
    }

    private static byte[] bounded(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > RollbackBlockSnapshot.MAXIMUM_BLOCK_ENTITY_BYTES)
            throw new IllegalArgumentException("Native block entity outside snapshot byte budget");
        return bytes;
    }
}
