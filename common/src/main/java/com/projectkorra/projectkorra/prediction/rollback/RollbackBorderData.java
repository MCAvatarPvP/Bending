package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;

/** Detached native border state, including the original curve and previous tick of an active resize. */
public record RollbackBorderData(double centerX, double centerZ, int absoluteMaxSize, double damagePerBlock,
                                 double safeZone, int warningBlocks, int warningTime, double size, Transition transition) {
    public static final int VERSION = 1, MAXIMUM_BYTES = 128;
    public record Transition(double from, double to, long duration, long begin, long remaining, double previousSize) {
        public Transition {
            positiveSize(from); positiveSize(to); positiveSize(previousSize);
            if (from == to || duration < 0 || remaining < 0 || remaining > duration) throw invalid("resize");
        }
    }
    public RollbackBorderData {
        finite(centerX); finite(centerZ); finite(damagePerBlock); finite(safeZone); positiveSize(size);
        if (absoluteMaxSize <= 0 || warningBlocks < 0 || warningTime < 0) throw invalid("settings");
    }
    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(VERSION); out.writeDouble(centerX); out.writeDouble(centerZ); out.writeInt(absoluteMaxSize);
            out.writeDouble(damagePerBlock); out.writeDouble(safeZone); out.writeInt(warningBlocks); out.writeInt(warningTime); out.writeDouble(size);
            out.writeBoolean(transition != null);
            if (transition != null) {
                out.writeDouble(transition.from); out.writeDouble(transition.to); out.writeLong(transition.duration);
                out.writeLong(transition.begin); out.writeLong(transition.remaining); out.writeDouble(transition.previousSize);
            }
            return bytes.toByteArray();
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    public static RollbackBorderData decode(byte[] bytes) {
        if (bytes.length > MAXIMUM_BYTES) throw invalid("wire budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes)); if (in.readInt() != VERSION) throw invalid("version");
            double x = in.readDouble(), z = in.readDouble(); int limit = in.readInt();
            double damage = in.readDouble(), safe = in.readDouble(); int blocks = in.readInt(), time = in.readInt(); double size = in.readDouble();
            Transition transition = switch (in.readUnsignedByte()) {
                case 0 -> null;
                case 1 -> new Transition(in.readDouble(), in.readDouble(), in.readLong(), in.readLong(), in.readLong(), in.readDouble());
                default -> throw invalid("boolean");
            };
            var result = new RollbackBorderData(x, z, limit, damage, safe, blocks, time, size, transition);
            if (in.available() != 0) throw invalid("trailing data"); return result;
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid border: truncated data", failure); }
    }
    private static void finite(double value) { if (!Double.isFinite(value)) throw invalid("nonfinite value"); }
    private static void positiveSize(double value) { finite(value); if (value < 0) throw invalid("negative size"); }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException("Invalid border: " + reason); }
}
