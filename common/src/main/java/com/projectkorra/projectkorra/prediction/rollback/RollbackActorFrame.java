package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.Pose;
import java.io.*;
import java.util.Objects;

/** Native entity save data accompanied by transient movement and RNG state common to both mappings. */
public record RollbackActorFrame(int age, Pose last, boolean horizontalCollision, boolean verticalCollision,
                                 boolean groundCollision, boolean impulse, boolean sendVelocity,
                                 RollbackRandom.State random, byte[] nbt) {
    private static final int VERSION = 1, MAXIMUM_BYTES = (1 << 20) - 256;
    public RollbackActorFrame {
        Objects.requireNonNull(last); Objects.requireNonNull(random);
        if (nbt.length < 1 || nbt.length > MAXIMUM_BYTES) throw new IllegalArgumentException("Actor NBT size");
        nbt = nbt.clone();
    }
    @Override public byte[] nbt() { return nbt.clone(); }
    public byte[] encode() {
        try {
            var buffer = new ByteArrayOutputStream(); var out = new DataOutputStream(buffer);
            out.writeInt(VERSION); out.writeInt(age);
            out.writeDouble(last.x()); out.writeDouble(last.y()); out.writeDouble(last.z());
            out.writeFloat(last.yaw()); out.writeFloat(last.pitch());
            int flags = (horizontalCollision ? 1 : 0) | (verticalCollision ? 2 : 0) | (groundCollision ? 4 : 0)
                    | (impulse ? 8 : 0) | (sendVelocity ? 16 : 0) | (random.hasGaussian() ? 32 : 0);
            out.writeByte(flags); out.writeLong(random.bits()); out.writeDouble(random.gaussian());
            out.writeInt(nbt.length); out.write(nbt); return buffer.toByteArray();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    public static RollbackActorFrame decode(byte[] bytes) {
        if (bytes.length > 1 << 20) throw new IllegalArgumentException("Actor frame size");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw new IllegalArgumentException("Actor frame version");
            int age = in.readInt(); var pos = new Pose(in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat());
            int flags = in.readUnsignedByte();
            if (flags >= 64) throw new IllegalArgumentException("Actor frame flags");
            var random = new RollbackRandom.State(in.readLong(), (flags & 32) != 0, in.readDouble());
            int size = in.readInt();
            if (size != in.available() || size < 1 || size > MAXIMUM_BYTES) throw new IllegalArgumentException("Actor NBT size");
            return new RollbackActorFrame(age, pos, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0,
                    (flags & 8) != 0, (flags & 16) != 0, random, in.readNBytes(size));
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid private actor frame", failure); }
    }
}
