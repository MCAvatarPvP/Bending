package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.util.*;

/** Sized below Paper's plugin-message bound; assembled atomically before any simulation changes. */
public record RollbackStateCorrectionChunk(UUID session, long publication, int index, int count, byte[] data) {
    public static final String CHANNEL = "projectkorra:rollback_state";
    public static final int DATA_BYTES = 24_576;
    public static final int MAXIMUM_BYTES = DATA_BYTES + 32;
    public static final int MAXIMUM_PARTS = (RollbackStateCorrection.MAXIMUM_BYTES + DATA_BYTES - 1) / DATA_BYTES;

    public RollbackStateCorrectionChunk {
        Objects.requireNonNull(session); Objects.requireNonNull(data);
        if (publication < 1 || count < 1 || count > MAXIMUM_PARTS || index < 0 || index >= count
                || data.length < 1 || data.length > DATA_BYTES || index < count - 1 && data.length != DATA_BYTES
                || (long) (count - 1) * DATA_BYTES + (index == count - 1 ? data.length : 1) > RollbackStateCorrection.MAXIMUM_BYTES) {
            throw new IllegalArgumentException("State correction chunk bounds");
        }
        data = data.clone();
    }
    @Override public byte[] data() { return data.clone(); }
    @Override public boolean equals(Object value) {
        return value instanceof RollbackStateCorrectionChunk other && session.equals(other.session) && publication == other.publication
                && index == other.index && count == other.count && Arrays.equals(data, other.data);
    }
    @Override public int hashCode() { return 31 * Objects.hash(session, publication, index, count) + Arrays.hashCode(data); }

    public static List<RollbackStateCorrectionChunk> split(RollbackStateCorrection update) {
        byte[] encoded = update.encode();
        int count = (encoded.length + DATA_BYTES - 1) / DATA_BYTES;
        var result = new ArrayList<RollbackStateCorrectionChunk>(count);
        for (int index = 0; index < count; index++) result.add(new RollbackStateCorrectionChunk(update.session(), update.publication(), index, count,
                Arrays.copyOfRange(encoded, index * DATA_BYTES, Math.min(encoded.length, (index + 1) * DATA_BYTES))));
        return List.copyOf(result);
    }

    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(32 + data.length); var out = new DataOutputStream(bytes);
            out.writeInt(RollbackStateCorrection.VERSION); out.writeLong(session.getMostSignificantBits()); out.writeLong(session.getLeastSignificantBits());
            out.writeLong(publication); out.writeShort(index); out.writeShort(count); out.write(data); return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    public static RollbackStateCorrectionChunk decode(byte[] bytes) {
        Objects.requireNonNull(bytes);
        if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("State correction chunk byte budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != RollbackStateCorrection.VERSION) throw new IllegalArgumentException("Unsupported state correction chunk version");
            var session = new UUID(in.readLong(), in.readLong()); long publication = in.readLong();
            int index = in.readUnsignedShort(), count = in.readUnsignedShort();
            return new RollbackStateCorrectionChunk(session, publication, index, count, in.readAllBytes());
        } catch (IOException malformed) { throw new IllegalArgumentException("Truncated state correction chunk", malformed); }
    }

    /** One bounded publication at a time on the authenticated reliable server connection. */
    public static final class Assembler {
        private final Thread owner = Thread.currentThread();
        private final UUID session;
        private final int timeoutTicks, maximumBytes;
        private final String definitions;
        private long assembling;
        private byte[][] parts;
        private long publication, startedTick, lastTick;
        private int received;
        private boolean failed;
        public Assembler(UUID session, String definitions, int maximumBytes, long tick, int timeoutTicks) {
            this.definitions = Objects.requireNonNull(definitions); this.maximumBytes = maximumBytes;
            if (!definitions.matches("[0-9a-f]{64}") || maximumBytes <= RollbackStateCorrection.HEADER_BYTES || maximumBytes > RollbackStateCorrection.MAXIMUM_BYTES)
                throw new IllegalArgumentException("State correction assembly budget/schema");
            this.session = Objects.requireNonNull(session); this.lastTick = tick; this.timeoutTicks = timeoutTicks;
            if (tick < 0 || timeoutTicks < 1 || timeoutTicks > 1200) throw new IllegalArgumentException("Assembly clock bounds");
        }
        public RollbackStateCorrection receive(RollbackStateCorrectionChunk chunk, long tick) {
            poll(tick);
            if (!session.equals(chunk.session) || chunk.publication <= publication) return null;
            try {
                long minimum = (long) (chunk.count - 1) * DATA_BYTES + (chunk.index == chunk.count - 1 ? chunk.data.length : 1);
                if (minimum > maximumBytes) throw new IllegalArgumentException("State correction exceeds session budget");
                if (parts == null) { parts = new byte[chunk.count][]; startedTick = tick; assembling = chunk.publication; }
                if (chunk.publication != assembling) throw new IllegalArgumentException("Interleaved state correction publications");
                if (parts.length != chunk.count) throw new IllegalArgumentException("State correction chunk count changed");
                if (parts[chunk.index] != null) {
                    if (!Arrays.equals(parts[chunk.index], chunk.data)) throw new IllegalArgumentException("State correction chunk was rewritten");
                    return null;
                }
                parts[chunk.index] = chunk.data.clone(); received++;
                if (received < parts.length) return null;
                var bytes = new ByteArrayOutputStream();
                for (byte[] part : parts) bytes.writeBytes(part);
                var update = RollbackStateCorrection.decode(bytes.toByteArray(), maximumBytes);
                if (!session.equals(update.session()) || update.publication() != chunk.publication || !definitions.equals(update.definitions())) throw new IllegalArgumentException("State correction envelope mismatch");
                publication = chunk.publication; parts = null; received = 0;
                return update;
            } catch (RuntimeException | Error failure) { failed = true; parts = null; throw failure; }
        }
        public void poll(long tick) {
            if (Thread.currentThread() != owner) throw new IllegalStateException("State correction assembly crossed threads");
            if (failed) throw new IllegalStateException("State correction assembly failed; stop the session");
            if (tick < lastTick || parts != null && tick - startedTick > timeoutTicks) {
                failed = true; parts = null; throw new IllegalStateException("State correction assembly clock/timeout");
            }
            lastTick = tick;
        }
    }
}
