package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.Pose;
import java.io.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Native entity save data accompanied by transient movement and RNG state common to both mappings. */
public record RollbackActorFrame(int age, Pose last, boolean horizontalCollision, boolean verticalCollision,
                                 boolean groundCollision, boolean impulse, boolean sendVelocity,
                                 RollbackRandom.State random, byte[] nbt, Map<UUID, Integer> cloudVictims) {
    private static final int BASE_VERSION = 1, CLOUD_VERSION = 2;
    private static final int FRAME_LIMIT = 1 << 20, MAXIMUM_NBT = FRAME_LIMIT - 256;
    private static final int BASE_HEADER = 61, VICTIM_BYTES = 2 * Long.BYTES + Integer.BYTES;
    private static final int MAXIMUM_VICTIMS = RollbackActorData.MAXIMUM_ACTORS + RollbackRosterData.MAXIMUM_PLAYERS;

    public RollbackActorFrame(int age, Pose last, boolean horizontalCollision, boolean verticalCollision,
                              boolean groundCollision, boolean impulse, boolean sendVelocity,
                              RollbackRandom.State random, byte[] nbt) {
        this(age, last, horizontalCollision, verticalCollision, groundCollision, impulse, sendVelocity, random, nbt, Map.of());
    }
    public RollbackActorFrame {
        Objects.requireNonNull(last); Objects.requireNonNull(random);
        if (nbt.length < 1 || nbt.length > MAXIMUM_NBT) throw new IllegalArgumentException("Actor NBT size");
        if (cloudVictims.isEmpty()) cloudVictims = Map.of();
        else {
            if (cloudVictims.size() > MAXIMUM_VICTIMS
                    || BASE_HEADER + nbt.length + Integer.BYTES + (long) VICTIM_BYTES * cloudVictims.size() > FRAME_LIMIT)
                throw new IllegalArgumentException("Cloud victim table size");
            var sorted = new TreeMap<UUID, Integer>();
            cloudVictims.forEach((id, nextAge) -> sorted.put(id, Objects.requireNonNull(nextAge)));
            cloudVictims = Collections.unmodifiableMap(sorted);
        }
        nbt = nbt.clone();
    }
    @Override public byte[] nbt() { return nbt.clone(); }
    public byte[] encode() {
        try {
            var buffer = new ByteArrayOutputStream(); var out = new DataOutputStream(buffer);
            out.writeInt(cloudVictims.isEmpty() ? BASE_VERSION : CLOUD_VERSION); out.writeInt(age);
            out.writeDouble(last.x()); out.writeDouble(last.y()); out.writeDouble(last.z());
            out.writeFloat(last.yaw()); out.writeFloat(last.pitch());
            int flags = (horizontalCollision ? 1 : 0) | (verticalCollision ? 2 : 0) | (groundCollision ? 4 : 0)
                    | (impulse ? 8 : 0) | (sendVelocity ? 16 : 0) | (random.hasGaussian() ? 32 : 0);
            out.writeByte(flags); out.writeLong(random.bits()); out.writeDouble(random.gaussian());
            out.writeInt(nbt.length); out.write(nbt);
            if (!cloudVictims.isEmpty()) {
                out.writeInt(cloudVictims.size());
                for (var victim : cloudVictims.entrySet()) {
                    out.writeLong(victim.getKey().getMostSignificantBits());
                    out.writeLong(victim.getKey().getLeastSignificantBits());
                    out.writeInt(victim.getValue());
                }
            }
            return buffer.toByteArray();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    public static RollbackActorFrame decode(byte[] bytes) {
        if (bytes.length > FRAME_LIMIT) throw new IllegalArgumentException("Actor frame size");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            int version = in.readInt();
            if (version != BASE_VERSION && version != CLOUD_VERSION) throw new IllegalArgumentException("Actor frame version");
            int age = in.readInt(); var pos = new Pose(in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat());
            int flags = in.readUnsignedByte();
            if (flags >= 64) throw new IllegalArgumentException("Actor frame flags");
            var random = new RollbackRandom.State(in.readLong(), (flags & 32) != 0, in.readDouble());
            int size = in.readInt();
            if (size < 1 || size > MAXIMUM_NBT || size > in.available()
                    || (version == BASE_VERSION && size != in.available())) throw new IllegalArgumentException("Actor NBT size");
            byte[] nbt = in.readNBytes(size);
            Map<UUID, Integer> victims = Map.of();
            if (version == CLOUD_VERSION) {
                int count = in.readInt();
                if (count < 1 || count > MAXIMUM_VICTIMS || in.available() != count * VICTIM_BYTES)
                    throw new IllegalArgumentException("Cloud victim table size");
                victims = new HashMap<>();
                for (int i = 0; i < count; i++) {
                    var id = new UUID(in.readLong(), in.readLong());
                    if (victims.put(id, in.readInt()) != null) throw new IllegalArgumentException("Duplicate cloud victim");
                }
            }
            return new RollbackActorFrame(age, pos, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0,
                    (flags & 8) != 0, (flags & 16) != 0, random, nbt, victims);
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid private actor frame", failure); }
    }
}
