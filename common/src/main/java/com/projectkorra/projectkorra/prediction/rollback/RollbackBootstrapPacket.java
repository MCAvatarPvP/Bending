package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.util.*;

import static com.projectkorra.projectkorra.prediction.rollback.RollbackStartPacket.Direction;
import static com.projectkorra.projectkorra.prediction.rollback.RollbackStartPacket.AbortReason;

/** Bounded plugin messages; a receipt means successful private import, not merely receiving all chunks. */
public final class RollbackBootstrapPacket {
    public static final String CLIENT_CHANNEL = "projectkorra:rollback_boot_c2s", SERVER_CHANNEL = "projectkorra:rollback_boot_s2c";
    public static final int VERSION = 1, DATA_BYTES = 24_576, MAXIMUM_BYTES = DATA_BYTES + 41, MAXIMUM_REPLY_BYTES = 69;
    private RollbackBootstrapPacket() { }

    public sealed interface Message permits Offer, Part, Ready, Cancel { UUID session(); UUID challenge(); }
    public record Offer(UUID session, UUID challenge, int startVersion, String definitions, String fingerprint, int bytes) implements Message {
        public Offer {
            ids(session, challenge); hash(definitions); hash(fingerprint);
            if (startVersion < 1 || bytes < 1 || bytes > RollbackBootstrapData.MAXIMUM_BYTES) throw invalid("offer bounds");
        }
        public int parts() { return (bytes + DATA_BYTES - 1) / DATA_BYTES; }
        public int partSize(int index) {
            if (index < 0 || index >= parts()) throw invalid("part index");
            return Math.min(DATA_BYTES, bytes - index * DATA_BYTES);
        }
    }
    public record Part(UUID session, UUID challenge, int index, byte[] data) implements Message {
        public Part {
            ids(session, challenge); Objects.requireNonNull(data);
            if (index < 0 || index >= (RollbackBootstrapData.MAXIMUM_BYTES + DATA_BYTES - 1) / DATA_BYTES
                    || data.length < 1 || data.length > DATA_BYTES) throw invalid("part bounds");
            data = data.clone();
        }
        @Override public byte[] data() { return data.clone(); }
        @Override public boolean equals(Object value) { return value instanceof Part p && session.equals(p.session) && challenge.equals(p.challenge) && index == p.index && Arrays.equals(data, p.data); }
        @Override public int hashCode() { return 31 * Objects.hash(session, challenge, index) + Arrays.hashCode(data); }
    }
    public record Ready(UUID session, UUID challenge, String fingerprint) implements Message {
        public Ready { ids(session, challenge); hash(fingerprint); }
    }
    public record Cancel(UUID session, UUID challenge, AbortReason reason) implements Message {
        public Cancel { ids(session, challenge); Objects.requireNonNull(reason); }
    }

    public static void requireDirection(Message message, Direction direction) {
        Objects.requireNonNull(direction); Objects.requireNonNull(message);
        if (!(message instanceof Cancel) && (direction == Direction.CLIENT_TO_SERVER ? !(message instanceof Ready) : message instanceof Ready)) {
            throw invalid("message direction");
        }
    }
    public static byte[] encode(Message message, Direction direction) {
        requireDirection(message, direction);
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(VERSION); out.writeByte(switch (message) { case Offer ignored -> 0; case Part ignored -> 1; case Ready ignored -> 2; case Cancel ignored -> 3; });
            uuid(out, message.session()); uuid(out, message.challenge());
            switch (message) {
                case Offer offer -> { out.writeInt(offer.startVersion); out.write(HexFormat.of().parseHex(offer.definitions)); out.write(HexFormat.of().parseHex(offer.fingerprint)); out.writeInt(offer.bytes); }
                case Part part -> { out.writeInt(part.index); out.write(part.data); }
                case Ready ready -> out.write(HexFormat.of().parseHex(ready.fingerprint));
                case Cancel cancel -> out.writeByte(cancel.reason.ordinal());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    public static Message decode(byte[] bytes, Direction direction) {
        if (bytes.length > (direction == Direction.CLIENT_TO_SERVER ? MAXIMUM_REPLY_BYTES : MAXIMUM_BYTES)) throw invalid("wire budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw invalid("version");
            int type = in.readUnsignedByte(); var session = uuid(in); var challenge = uuid(in);
            Message message = switch (type) {
                case 0 -> new Offer(session, challenge, in.readInt(), hash(in), hash(in), in.readInt());
                case 1 -> new Part(session, challenge, in.readInt(), in.readAllBytes());
                case 2 -> new Ready(session, challenge, hash(in));
                case 3 -> {
                    int reason = in.readUnsignedByte(); if (reason >= AbortReason.values().length) throw invalid("abort reason");
                    yield new Cancel(session, challenge, AbortReason.values()[reason]);
                }
                default -> throw invalid("message type");
            };
            if (in.available() != 0) throw invalid("trailing data");
            requireDirection(message, direction); return message;
        } catch (IOException malformed) { throw new IllegalArgumentException("Invalid bootstrap packet: truncated data", malformed); }
    }

    /** One announced snapshot, bounded by both the protocol and the loader's local memory budget. */
    public static final class Assembler {
        private final Thread thread = Thread.currentThread();
        private final Offer offer;
        private final long deadline;
        private long lastTick;
        private byte[][] parts;
        private int received;
        private boolean complete, failed;
        public Assembler(Offer offer, long tick, int timeoutTicks, int maximumBytes) {
            this.offer = Objects.requireNonNull(offer);
            if (tick < 0 || timeoutTicks < 1 || timeoutTicks > 1200 || maximumBytes < 1 || offer.bytes > maximumBytes) throw invalid("assembly bounds");
            deadline = Math.addExact(tick, timeoutTicks); lastTick = tick; parts = new byte[offer.parts()][];
        }
        public byte[] receive(Part part, long tick) {
            poll(tick);
            if (complete || !offer.session.equals(part.session) || !offer.challenge.equals(part.challenge)) return null;
            try {
                if (part.data.length != offer.partSize(part.index)) throw invalid("part length");
                if (parts[part.index] != null) {
                    if (!Arrays.equals(parts[part.index], part.data)) throw invalid("rewritten part");
                    return null;
                }
                parts[part.index] = part.data.clone();
                if (++received != parts.length) return null;
                byte[] data = new byte[offer.bytes];
                for (int i = 0; i < parts.length; i++) System.arraycopy(parts[i], 0, data, i * DATA_BYTES, parts[i].length);
                if (!offer.fingerprint.equals(RollbackBootstrapData.fingerprint(data))) throw invalid("snapshot fingerprint");
                complete = true; parts = null; return data;
            } catch (RuntimeException | Error failure) { failed = true; parts = null; throw failure; }
        }
        public void poll(long tick) {
            if (Thread.currentThread() != thread) throw new IllegalStateException("Bootstrap assembly crossed threads");
            if (failed) throw new IllegalStateException("Bootstrap assembly failed");
            if (tick < lastTick || !complete && tick > deadline) { failed = true; parts = null; throw new IllegalStateException("Bootstrap assembly clock/timeout"); }
            lastTick = tick;
        }
    }
    private static void ids(UUID session, UUID challenge) { Objects.requireNonNull(session); Objects.requireNonNull(challenge); }
    private static void hash(String hash) { if (hash == null || !hash.matches("[0-9a-f]{64}")) throw invalid("hash"); }
    private static String hash(DataInputStream in) throws IOException { byte[] hash = new byte[32]; in.readFully(hash); return HexFormat.of().formatHex(hash); }
    private static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static IllegalArgumentException invalid(String detail) { return new IllegalArgumentException("Invalid bootstrap packet: " + detail); }
}
