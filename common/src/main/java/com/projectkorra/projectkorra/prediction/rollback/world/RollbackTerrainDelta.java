package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Change;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;

import java.io.*;
import java.util.*;

/** The complete sparse terrain overlay relative to the negotiated arena seed at one frontier. */
public final class RollbackTerrainDelta {
    private static final int VERSION = 1;
    static final Comparator<Position> ORDER = Comparator.comparingInt(Position::y)
            .thenComparingInt(Position::z).thenComparingInt(Position::x);
    private RollbackTerrainDelta() { }

    public static byte[] encode(List<Change> changes, Bounds bounds, RollbackTerrainCodec.BlockStates states,
            RollbackTerrainCodec.Limits limits) {
        if (changes.size() > limits.maximumCells()) throw new IllegalArgumentException("Terrain correction cell budget");
        var bytes = new ByteArrayOutputStream();
        try {
            var out = RollbackTerrainCodec.bounded(bytes, limits);
            out.writeInt(VERSION);
            out.writeInt(changes.size());
            Position previous = null;
            long storage = 0;
            for (var change : changes) {
                var pos = change.position();
                requirePosition(bounds, pos, previous);
                out.writeInt(pos.x()); out.writeInt(pos.y()); out.writeInt(pos.z());
                storage += 48 + RollbackTerrainCodec.writeCell(out, change.value(), states, limits);
                if (storage > limits.maximumStorageBytes()) throw new IllegalArgumentException("Terrain correction storage budget");
                previous = pos;
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    public static List<Change> decode(byte[] bytes, Bounds bounds, RollbackTerrainCodec.BlockStates states,
            RollbackTerrainCodec.Limits limits) {
        if (bytes.length > limits.maximumWireBytes()) throw new IllegalArgumentException("Terrain correction byte budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw new IllegalArgumentException("Unsupported terrain correction");
            int count = in.readInt();
            if (count < 0 || count > limits.maximumCells() || count > in.available() / 43
                    || (long) count * 176 > limits.maximumStorageBytes())
                throw new IllegalArgumentException("Terrain correction size");
            var positions = new ArrayList<Position>(count);
            var cells = new ArrayList<RollbackTerrainCodec.WireCell>(count);
            Position previous = null;
            long storage = 0;
            for (int i = 0; i < count; i++) {
                var pos = new Position(in.readInt(), in.readInt(), in.readInt());
                requirePosition(bounds, pos, previous);
                var cell = RollbackTerrainCodec.readCell(in, limits);
                storage += 48 + cell.storage();
                if (storage > limits.maximumStorageBytes()) throw new IllegalArgumentException("Terrain correction storage budget");
                positions.add(pos);
                cells.add(cell);
                previous = pos;
            }
            if (in.available() != 0) throw new IllegalArgumentException("Trailing terrain correction data");
            var result = new ArrayList<Change>(count);
            for (int i = 0; i < count; i++) result.add(new Change(positions.get(i), cells.get(i).decode(states)));
            return List.copyOf(result);
        } catch (IOException malformed) { throw new IllegalArgumentException("Truncated terrain correction", malformed); }
    }

    static void requirePosition(Bounds bounds, Position position, Position previous) {
        if (!bounds.contains(position) || previous != null && ORDER.compare(previous, position) >= 0)
            throw new IllegalArgumentException("Out-of-bounds, duplicate or unordered terrain correction");
    }
}
