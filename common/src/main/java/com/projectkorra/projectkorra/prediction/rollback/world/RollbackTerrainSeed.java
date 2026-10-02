package com.projectkorra.projectkorra.prediction.rollback.world;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.block.data.Snowable;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Fire;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Snow;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;

/** Complete immutable region, stored as palette indices instead of a coordinate map per cell. */
public final class RollbackTerrainSeed implements RollbackBlockStore.Seed {
    private final Bounds bounds;
    private final List<Cell> palette;
    private final int[] cells;
    private RollbackTerrainSeed(Bounds bounds, List<Cell> palette, int[] cells) {
        this.bounds = bounds; this.palette = List.copyOf(palette); this.cells = cells;
    }
    @Override public Bounds bounds() { return bounds; }
    public int paletteSize() { return palette.size(); }
    public int cellCount() { return cells.length; }
    public List<Cell> palette() { return palette; }
    /** Immutable linear layout in Y/Z/X order; exposes no writable index array. */
    public int paletteIndex(int cell) { return cells[cell]; }
    @Override public Cell cell(Position position) {
        if (!bounds.contains(position)) throw new IllegalStateException("Terrain was not captured at " + position);
        int index = ((position.y() - bounds.minY()) * (bounds.maxZ() - bounds.minZ()) + position.z() - bounds.minZ())
                * (bounds.maxX() - bounds.minX()) + position.x() - bounds.minX();
        return palette.get(cells[index]);
    }

    /** Append in Y, Z, X order (X changes fastest). Finish rejects a partially captured region. */
    public static final class Builder {
        private final Bounds bounds;
        private final int[] cells;
        private final Map<Key, Integer> entries = new HashMap<>();
        private final List<Cell> palette = new ArrayList<>();
        private final long maximumBytes;
        private long bytes;
        private int next;
        private boolean finished;
        public Builder(Bounds bounds, long maximumBytes) {
            this.bounds = Objects.requireNonNull(bounds, "bounds");
            long count = ((long) bounds.maxX() - bounds.minX()) * (bounds.maxY() - bounds.minY()) * (bounds.maxZ() - bounds.minZ());
            bytes = Math.multiplyExact(count, Integer.BYTES);
            if (maximumBytes < bytes || maximumBytes > 1_073_741_824L) throw new IllegalArgumentException("Terrain storage budget");
            this.maximumBytes = maximumBytes; cells = new int[Math.toIntExact(count)];
        }
        public void append(Cell cell) { append(cell, 1); }
        public void append(Cell cell, int repetitions) {
            if (finished || repetitions < 1 || repetitions > cells.length - next) throw new IllegalStateException("Terrain run exceeds remaining capture");
            var data = Objects.requireNonNull(cell, "cell").data();
            if (data.getExactState() == null) throw new IllegalArgumentException("Captured terrain requires exact block states");
            var key = new Key(data.getMaterial(), data.getExactState(), Properties.of(data), cell.blockEntity(), cell.biome(), cell.biomeKey(), cell.noiseBiomeKey(), cell.light(), cell.temperature(), cell.humidity());
            Integer index = entries.get(key);
            if (index == null) {
                long cost = 128L + key.exact.getBytes(StandardCharsets.UTF_8).length + key.biomeKey.getBytes(StandardCharsets.UTF_8).length + key.noiseBiomeKey.getBytes(StandardCharsets.UTF_8).length
                        + (key.tile == null ? 0 : key.tile.length);
                if (cost > maximumBytes - bytes) throw new IllegalStateException("Terrain palette exceeds storage budget");
                bytes += cost; index = palette.size(); palette.add(cell); entries.put(key, index);
            }
            Arrays.fill(cells, next, next + repetitions, index); next += repetitions;
        }
        public RollbackTerrainSeed finish() {
            if (finished || next != cells.length) throw new IllegalStateException("Terrain capture is incomplete or already finished");
            finished = true; entries.clear();
            return new RollbackTerrainSeed(bounds, palette, cells);
        }
    }

    /** Common facades produced by the native capture adapters; edits can override the exact base. */
    private record Properties(Class<?> type, int level, boolean waterlogged, boolean snowy, int layers, Set<BlockFace> faces) {
        static Properties of(BlockData data) {
            Class<?> type = data.getClass();
            if (type != BlockData.class && type != Levelled.class && type != Snowable.class && type != Snow.class && type != Fire.class) {
                throw new IllegalArgumentException("Terrain capture requires a detached native block-data facade: " + type.getName());
            }
            return new Properties(type, data instanceof Levelled value ? value.getLevel() : 0,
                    data instanceof Levelled value && value.isWaterlogged(), data instanceof Snowable value && value.isSnowy(),
                    data instanceof Snow value ? value.getLayers() : 0, data instanceof Fire value ? value.getFaces() : Set.of());
        }
    }
    private record Key(Material material, String exact, Properties properties, byte[] tile, Biome biome, String biomeKey, String noiseBiomeKey, byte light, double temperature, double humidity) {
        @Override public boolean equals(Object value) {
            return value instanceof Key other && material == other.material && exact.equals(other.exact) && properties.equals(other.properties) && Arrays.equals(tile, other.tile)
                    && biome == other.biome && biomeKey.equals(other.biomeKey) && noiseBiomeKey.equals(other.noiseBiomeKey) && light == other.light
                    && Double.doubleToLongBits(temperature) == Double.doubleToLongBits(other.temperature)
                    && Double.doubleToLongBits(humidity) == Double.doubleToLongBits(other.humidity);
        }
        @Override public int hashCode() { return Objects.hash(material, exact, properties, Arrays.hashCode(tile), biome, biomeKey, noiseBiomeKey, light, temperature, humidity); }
    }
}
