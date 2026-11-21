package com.projectkorra.projectkorra.prediction.rollback.world;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.ToIntFunction;

/** Immutable native sky/block light layers, independent of time-adjusted combined brightness. */
public final class RollbackLightSeed {
    private static final int VERSION = 1, HEADER = 28;
    private final RollbackBlockStore.Bounds bounds;
    private final byte[] layers;
    private final Section[] sections;
    private final int width, depth;
    private final int count;
    private RollbackLightSeed(RollbackBlockStore.Bounds bounds, byte[] values) {
        this.bounds = bounds;
        width = bounds.maxX() - bounds.minX(); depth = bounds.maxZ() - bounds.minZ(); count = values.length;
        if (((bounds.minX() | bounds.minY() | bounds.minZ() | bounds.maxX() | bounds.maxY() | bounds.maxZ()) & 15) != 0) {
            layers = values; sections = null;
            return;
        }
        // Bootstrap decoding and detached terrain expansion happen before native world hydration.
        // Keep exactly one byte per cell in the same two native nibble planes the loaders consume.
        layers = null; sections = new Section[count / 4096];
        for (int y = bounds.minY(); y < bounds.maxY(); y += 16) for (int z = bounds.minZ(); z < bounds.maxZ(); z += 16)
            for (int x = bounds.minX(); x < bounds.maxX(); x += 16) sections[sectionIndex(x, y, z)] = pack(values, x, y, z);
    }
    private int sectionIndex(int x, int y, int z) {
        return (((y - bounds.minY()) >> 4) * (depth >> 4) + ((z - bounds.minZ()) >> 4)) * (width >> 4) + ((x - bounds.minX()) >> 4);
    }
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
    public int sky(RollbackBlockStore.Position position) { return level(position, true); }
    public int block(RollbackBlockStore.Position position) { return level(position, false); }
    private int level(RollbackBlockStore.Position p, boolean sky) {
        if (!bounds.contains(Objects.requireNonNull(p))) throw new IllegalArgumentException("Light outside captured bounds");
        if (layers != null) {
            int packed = layers[((p.y() - bounds.minY()) * depth + p.z() - bounds.minZ()) * width + p.x() - bounds.minX()] & 255;
            return sky ? packed >>> 4 : packed & 15;
        }
        var section = sections[sectionIndex(p.x(), p.y(), p.z())];
        int nibble = ((p.y() & 15) << 8) | ((p.z() & 15) << 4) | (p.x() & 15);
        return ((sky ? section.sky : section.block)[nibble >> 1] >>> ((nibble & 1) << 2)) & 15;
    }
    /** Owned native section arrays in low/even-X, high/odd-X nibble order. */
    public record Section(byte[] sky, byte[] block, boolean hasSkyLight) { }
    public Section section(int x, int y, int z) {
        if (((x | y | z) & 15) != 0 || x < bounds.minX() || y < bounds.minY() || z < bounds.minZ()
                || (long) x + 16 > bounds.maxX() || (long) y + 16 > bounds.maxY() || (long) z + 16 > bounds.maxZ())
            throw new IllegalArgumentException("Light section outside captured bounds");
        if (sections == null) return pack(layers, x, y, z);
        var saved = sections[sectionIndex(x, y, z)];
        return new Section(saved.sky.clone(), saved.block.clone(), saved.hasSkyLight);
    }
    private Section pack(byte[] values, int x, int y, int z) {
        byte[] sky = new byte[2048], block = new byte[2048];
        int skyMask = 0, target = 0;
        for (int dy = 0; dy < 16; dy++) for (int dz = 0; dz < 16; dz++) {
            int row = ((y - bounds.minY() + dy) * depth + z - bounds.minZ() + dz) * width + x - bounds.minX();
            for (int dx = 0; dx < 16; dx += 2) {
                int even = values[row + dx] & 255, odd = values[row + dx + 1] & 255;
                sky[target] = (byte) ((even >>> 4) | (odd & 240));
                block[target++] = (byte) ((even & 15) | ((odd & 15) << 4));
                skyMask |= even | odd;
            }
        }
        return new Section(sky, block, (skyMask & 240) != 0);
    }
    public byte[] encode() {
        var output = ByteBuffer.allocate(HEADER + count).putInt(VERSION)
                .putInt(bounds.minX()).putInt(bounds.minY()).putInt(bounds.minZ())
                .putInt(bounds.maxX()).putInt(bounds.maxY()).putInt(bounds.maxZ());
        if (layers != null) return output.put(layers).array();
        byte[] bytes = output.array();
        for (int y = bounds.minY(); y < bounds.maxY(); y++) for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
            int row = HEADER + ((y - bounds.minY()) * depth + z - bounds.minZ()) * width;
            int first = ((y & 15) << 7) | ((z & 15) << 3);
            for (int x = bounds.minX(); x < bounds.maxX(); x += 16) {
                var section = sections[sectionIndex(x, y, z)];
                for (int i = first; i < first + 8; i++) {
                    int sky = section.sky[i] & 255, block = section.block[i] & 255;
                    bytes[row++] = (byte) ((sky << 4) | (block & 15));
                    bytes[row++] = (byte) ((sky & 240) | (block >>> 4));
                }
            }
        }
        return bytes;
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
