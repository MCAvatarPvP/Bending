package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.util.*;

/** Bounded server-to-client input/revision encoding, never a client hit claim or native object graph. */
public final class RollbackAuthorityCodec {
    public static final int VERSION = 3;
    public static final int MAXIMUM_BYTES = 1_048_576;
    private RollbackAuthorityCodec() { }

    public static byte[] encode(RollbackAuthorityUpdate update) {
        Objects.requireNonNull(update);
        var roster = new TreeSet<>(update.receivedTicks().keySet());
        long size = 63 + roster.size() * 18L;
        for (var receipts : update.receivedTicks().values()) size += receipts.size() * 8L;
        for (var frame : update.frames()) for (var input : frame.values()) size += 19L + input.actions().size() * 26L;
        if (size > MAXIMUM_BYTES) throw new IllegalArgumentException("Authority update exceeds byte budget");
        try {
            var bytes = new ByteArrayOutputStream((int) size); var out = new DataOutputStream(bytes);
            out.writeInt(VERSION); uuid(out, update.session());
            out.writeLong(update.publication()); out.writeLong(update.revision()); out.writeLong(update.headTick());
            out.writeLong(update.finalizedTick()); out.writeLong(update.firstTick());
            out.writeByte(roster.size()); out.writeShort(update.frames().size());
            for (var player : roster) {
                uuid(out, player);
                var receipts = update.receivedTicks().get(player); out.writeShort(receipts.size());
                for (long tick : receipts) out.writeLong(tick);
            }
            for (var frame : update.frames()) for (var player : roster) writeInput(out, frame.get(player));
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    public static RollbackAuthorityUpdate decode(byte[] bytes) {
        Objects.requireNonNull(bytes);
        if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("Authority update exceeds byte budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw new IllegalArgumentException("Unsupported authority version");
            UUID session = uuid(in); long publication = in.readLong(), revision = in.readLong(), head = in.readLong();
            long finalized = in.readLong(), first = in.readLong();
            int count = in.readUnsignedByte(), frameCount = in.readUnsignedShort();
            if (count < 1 || count > 128 || frameCount < 1 || frameCount > RollbackAuthorityUpdate.MAXIMUM_FRAMES
                    || publication < 1 || revision < 0 || first < 1 || head < first || head == Long.MAX_VALUE
                    || finalized < 0 || finalized > head || head - first != frameCount - 1) {
                throw new IllegalArgumentException("Authority header bounds");
            }
            var received = new TreeMap<UUID, List<Long>>();
            UUID previous = null;
            for (int index = 0; index < count; index++) {
                UUID player = uuid(in);
                if (previous != null && previous.compareTo(player) >= 0) throw new IllegalArgumentException("Authority roster order");
                previous = player;
                int length = in.readUnsignedShort();
                if (length > RollbackAuthorityUpdate.MAXIMUM_RECEIPTS) throw new IllegalArgumentException("Authority receipt budget");
                var ticks = new ArrayList<Long>(length);
                for (int entry = 0; entry < length; entry++) ticks.add(in.readLong());
                received.put(player, ticks);
            }
            // Every input needs at least nineteen bytes, before allocating frame maps.
            if ((long) frameCount * count * 19 > in.available()) throw new IllegalArgumentException("Truncated authority frames");
            var frames = new ArrayList<Map<UUID, RollbackPlayerInput>>(frameCount);
            for (int tick = 0; tick < frameCount; tick++) {
                var frame = new TreeMap<UUID, RollbackPlayerInput>();
                for (var player : received.keySet()) frame.put(player, readInput(in));
                frames.add(frame);
            }
            if (in.available() != 0) throw new IllegalArgumentException("Trailing authority data");
            return new RollbackAuthorityUpdate(session, publication, revision, head, finalized, first, frames, received);
        } catch (IOException malformed) { throw new IllegalArgumentException("Truncated authority update", malformed); }
    }

    private static void writeInput(DataOutputStream out, RollbackPlayerInput input) throws IOException {
        var movement = input.movement();
        out.writeFloat(movement.strafe()); out.writeFloat(movement.forward()); out.writeBoolean(movement.jump());
        out.writeFloat(movement.yaw()); out.writeFloat(movement.pitch()); out.writeBoolean(input.sprinting());
        out.writeByte(input.actions().size());
        for (var edge : input.actions()) {
            var action = edge.action();
            out.writeLong(action.sequence()); out.writeLong(action.seed()); out.writeByte(action.kind().ordinal());
            out.writeByte(action.slot()); out.writeFloat(edge.yaw()); out.writeFloat(edge.pitch());
        }
    }
    private static RollbackPlayerInput readInput(DataInputStream in) throws IOException {
        var movement = new RollbackMovementInput(in.readFloat(), in.readFloat(), bool(in), in.readFloat(), in.readFloat());
        boolean sprinting = bool(in); int count = in.readUnsignedByte();
        if (count > RollbackPlayerInput.MAXIMUM_ACTIONS) throw new IllegalArgumentException("Authority action budget");
        var actions = new ArrayList<RollbackPlayerInput.Edge>(count); var kinds = RollbackInputActions.Kind.values();
        for (int index = 0; index < count; index++) {
            long sequence = in.readLong(), seed = in.readLong(); int kind = in.readUnsignedByte(), slot = in.readByte();
            if (kind >= kinds.length) throw new IllegalArgumentException("Unknown authority action");
            actions.add(new RollbackPlayerInput.Edge(new RollbackInputActions.Action(sequence, seed, kinds[kind], slot), in.readFloat(), in.readFloat()));
        }
        return new RollbackPlayerInput(movement, sprinting, actions);
    }
    private static boolean bool(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if (value > 1) throw new IllegalArgumentException("Invalid authority boolean");
        return value != 0;
    }
    private static void uuid(DataOutputStream out, UUID value) throws IOException { out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
}
