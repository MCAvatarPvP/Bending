package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.util.*;

/** Sized below Paper's plugin-message bound; assembled atomically before any simulation changes. */
public record RollbackAuthorityChunk(UUID session, long publication, int index, int count, byte[] data) {
    public static final String CHANNEL = "projectkorra:rollback_authority";
    public static final int DATA_BYTES = 24_576;
    public static final int MAXIMUM_BYTES = DATA_BYTES + 32;
    public static final int MAXIMUM_PARTS = (RollbackAuthorityCodec.MAXIMUM_BYTES + DATA_BYTES - 1) / DATA_BYTES;

    public RollbackAuthorityChunk {
        Objects.requireNonNull(session); Objects.requireNonNull(data);
        if (publication < 1 || count < 1 || count > MAXIMUM_PARTS || index < 0 || index >= count
                || data.length < 1 || data.length > DATA_BYTES || index < count - 1 && data.length != DATA_BYTES
                || (long) (count - 1) * DATA_BYTES + (index == count - 1 ? data.length : 1) > RollbackAuthorityCodec.MAXIMUM_BYTES) {
            throw new IllegalArgumentException("Authority chunk bounds");
        }
        data = data.clone();
    }
    @Override public byte[] data() { return data.clone(); }
    @Override public boolean equals(Object value) {
        return value instanceof RollbackAuthorityChunk other && session.equals(other.session) && publication == other.publication
                && index == other.index && count == other.count && Arrays.equals(data, other.data);
    }
    @Override public int hashCode() { return 31 * Objects.hash(session, publication, index, count) + Arrays.hashCode(data); }

    public static List<RollbackAuthorityChunk> split(RollbackAuthorityUpdate update) {
        byte[] encoded = RollbackAuthorityCodec.encode(update);
        int count = (encoded.length + DATA_BYTES - 1) / DATA_BYTES;
        var result = new ArrayList<RollbackAuthorityChunk>(count);
        for (int index = 0; index < count; index++) result.add(new RollbackAuthorityChunk(update.session(), update.publication(), index, count,
                Arrays.copyOfRange(encoded, index * DATA_BYTES, Math.min(encoded.length, (index + 1) * DATA_BYTES))));
        return List.copyOf(result);
    }

    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(32 + data.length); var out = new DataOutputStream(bytes);
            out.writeInt(RollbackAuthorityCodec.VERSION); out.writeLong(session.getMostSignificantBits()); out.writeLong(session.getLeastSignificantBits());
            out.writeLong(publication); out.writeShort(index); out.writeShort(count); out.write(data); return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    public static RollbackAuthorityChunk decode(byte[] bytes) {
        Objects.requireNonNull(bytes);
        if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("Authority chunk byte budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != RollbackAuthorityCodec.VERSION) throw new IllegalArgumentException("Unsupported authority chunk version");
            var session = new UUID(in.readLong(), in.readLong()); long publication = in.readLong();
            int index = in.readUnsignedShort(), count = in.readUnsignedShort();
            return new RollbackAuthorityChunk(session, publication, index, count, in.readAllBytes());
        } catch (IOException malformed) { throw new IllegalArgumentException("Truncated authority chunk", malformed); }
    }

    /** One bounded publication at a time on the authenticated reliable server connection. */
    public static final class Assembler {
        private final Thread owner = Thread.currentThread();
        private final UUID session;
        private final int timeoutTicks;
        private byte[][] parts;
        private long publication, startedTick, lastTick;
        private int received;
        private boolean failed;
        public Assembler(UUID session, long tick, int timeoutTicks) {
            this.session = Objects.requireNonNull(session); this.lastTick = tick; this.timeoutTicks = timeoutTicks;
            if (tick < 0 || timeoutTicks < 1 || timeoutTicks > 1200) throw new IllegalArgumentException("Assembly clock bounds");
        }
        public RollbackAuthorityUpdate receive(RollbackAuthorityChunk chunk, long tick) {
            poll(tick);
            if (!session.equals(chunk.session) || chunk.publication <= publication) return null;
            try {
                if (chunk.publication != Math.incrementExact(publication)) throw new IllegalArgumentException("Missing authority publication");
                if (parts == null) { parts = new byte[chunk.count][]; startedTick = tick; }
                if (parts.length != chunk.count) throw new IllegalArgumentException("Authority chunk count changed");
                if (parts[chunk.index] != null) {
                    if (!Arrays.equals(parts[chunk.index], chunk.data)) throw new IllegalArgumentException("Authority chunk was rewritten");
                    return null;
                }
                parts[chunk.index] = chunk.data.clone(); received++;
                if (received < parts.length) return null;
                var bytes = new ByteArrayOutputStream();
                for (byte[] part : parts) bytes.writeBytes(part);
                var update = RollbackAuthorityCodec.decode(bytes.toByteArray());
                if (!session.equals(update.session()) || update.publication() != chunk.publication) throw new IllegalArgumentException("Authority envelope mismatch");
                publication = chunk.publication; parts = null; received = 0;
                return update;
            } catch (RuntimeException | Error failure) { failed = true; parts = null; throw failure; }
        }
        public void poll(long tick) {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Authority assembly crossed threads");
            if (failed) throw new IllegalStateException("Authority assembly failed; stop the session");
            if (tick < lastTick || parts != null && tick - startedTick > timeoutTicks) {
                failed = true; parts = null; throw new IllegalStateException("Authority assembly clock/timeout");
            }
            lastTick = tick;
        }
    }
}
