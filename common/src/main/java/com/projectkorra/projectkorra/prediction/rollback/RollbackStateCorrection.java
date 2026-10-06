package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.util.*;

/** Detached repair envelope. Payload decoding is exclusively through agreed local loader schemas. */
public record RollbackStateCorrection(UUID session, long publication, long revision, long tick,
                                      String definitions, byte[] payload) {
    public static final int VERSION = 1;
    public static final int HEADER_BYTES = 80;
    public static final int MAXIMUM_BYTES = RollbackBootstrapData.MAXIMUM_BYTES;
    public RollbackStateCorrection {
        Objects.requireNonNull(session); Objects.requireNonNull(definitions); Objects.requireNonNull(payload);
        if (publication < 1 || revision < 0 || tick < 0 || tick == Long.MAX_VALUE
                || !definitions.matches("[0-9a-f]{64}") || payload.length < 1 || payload.length > MAXIMUM_BYTES - HEADER_BYTES)
            throw new IllegalArgumentException("State correction bounds");
        payload = payload.clone();
    }
    @Override public byte[] payload() { return payload.clone(); }
    @Override public boolean equals(Object value) {
        return value instanceof RollbackStateCorrection other && session.equals(other.session)
                && publication == other.publication && revision == other.revision && tick == other.tick
                && definitions.equals(other.definitions) && Arrays.equals(payload, other.payload);
    }
    @Override public int hashCode() { return 31 * Objects.hash(session, publication, revision, tick, definitions) + Arrays.hashCode(payload); }
    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(HEADER_BYTES + payload.length); var out = new DataOutputStream(bytes);
            out.writeInt(VERSION); out.writeLong(session.getMostSignificantBits()); out.writeLong(session.getLeastSignificantBits());
            out.writeLong(publication); out.writeLong(revision); out.writeLong(tick);
            out.write(HexFormat.of().parseHex(definitions)); out.writeInt(payload.length); out.write(payload);
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    public static RollbackStateCorrection decode(byte[] bytes, int maximumBytes) {
        Objects.requireNonNull(bytes);
        if (maximumBytes <= HEADER_BYTES || maximumBytes > MAXIMUM_BYTES || bytes.length > maximumBytes)
            throw new IllegalArgumentException("State correction byte budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw new IllegalArgumentException("Unsupported state correction version");
            var session = new UUID(in.readLong(), in.readLong());
            long publication = in.readLong(), revision = in.readLong(), tick = in.readLong();
            byte[] definitions = new byte[32]; in.readFully(definitions);
            int length = in.readInt();
            if (length < 1 || length != in.available()) throw new IllegalArgumentException("State correction payload length");
            return new RollbackStateCorrection(session, publication, revision, tick, HexFormat.of().formatHex(definitions), in.readAllBytes());
        } catch (IOException failure) { throw new IllegalArgumentException("Truncated state correction", failure); }
    }
}
