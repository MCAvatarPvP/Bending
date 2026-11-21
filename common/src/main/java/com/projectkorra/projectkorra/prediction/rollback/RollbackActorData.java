package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.entity.EntityType;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.Identity;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Private spawned actors in allocation order at a confirmed boundary. */
public record RollbackActorData(UUID session, int cursor, List<Row> actors) {
    public static final int VERSION = 1, MAXIMUM_ACTORS = 16_384, MAXIMUM_BYTES = 16 << 20;
    public record Row(Identity identity, byte[] state) {
        public Row {
            Objects.requireNonNull(identity);
            if (identity.networkId() >= 0 || identity.networkId() == Integer.MIN_VALUE || identity.type() == EntityType.PLAYER
                    || identity.name().isBlank() || identity.name().length() > 256 || state.length < 1 || state.length > (1 << 20))
                throw new IllegalArgumentException("Invalid private actor");
            state = state.clone();
        }
        @Override public byte[] state() { return state.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Row row && identity.equals(row.identity) && Arrays.equals(state, row.state);
        }
        @Override public int hashCode() { return 31 * identity.hashCode() + Arrays.hashCode(state); }
    }
    public RollbackActorData {
        Objects.requireNonNull(session);
        actors = List.copyOf(actors);
        if (cursor < 0 || actors.size() > MAXIMUM_ACTORS) throw new IllegalArgumentException("Private actor collection bounds");
        int previous = 0;
        long bytes = 28;
        for (Row actor : actors) {
            var id = actor.identity(); int sequence = -id.networkId();
            if (sequence <= previous || sequence > cursor || !id.uuid().equals(uuid(session, sequence)))
                throw new IllegalArgumentException("Private actor allocation differs");
            previous = sequence;
            bytes += 4L + 2 + modifiedUtfSize(id.name()) + 2 + id.type().name().length() + 4 + actor.state.length;
            if (bytes > MAXIMUM_BYTES) throw new IllegalArgumentException("Private actor byte budget");
        }
    }
    /** History-only sources may be absent from the active scene. A shared allocation must describe the same actor. */
    public void requireHistoryFor(RollbackActorData active) {
        if (!session.equals(active.session()) || cursor != active.cursor())
            throw new IllegalArgumentException("Damage history differs from the confirmed allocation");
        int index = 0;
        for (var historical : actors) {
            var id = historical.identity();
            while (index < active.actors.size() && active.actors.get(index).identity().networkId() > id.networkId()) index++;
            if (index < active.actors.size() && active.actors.get(index).identity().networkId() == id.networkId()
                    && !historical.equals(active.actors.get(index)))
                throw new IllegalArgumentException("Damage history differs from the active actor frame");
        }
    }
    private static int modifiedUtfSize(String value) {
        int size = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            size += c >= 1 && c <= 127 ? 1 : c <= 2047 ? 2 : 3;
        }
        return size;
    }
    static UUID uuid(UUID session, int sequence) {
        var value = ByteBuffer.allocate(24);
        value.putInt(0x504B4143).putLong(session.getMostSignificantBits()).putLong(session.getLeastSignificantBits()).putInt(sequence);
        return UUID.nameUUIDFromBytes(value.array());
    }
    public byte[] encode() {
        try {
            var buffer = new ByteArrayOutputStream(); var out = new DataOutputStream(buffer);
            out.writeInt(VERSION); out.writeLong(session.getMostSignificantBits()); out.writeLong(session.getLeastSignificantBits());
            out.writeInt(cursor); out.writeInt(actors.size());
            for (Row row : actors) {
                var id = row.identity(); out.writeInt(id.networkId()); out.writeUTF(id.name()); out.writeUTF(id.type().name());
                out.writeInt(row.state.length); out.write(row.state);
            }
            return buffer.toByteArray();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    public static RollbackActorData decode(byte[] bytes) {
        if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("Private actor byte budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw new IllegalArgumentException("Private actor version");
            var session = new UUID(in.readLong(), in.readLong()); int cursor = in.readInt(), count = in.readInt();
            if (count < 0 || count > MAXIMUM_ACTORS) throw new IllegalArgumentException("Private actor count");
            var rows = new ArrayList<Row>(count);
            for (int i = 0; i < count; i++) {
                int network = in.readInt(); String name = in.readUTF(); var type = EntityType.valueOf(in.readUTF());
                int size = in.readInt();
                if (size < 1 || size > (1 << 20) || size > in.available()) throw new IllegalArgumentException("Private actor state size");
                rows.add(new Row(new Identity(uuid(session, -network), network, name, type), in.readNBytes(size)));
            }
            if (in.available() != 0) throw new IllegalArgumentException("Trailing private actor data");
            return new RollbackActorData(session, cursor, rows);
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid private actor encoding", failure); }
    }
}
