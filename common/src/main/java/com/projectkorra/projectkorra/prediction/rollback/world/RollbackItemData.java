package com.projectkorra.projectkorra.prediction.rollback.world;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Detached native item/component data, encoded with the negotiated Minecraft version's
 * ItemStack codec and unnamed binary NBT (type byte, then payload, without a root name).
 * An empty byte array denotes an empty slot. This is not a projection through ItemMeta;
 * registry components unknown to the common API are retained by the native codec.
 * Native transient animation/owner state, if used by simulation, must be captured separately.
 */
public final class RollbackItemData {
    public static final int MAXIMUM_BYTES = 1_048_576;
    public static final long MAXIMUM_DECODE_ALLOCATION = 4_194_304;
    public static final int MAXIMUM_NBT_DEPTH = 64;
    public static final RollbackItemData EMPTY = new RollbackItemData(new byte[0]);
    private final byte[] bytes;

    public RollbackItemData(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("Item data exceeds rollback budget");
        this.bytes = bytes.clone();
    }
    public boolean isEmpty() { return bytes.length == 0; }
    public int size() { return bytes.length; }
    public byte[] bytes() { return bytes.clone(); }
    @Override public boolean equals(Object other) { return other instanceof RollbackItemData data && Arrays.equals(bytes, data.bytes); }
    @Override public int hashCode() { return Arrays.hashCode(bytes); }

    /** Stops native serialization before it can grow an unbounded output buffer. */
    public static final class Output extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) { requireCapacity(1); super.write(value); }
        @Override public synchronized void write(byte[] values, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, values.length);
            requireCapacity(length);
            super.write(values, offset, length);
        }
        private void requireCapacity(int added) {
            if (added > MAXIMUM_BYTES - count) throw new IllegalArgumentException("Item data exceeds rollback budget");
        }
        public RollbackItemData snapshot() { return new RollbackItemData(toByteArray()); }
    }
}
