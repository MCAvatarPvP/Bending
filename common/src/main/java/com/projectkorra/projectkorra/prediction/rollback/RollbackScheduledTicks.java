package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;

import java.io.*;
import java.util.*;

/** A bounded, ordered snapshot of the match's native block and fluid tick queues. */
public record RollbackScheduledTicks(Bounds bounds, int maximumBlockTicks, int maximumFluidTicks, long nextOrder,
                                     List<Entry> entries) {
    public static final int MAXIMUM_ENTRIES = 65_536, MAXIMUM_BYTES = 8_388_608;
    public enum Kind { BLOCK, FLUID }
    public record Entry(Kind kind, Position position, String id, long time, int priority, long order) {
        public Entry {
            Objects.requireNonNull(kind); Objects.requireNonNull(position); Objects.requireNonNull(id);
            if (id.length() > 256 || !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || priority < -3 || priority > 3)
                throw new IllegalArgumentException("Invalid scheduled tick");
        }
    }
    private record Key(Kind kind, Position position, String id) { }
    private static final Comparator<Entry> ORDER = Comparator.comparing(Entry::kind)
            .thenComparingLong(Entry::time).thenComparingInt(Entry::priority).thenComparingLong(Entry::order)
            .thenComparingInt(e -> e.position().x()).thenComparingInt(e -> e.position().y())
            .thenComparingInt(e -> e.position().z()).thenComparing(Entry::id);

    public RollbackScheduledTicks {
        Objects.requireNonNull(bounds);
        if (entries.size() > MAXIMUM_ENTRIES || maximumBlockTicks < 0 || maximumFluidTicks < 0)
            throw new IllegalArgumentException("Invalid scheduled tick budget");
        var keys = new HashSet<Key>();
        for (var entry : entries) {
            if (!bounds.contains(entry.position()) || entry.order() >= nextOrder
                    || !keys.add(new Key(entry.kind(), entry.position(), entry.id())))
                throw new IllegalArgumentException("Invalid scheduled tick region, order or duplicate");
        }
        entries = entries.stream().sorted(ORDER).toList();
    }

    public static RollbackScheduledTicks empty(Bounds region) {
        return new RollbackScheduledTicks(region, 65_536, 65_536, 0, List.of());
    }
    public void requireCaptureBounds(Bounds capture) {
        if (bounds.minX() < capture.minX() || bounds.minY() < capture.minY() || bounds.minZ() < capture.minZ()
                || bounds.maxX() > capture.maxX() || bounds.maxY() > capture.maxY() || bounds.maxZ() > capture.maxZ())
            throw new IllegalArgumentException("Scheduled terrain extends beyond captured blocks");
    }

    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(1);
            out.writeInt(bounds.minX()); out.writeInt(bounds.minY()); out.writeInt(bounds.minZ());
            out.writeInt(bounds.maxX()); out.writeInt(bounds.maxY()); out.writeInt(bounds.maxZ());
            out.writeInt(maximumBlockTicks); out.writeInt(maximumFluidTicks); out.writeLong(nextOrder);
            out.writeInt(entries.size());
            for (var e : entries) {
                out.writeByte(e.kind().ordinal()); out.writeInt(e.position().x()); out.writeInt(e.position().y());
                out.writeInt(e.position().z()); out.writeUTF(e.id()); out.writeLong(e.time());
                out.writeByte(e.priority()); out.writeLong(e.order());
                if (bytes.size() > MAXIMUM_BYTES) throw new IllegalArgumentException("Scheduled tick wire budget");
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    public static RollbackScheduledTicks decode(byte[] bytes) {
        if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("Scheduled tick wire budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 1) throw new IllegalArgumentException("Scheduled tick version");
            var bounds = new Bounds(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
            int blocks = in.readInt(), fluids = in.readInt(); long sequence = in.readLong(); int count = in.readInt();
            if (count < 0 || count > MAXIMUM_ENTRIES || count > in.available() / 32) throw new IllegalArgumentException("Scheduled tick count");
            var entries = new ArrayList<Entry>(count);
            for (int i = 0; i < count; i++) {
                int kind = in.readUnsignedByte();
                if (kind >= Kind.values().length) throw new IllegalArgumentException("Scheduled tick type");
                var e = new Entry(Kind.values()[kind], new Position(in.readInt(), in.readInt(), in.readInt()), in.readUTF(),
                        in.readLong(), in.readByte(), in.readLong());
                if (i != 0 && ORDER.compare(entries.getLast(), e) >= 0) throw new IllegalArgumentException("Scheduled tick order");
                entries.add(e);
            }
            if (in.available() != 0) throw new IllegalArgumentException("Trailing scheduled tick data");
            return new RollbackScheduledTicks(bounds, blocks, fluids, sequence, entries);
        } catch (IOException failure) { throw new IllegalArgumentException("Truncated scheduled tick data", failure); }
    }
}
