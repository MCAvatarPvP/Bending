package com.projectkorra.projectkorra.prediction.rollback;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Generated structure pieces clipped to each referencing chunk and the captured arena. */
public final class RollbackStructurePieces {
    public static final int MAXIMUM_PIECES = 8_192;
    public record Piece(String structure, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public Piece {
            if (structure.length() > 256 || !structure.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                    || minX > maxX || minY > maxY || minZ > maxZ
                    || minX >> 4 != maxX >> 4 || minZ >> 4 != maxZ >> 4) {
                throw new IllegalArgumentException("Invalid captured structure piece");
            }
        }
        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }
    private static final Comparator<Piece> ORDER = Comparator.comparing(Piece::structure)
            .thenComparingInt(Piece::minX).thenComparingInt(Piece::minY).thenComparingInt(Piece::minZ)
            .thenComparingInt(Piece::maxX).thenComparingInt(Piece::maxY).thenComparingInt(Piece::maxZ);
    public static final RollbackStructurePieces EMPTY = new RollbackStructurePieces(List.of());
    private final List<Piece> pieces;
    private final Map<Long, List<Piece>> byChunk = new HashMap<>();

    public RollbackStructurePieces(Collection<Piece> source) {
        if (source.size() > MAXIMUM_PIECES) throw new IllegalArgumentException("Captured structure piece limit");
        pieces = source.stream().distinct().sorted(ORDER).toList();
        for (Piece piece : pieces) byChunk.computeIfAbsent(chunk(piece.minX, piece.minZ), ignored -> new ArrayList<>()).add(piece);
    }
    private static long chunk(int x, int z) {
        return Integer.toUnsignedLong(x >> 4) | (long) (z >> 4) << 32;
    }
    public List<Piece> at(int x, int y, int z) {
        var candidates = byChunk.get(chunk(x, z));
        if (candidates == null) return List.of();
        return candidates.stream().filter(piece -> piece.contains(x, y, z)).toList();
    }
    public List<Piece> pieces() { return pieces; }
    @Override public boolean equals(Object other) { return other instanceof RollbackStructurePieces value && pieces.equals(value.pieces); }
    @Override public int hashCode() { return pieces.hashCode(); }

    void write(DataOutputStream out) throws IOException {
        out.writeShort(pieces.size());
        for (Piece piece : pieces) {
            out.writeUTF(piece.structure);
            out.writeInt(piece.minX); out.writeInt(piece.minY); out.writeInt(piece.minZ);
            out.writeInt(piece.maxX); out.writeInt(piece.maxY); out.writeInt(piece.maxZ);
        }
    }
    static RollbackStructurePieces read(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        if (count > MAXIMUM_PIECES) throw new IllegalArgumentException("Captured structure piece limit");
        var rows = new ArrayList<Piece>(count);
        for (int i = 0; i < count; i++) {
            var piece = new Piece(in.readUTF(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
            if (i > 0 && ORDER.compare(rows.getLast(), piece) >= 0) throw new IllegalArgumentException("Structure piece order");
            rows.add(piece);
        }
        return new RollbackStructurePieces(rows);
    }
}
