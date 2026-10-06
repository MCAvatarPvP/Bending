package com.projectkorra.projectkorra.prediction.rollback.world;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.ToIntFunction;

/** Immutable native sky/block light layers, independent of time-adjusted combined brightness. */
public final class RollbackLightSeed {
    private static final int VERSION = 1, HEADER = 28;
    private final RollbackBlockStore.Bounds bounds;
    private final byte[] layers;
    private RollbackLightSeed(RollbackBlockStore.Bounds bounds, byte[] layers) { this.bounds = bounds; this.layers = layers; }
    /** Capture in the same y/z/x order as terrain, from loader-owned reads at one boundary. */
    public static RollbackLightSeed capture(RollbackBlockStore.Bounds bounds, int maximumCells,
            ToIntFunction<RollbackBlockStore.Position> sky, ToIntFunction<RollbackBlockStore.Position> block) {
        Objects.requireNonNull(bounds); Objects.requireNonNull(sky); Objects.requireNonNull(block);
        int count = count(bounds, maximumCells); var values = new byte[count]; int index = 0;
        for (int y = bounds.minY(); y < bounds.maxY(); y++) for (int z = bounds.minZ(); z < bounds.maxZ(); z++)
            for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                var position = new RollbackBlockStore.Position(x, y, z);
                int sunlight = sky.applyAsInt(position), emitted = block.applyAsInt(position);
                if (sunlight < 0 || sunlight > 15 || emitted < 0 || emitted > 15) throw new IllegalArgumentException("Native light outside 0..15");
                values[index++] = (byte) ((sunlight << 4) | emitted);
            }
        return new RollbackLightSeed(bounds, values);
    }
    public RollbackBlockStore.Bounds bounds() { return bounds; }
    public int sky(RollbackBlockStore.Position position) { return (layers[index(position)] & 255) >>> 4; }
    public int block(RollbackBlockStore.Position position) { return layers[index(position)] & 15; }
    private int index(RollbackBlockStore.Position position) {
        if (!bounds.contains(Objects.requireNonNull(position))) throw new IllegalArgumentException("Light outside captured bounds");
        return ((position.y() - bounds.minY()) * (bounds.maxZ() - bounds.minZ()) + position.z() - bounds.minZ())
                * (bounds.maxX() - bounds.minX()) + position.x() - bounds.minX();
    }
    public byte[] encode() {
        return ByteBuffer.allocate(HEADER + layers.length).putInt(VERSION)
                .putInt(bounds.minX()).putInt(bounds.minY()).putInt(bounds.minZ())
                .putInt(bounds.maxX()).putInt(bounds.maxY()).putInt(bounds.maxZ()).put(layers).array();
    }
    public static RollbackLightSeed decode(byte[] bytes, int maximumCells) {
        Objects.requireNonNull(bytes);
        if (maximumCells < 1 || bytes.length < HEADER || (long) bytes.length > HEADER + (long) maximumCells)
            throw new IllegalArgumentException("Light payload budget/header");
        var input = ByteBuffer.wrap(bytes);
        if (input.getInt() != VERSION) throw new IllegalArgumentException("Light payload version");
        var bounds = new RollbackBlockStore.Bounds(input.getInt(), input.getInt(), input.getInt(), input.getInt(), input.getInt(), input.getInt());
        int count = count(bounds, maximumCells);
        if (input.remaining() != count) throw new IllegalArgumentException("Light payload size differs from terrain bounds");
        var values = new byte[count]; input.get(values);
        return new RollbackLightSeed(bounds, values);
    }
    private static int count(RollbackBlockStore.Bounds bounds, int maximumCells) {
        long count = ((long) bounds.maxX() - bounds.minX()) * ((long) bounds.maxY() - bounds.minY()) * ((long) bounds.maxZ() - bounds.minZ());
        if (maximumCells < 1 || count > maximumCells) throw new IllegalArgumentException("Light cell budget");
        return Math.toIntExact(count);
    }
}
