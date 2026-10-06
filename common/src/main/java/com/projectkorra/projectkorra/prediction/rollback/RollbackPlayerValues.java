package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Portable, detached native player value fields. Names are shared semantic keys,
 * not reflective member names supplied to the receiving loader. Native adapters
 * must check their complete fixed schema before applying any field. This component
 * does not include tracked data, effects, attributes, items, services or entity links.
 */
public final class RollbackPlayerValues {
    public static final int VERSION = 1, MAXIMUM_FIELDS = 512, MAXIMUM_BYTES = 1_048_576;
    // Ordinals are part of VERSION's wire schema.
    public enum Kind { BOOLEAN, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, CHAR, ENUM, VECTOR, BOX, BLOCK, CHUNK, DIMENSIONS, OPTIONAL_BLOCK }

    public record Vector(double x, double y, double z) {
        public Vector { finite(x); finite(y); finite(z); }
    }
    public record Block(int x, int y, int z) { }
    public record Chunk(int x, int z) { }
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Box {
            finite(minX); finite(minY); finite(minZ); finite(maxX); finite(maxY); finite(maxZ);
            if (minX > maxX || minY > maxY || minZ > maxZ) throw invalid("bounds");
        }
    }
    public record Dimensions(float width, float height, float eyeHeight, boolean fixed, Map<String, List<Vector>> attachments) {
        public Dimensions {
            finite(width); finite(height); finite(eyeHeight);
            if (width < 0 || height < 0 || attachments.size() > 16) throw invalid("dimensions");
            var copy = new TreeMap<String, List<Vector>>();
            attachments.forEach((key, points) -> {
                name(key);
                if (points.size() > 128) throw invalid("attachment points");
                copy.put(key, List.copyOf(points));
            });
            attachments = Collections.unmodifiableMap(copy);
        }
    }
    public record Cell(Kind kind, Object value) {
        public Cell {
            Objects.requireNonNull(kind);
            if (value == null) {
                if (kind.ordinal() < Kind.ENUM.ordinal()) throw invalid("null primitive");
            } else {
                Class<?> expected = switch (kind) {
                    case BOOLEAN -> Boolean.class; case BYTE -> Byte.class; case SHORT -> Short.class;
                    case INT -> Integer.class; case LONG -> Long.class; case FLOAT -> Float.class;
                    case DOUBLE -> Double.class; case CHAR -> Character.class; case ENUM -> String.class;
                    case VECTOR -> Vector.class; case BOX -> Box.class; case BLOCK -> Block.class;
                    case CHUNK -> Chunk.class; case DIMENSIONS -> Dimensions.class; case OPTIONAL_BLOCK -> Optional.class;
                };
                if (!expected.isInstance(value)) throw invalid("value type " + kind);
                if (value instanceof Float v) finite(v);
                if (value instanceof Double v) finite(v);
                if (kind == Kind.ENUM) name((String) value);
                if (value instanceof Optional<?> optional && optional.isPresent() && !(optional.get() instanceof Block)) throw invalid("optional block");
            }
        }
    }

    private final SortedMap<String, Cell> fields;
    public RollbackPlayerValues(Map<String, Cell> fields) {
        if (fields.size() > MAXIMUM_FIELDS) throw invalid("field count");
        var copy = new TreeMap<String, Cell>();
        fields.forEach((key, value) -> { name(key); copy.put(key, Objects.requireNonNull(value)); });
        this.fields = Collections.unmodifiableSortedMap(copy);
    }
    public SortedMap<String, Cell> fields() { return fields; }

    /** Nullable native references in the paired 1.21.11 player-field schema. */
    public static Cell requireField(String key, Cell cell) {
        if (cell == null || (cell.value() == null && !NULLABLE_FIELDS.contains(key))) throw invalid("missing/null field " + key);
        return cell;
    }
    private static final Set<String> NULLABLE_FIELDS = Set.of("entity.lastKnownPosition", "entity.lastLavaContact", "living.lastPos", "living.swingingArm",
            "player.currentImpulseImpactPos", "server.levitationStartPos", "server.startingToFallPosition");

    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(new FilterOutputStream(bytes) {
                int count;
                void reserve(int n) { if ((long) count + n > MAXIMUM_BYTES) throw invalid("wire budget"); count += n; }
                @Override public void write(int b) { reserve(1); bytes.write(b); }
                @Override public void write(byte[] b, int off, int len) { reserve(len); bytes.write(b, off, len); }
            });
            out.writeInt(VERSION); out.writeInt(fields.size());
            for (var entry : fields.entrySet()) {
                text(out, entry.getKey());
                Cell cell = entry.getValue();
                out.writeByte(cell.kind.ordinal()); out.writeBoolean(cell.value != null);
                if (cell.value != null) write(out, cell);
            }
            return bytes.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    public static RollbackPlayerValues decode(byte[] bytes) {
        if (bytes.length > MAXIMUM_BYTES) throw invalid("wire budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw invalid("version");
            int count = bounded(in.readInt(), MAXIMUM_FIELDS);
            var fields = new TreeMap<String, Cell>();
            String previous = "";
            for (int i = 0; i < count; i++) {
                String key = text(in);
                if (key.compareTo(previous) <= 0) throw invalid("field ordering/duplicate");
                previous = key;
                Kind kind = Kind.values()[bounded(in.readUnsignedByte(), Kind.values().length - 1)];
                fields.put(key, new Cell(kind, bool(in) ? read(in, kind) : null));
            }
            if (in.available() != 0) throw invalid("trailing data");
            return new RollbackPlayerValues(fields);
        } catch (IOException exception) { throw new IllegalArgumentException("Invalid player values: malformed/truncated data", exception); }
    }

    @SuppressWarnings("unchecked")
    private static void write(DataOutputStream out, Cell cell) throws IOException {
        Object value = cell.value;
        switch (cell.kind) {
            case BOOLEAN -> out.writeBoolean((Boolean) value);
            case BYTE -> out.writeByte((Byte) value);
            case SHORT -> out.writeShort((Short) value);
            case INT -> out.writeInt((Integer) value);
            case LONG -> out.writeLong((Long) value);
            case FLOAT -> out.writeFloat((Float) value);
            case DOUBLE -> out.writeDouble((Double) value);
            case CHAR -> out.writeChar((Character) value);
            case ENUM -> text(out, (String) value);
            case VECTOR -> vector(out, (Vector) value);
            case BOX -> {
                Box box = (Box) value;
                out.writeDouble(box.minX); out.writeDouble(box.minY); out.writeDouble(box.minZ);
                out.writeDouble(box.maxX); out.writeDouble(box.maxY); out.writeDouble(box.maxZ);
            }
            case BLOCK -> block(out, (Block) value);
            case CHUNK -> { Chunk chunk = (Chunk) value; out.writeInt(chunk.x); out.writeInt(chunk.z); }
            case OPTIONAL_BLOCK -> { var optional = (Optional<Block>) value; out.writeBoolean(optional.isPresent()); if (optional.isPresent()) block(out, optional.get()); }
            case DIMENSIONS -> {
                Dimensions dimensions = (Dimensions) value;
                out.writeFloat(dimensions.width); out.writeFloat(dimensions.height); out.writeFloat(dimensions.eyeHeight); out.writeBoolean(dimensions.fixed);
                out.writeByte(dimensions.attachments.size());
                for (var entry : dimensions.attachments.entrySet()) {
                    text(out, entry.getKey()); out.writeInt(entry.getValue().size());
                    for (Vector point : entry.getValue()) vector(out, point);
                }
            }
        }
    }
    private static Object read(DataInputStream in, Kind kind) throws IOException {
        return switch (kind) {
            case BOOLEAN -> bool(in); case BYTE -> in.readByte(); case SHORT -> in.readShort(); case INT -> in.readInt();
            case LONG -> in.readLong(); case FLOAT -> in.readFloat(); case DOUBLE -> in.readDouble(); case CHAR -> in.readChar();
            case ENUM -> text(in); case VECTOR -> vector(in); case BLOCK -> block(in);
            case BOX -> new Box(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble());
            case CHUNK -> new Chunk(in.readInt(), in.readInt());
            case OPTIONAL_BLOCK -> bool(in) ? Optional.of(block(in)) : Optional.empty();
            case DIMENSIONS -> {
                float width = in.readFloat(), height = in.readFloat(), eye = in.readFloat(); boolean fixed = bool(in);
                int count = bounded(in.readUnsignedByte(), 16);
                var attachments = new TreeMap<String, List<Vector>>(); String previous = "";
                for (int i = 0; i < count; i++) {
                    String key = text(in);
                    if (key.compareTo(previous) <= 0) throw invalid("attachment ordering/duplicate");
                    previous = key;
                    int points = bounded(in.readInt(), 128);
                    var values = new ArrayList<Vector>(points);
                    for (int j = 0; j < points; j++) values.add(vector(in));
                    attachments.put(key, values);
                }
                yield new Dimensions(width, height, eye, fixed, attachments);
            }
        };
    }
    private static void vector(DataOutputStream out, Vector value) throws IOException { out.writeDouble(value.x); out.writeDouble(value.y); out.writeDouble(value.z); }
    private static Vector vector(DataInputStream in) throws IOException { return new Vector(in.readDouble(), in.readDouble(), in.readDouble()); }
    private static void block(DataOutputStream out, Block value) throws IOException { out.writeInt(value.x); out.writeInt(value.y); out.writeInt(value.z); }
    private static Block block(DataInputStream in) throws IOException { return new Block(in.readInt(), in.readInt(), in.readInt()); }
    private static boolean bool(DataInputStream in) throws IOException { return bounded(in.readUnsignedByte(), 1) == 1; }
    private static int bounded(int n, int maximum) { if (n < 0 || n > maximum) throw invalid("count/index"); return n; }
    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeShort(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in) throws IOException {
        int length = bounded(in.readUnsignedShort(), 128);
        if (length > in.available()) throw invalid("string length");
        String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(in.readNBytes(length))).toString();
        name(value); return value;
    }
    private static void name(String value) { if (value == null || value.isEmpty() || value.length() > 128 || !value.matches("[A-Za-z0-9_.]+")) throw invalid("name"); }
    private static void finite(double value) { if (!Double.isFinite(value)) throw invalid("nonfinite value"); }
    private static IllegalArgumentException invalid(String detail) { return new IllegalArgumentException("Invalid player values: " + detail); }
}
