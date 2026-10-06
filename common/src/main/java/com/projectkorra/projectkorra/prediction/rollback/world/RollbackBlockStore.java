package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.BlockState;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded logical terrain behind the existing Block API. The immutable seed is captured
 * before simulation; no read falls through to a live world. Native geometry and block
 * physics adapters must query this store, including neighboring provisional blocks.
 * No ability names, projectile types or defence-specific rules occur here.
 */
public final class RollbackBlockStore implements RollbackStateCell<RollbackBlockStore.State> {
    public record Position(int x, int y, int z) {
        public Position offset(int dx, int dy, int dz) {
            return new Position(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
        }
    }

    /** Inclusive minimum, exclusive maximum. Uncaptured coordinates abort instead of reading future terrain. */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public Bounds {
            if (maxX <= minX || maxY <= minY || maxZ <= minZ) throw new IllegalArgumentException("Empty terrain bounds");
            final long volume = Math.multiplyExact(Math.multiplyExact((long) maxX - minX, (long) maxY - minY), (long) maxZ - minZ);
            if (volume > 16_777_216L) throw new IllegalArgumentException("Terrain volume exceeds budget");
        }
        public boolean contains(Position position) {
            return position.x >= minX && position.x < maxX && position.y >= minY && position.y < maxY
                    && position.z >= minZ && position.z < maxZ;
        }
    }

    /** Immutable shape box, relative to a block coordinate. Shapes may extend beyond a unit cube. */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Box {
            if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                    || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                    || maxX < minX || maxY < minY || maxZ < minZ) throw new IllegalArgumentException("Invalid collision box");
        }
        public BoundingBox at(Position position) {
            return new BoundingBox(new Vector(position.x + minX, position.y + minY, position.z + minZ),
                    new Vector(position.x + maxX, position.y + maxY, position.z + maxZ));
        }
    }

    public record Geometry(boolean solid, boolean liquid, boolean passable, boolean blockEntity,
                           Box bounds, List<Box> collision, List<Box> fluid) {
        public Geometry {
            collision = List.copyOf(collision);
            fluid = List.copyOf(fluid);
        }
    }

    /**
     * Pure simulation adapter for native block behavior, supplied by the platform. Implementations
     * must inspect the provided logical blocks rather than live geometry. Updates may recursively
     * mutate this store; a bounded mutation budget prevents unbounded neighbor-update chains.
     */
    public interface Rules {
        Geometry geometry(RollbackBlockStore terrain, Position position, BlockData data);
        byte legacyData(RollbackBlockStore terrain, Position position, BlockData data);
        void physics(RollbackBlockStore terrain, Position changed);
        /** Synchronous derived-state update, including writes which suppress block physics. */
        default void changed(RollbackBlockStore terrain, Position position) { }
        /** Platforms with mutable lighting override the immutable capture value. */
        default byte light(RollbackBlockStore terrain, Position position) { return terrain.cell(position).light(); }
        Collection<ItemStack> drops(RollbackBlockStore terrain, Position position, ItemStack tool);
        boolean breakNaturally(RollbackBlockStore terrain, Position position, ItemStack tool);
    }

    /** Detached cell value. Block-entity bytes are opaque native snapshot data, never live handles. */
    public static final class Cell {
        private static final java.util.regex.Pattern BIOME_KEY = java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
        private final BlockData data;
        private final byte[] blockEntity;
        private final Biome biome;
        private final String biomeKey;
        private final String noiseBiomeKey;
        private final byte light;
        private final double temperature, humidity;

        public Cell(BlockData data, byte[] blockEntity, Biome biome, byte light, double temperature, double humidity) {
            this(data, blockEntity, biome, "minecraft:" + Objects.requireNonNull(biome, "biome").name().toLowerCase(java.util.Locale.ROOT), light, temperature, humidity);
        }

        public Cell(BlockData data, byte[] blockEntity, Biome biome, String biomeKey, byte light, double temperature, double humidity) {
            this(data, blockEntity, biome, biomeKey, biomeKey, light, temperature, humidity);
        }

        public Cell(BlockData data, byte[] blockEntity, Biome biome, String biomeKey, String noiseBiomeKey, byte light, double temperature, double humidity) {
            this.data = copyData(data);
            this.blockEntity = blockEntity == null ? null : blockEntity.clone();
            this.biome = Objects.requireNonNull(biome, "biome");
            this.biomeKey = Objects.requireNonNull(biomeKey, "native biome key");
            this.noiseBiomeKey = Objects.requireNonNull(noiseBiomeKey, "native noise biome key");
            if (!BIOME_KEY.matcher(biomeKey).matches() || !BIOME_KEY.matcher(noiseBiomeKey).matches()) throw new IllegalArgumentException("Invalid native biome key");
            if (light < 0 || light > 15 || !Double.isFinite(temperature) || !Double.isFinite(humidity)) {
                throw new IllegalArgumentException("Invalid cell environment");
            }
            this.light = light;
            this.temperature = temperature;
            this.humidity = humidity;
        }
        public BlockData data() { return copyData(data); }
        public byte[] blockEntity() { return blockEntity == null ? null : blockEntity.clone(); }
        public boolean hasBlockEntity() { return blockEntity != null; }
        public Biome biome() { return biome; }
        public String biomeKey() { return biomeKey; }
        public String noiseBiomeKey() { return noiseBiomeKey; }
        public byte light() { return light; }
        public double temperature() { return temperature; }
        public double humidity() { return humidity; }

        private Cell withData(BlockData replacement, boolean hasBlockEntity) {
            byte[] tile = hasBlockEntity ? (data.getMaterial() == replacement.getMaterial()
                    && blockEntity != null ? blockEntity : new byte[0]) : null;
            return new Cell(replacement, tile, biome, biomeKey, noiseBiomeKey, light, temperature, humidity);
        }
    }

    /** One final cell value per modified coordinate, in deterministic first-mutation order. */
    public record Change(Position position, Cell value) {}

    public static final class State {
        private final RollbackBlockStore owner;
        private final Map<Position, Cell> overlay;
        private final List<Position> dirty;
        private State(RollbackBlockStore owner, Map<Position, Cell> overlay, Collection<Position> dirty) {
            this.owner = owner;
            this.overlay = Map.copyOf(overlay);
            this.dirty = List.copyOf(dirty);
        }
    }

    private final Thread owner = Thread.currentThread();
    private final World world;
    private final Bounds bounds;
    public sealed interface Seed permits SparseSeed, RollbackTerrainSeed {
        Bounds bounds();
        Cell cell(Position position);
    }
    private record SparseSeed(Bounds bounds, Map<Position, Cell> cells, Cell empty) implements Seed {
        SparseSeed {
            Objects.requireNonNull(bounds, "bounds"); cells = Map.copyOf(cells); Objects.requireNonNull(empty, "empty");
            for (var position : cells.keySet()) if (!bounds.contains(position)) throw new IllegalArgumentException("Seed outside captured bounds");
        }
        @Override public Cell cell(Position position) { return cells.getOrDefault(position, empty); }
    }
    public static Seed sparseSeed(Bounds bounds, Map<Position, Cell> cells, Cell empty) { return new SparseSeed(bounds, cells, empty); }
    private final Seed seed;
    private final Rules rules;
    private final int maximumMutations;
    // Views contain only a fixed coordinate and this store. They are stable across restores;
    // their cache is bounded by the captured volume and has no gameplay state to rewind.
    private final Map<Position, View> views = new HashMap<>();
    private final Map<Position, Cell> overlay = new LinkedHashMap<>();
    private final LinkedHashSet<Position> dirty = new LinkedHashSet<>();
    private int mutationDepth;
    private int mutations;

    /** Missing seed entries denote the supplied captured default cell, never an uncaptured live block. */
    public RollbackBlockStore(World world, Bounds bounds, Map<Position, Cell> seed, Cell empty, Rules rules,
                              int maximumMutations) {
        this(world, new SparseSeed(bounds, seed, empty), rules, maximumMutations);
    }

    public RollbackBlockStore(World world, Seed seed, Rules rules, int maximumMutations) {
        this.world = Objects.requireNonNull(world, "world");
        this.seed = Objects.requireNonNull(seed, "seed");
        this.bounds = seed.bounds();
        this.rules = Objects.requireNonNull(rules, "rules");
        if (maximumMutations < 1) throw new IllegalArgumentException("Mutation budget");
        this.maximumMutations = maximumMutations;
    }

    public Bounds bounds() { return bounds; }
    public World world() { checkThread(); return world; }
    public Cell cell(Position position) {
        checkThread();
        requireInside(position);
        Cell changed = overlay.get(position);
        return changed == null ? seed.cell(position) : changed;
    }
    public Block block(int x, int y, int z) {
        checkThread();
        Position position = new Position(x, y, z);
        requireInside(position);
        return views.computeIfAbsent(position, View::new);
    }
    public Geometry geometry(Position position) {
        return Objects.requireNonNull(rules.geometry(this, position, cell(position).data()), "geometry");
    }

    /** Used for both simulation mutation and authenticated recorded external-world changes. */
    public void replace(Position position, Cell value, boolean physics) {
        checkThread();
        requireInside(position);
        Objects.requireNonNull(value, "value");
        if (mutationDepth == 0) mutations = 0;
        if (++mutations > maximumMutations) throw new IllegalStateException("Block physics mutation budget exceeded");
        mutationDepth++;
        try {
            overlay.put(position, value);
            dirty.add(position);
            rules.changed(this, position);
            if (physics) rules.physics(this, position);
        } finally { mutationDepth--; }
    }

    public List<Change> drainChanges() {
        checkThread();
        if (mutationDepth != 0) throw new IllegalStateException("Block update is still running");
        List<Change> result = new ArrayList<>(dirty.size());
        for (Position position : dirty) result.add(new Change(position, cell(position)));
        dirty.clear();
        return List.copyOf(result);
    }

    @Override public State captureRollbackState() {
        checkThread();
        if (mutationDepth != 0) throw new IllegalStateException("Cannot checkpoint block physics mid-update");
        return new State(this, overlay, dirty);
    }
    @Override public void restoreRollbackState(State state) {
        checkThread();
        if (mutationDepth != 0) throw new IllegalStateException("Cannot restore block physics mid-update");
        if (state.owner != this) throw new IllegalArgumentException("Terrain snapshot belongs to another world");
        overlay.clear();
        overlay.putAll(state.overlay);
        dirty.clear();
        dirty.addAll(state.dirty);
        mutations = 0;
    }

    @Override public Collection<?> rollbackReferences() {
        checkThread();
        return rules instanceof RollbackStateCell<?> ? List.of(rules) : List.of();
    }

    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Logical terrain crossed threads");
    }
    private void requireInside(Position position) {
        if (!bounds.contains(position)) throw new IllegalStateException("Terrain was not captured at " + position);
    }
    static BlockData copyData(BlockData data) {
        Objects.requireNonNull(data, "data");
        BlockData copy = data.clone();
        if (copy == data) throw new IllegalArgumentException("BlockData clone must be detached");
        // Some legacy typed clones preserve their mutable properties but omit the base exact-state string.
        copy.setExactState(data.getExactState());
        return copy;
    }

    /** Only this store's owned block view is projected; arbitrary addon block subclasses are untouched. */
    public static RollbackBlockReference portableReference(Object value) {
        return value instanceof RollbackBlockStore.View view ? view.reference() : null;
    }

    private final class View extends Block implements RollbackStateCell<Void>, com.projectkorra.projectkorra.platform.mc.block.BlockValue, RollbackBlockSnapshot.Target {
        private RollbackBlockReference reference() {
            checkThread(); return new RollbackBlockReference(world, position.x, position.y, position.z);
        }
        @Override public boolean equals(Object other) { return com.projectkorra.projectkorra.platform.mc.block.BlockValue.same(this, other); }
        @Override public int hashCode() { return com.projectkorra.projectkorra.platform.mc.block.BlockValue.hash(this); }
        private final Position position;
        private View(Position position) { this.position = position; }
        @Override public World getWorld() { return world; }
        @Override public Location getLocation() { return new Location(world, position.x, position.y, position.z); }
        @Override public int getX() { return position.x; }
        @Override public int getY() { return position.y; }
        @Override public int getZ() { return position.z; }
        @Override public Material getType() { return cell(position).data.getMaterial(); }
        @Override public boolean isEmpty() { return getType().isAir(); }
        @Override public BlockData getBlockData() { return cell(position).data(); }
        @Override public void setType(Material material) { setType(material, true); }
        @Override public void setType(Material material, boolean physics) { setBlockData(material.createBlockData(), physics); }
        @Override public void setBlockData(BlockData data) { setBlockData(data, true); }
        @Override public void setBlockData(BlockData data, boolean physics) {
            checkThread();
            BlockData copy = copyData(data);
            boolean tile = rules.geometry(RollbackBlockStore.this, position, copy).blockEntity;
            replace(position, cell(position).withData(copy, tile), physics);
        }
        @Override public boolean restoreBlockSnapshot(BlockData data, byte[] blockEntity, boolean force, boolean physics) {
            checkThread();
            if (!force && getType() != data.getMaterial()) return false;
            if (blockEntity != null && blockEntity.length > RollbackBlockSnapshot.MAXIMUM_BLOCK_ENTITY_BYTES)
                throw new IllegalArgumentException("Block snapshot exceeds budget");
            var current = cell(position);
            replace(position, new Cell(data, blockEntity, current.biome, current.biomeKey, current.noiseBiomeKey,
                    current.light, current.temperature, current.humidity), physics);
            return true;
        }
        @Override public BlockState getState() { return new SavedBlock(this, cell(position)); }
        @Override public boolean isLiquid() { return geometry(position).liquid; }
        @Override public boolean isSolid() { return geometry(position).solid; }
        @Override public boolean isPassable() { return geometry(position).passable; }
        @Override public BoundingBox getBoundingBox() {
            Box shape = geometry(position).bounds;
            return shape == null ? new BoundingBox() : shape.at(position);
        }
        @Override public List<BoundingBox> getCollisionBoxes() {
            return geometry(position).collision.stream().map(box -> box.at(position)).toList();
        }
        @Override public byte getLightLevel() { return rules.light(RollbackBlockStore.this, position); }
        @Override public Biome getBiome() { return cell(position).biome; }
        @Override public byte getData() { return rules.legacyData(RollbackBlockStore.this, position, getBlockData()); }
        @Override public Block getRelative(int x, int y, int z) {
            Position next = position.offset(x, y, z);
            return block(next.x, next.y, next.z);
        }
        @Override public Block getRelative(BlockFace face) { return getRelative(face, 1); }
        @Override public Block getRelative(BlockFace face, int distance) {
            int[] direction = direction(face);
            return getRelative(Math.multiplyExact(direction[0], distance), Math.multiplyExact(direction[1], distance),
                    Math.multiplyExact(direction[2], distance));
        }
        @Override public BlockFace getFace(Block other) {
            if (!Objects.equals(other.getWorld(), world)) return null;
            int x = other.getX() - position.x, y = other.getY() - position.y, z = other.getZ() - position.z;
            for (BlockFace face : BlockFace.values()) if (Arrays.equals(direction(face), new int[]{x, y, z})) return face;
            return null;
        }
        @Override public Collection<ItemStack> getDrops() { return List.copyOf(rules.drops(RollbackBlockStore.this, position, null)); }
        @Override public boolean breakNaturally() { return breakNaturally(null); }
        @Override public boolean breakNaturally(ItemStack tool) { return rules.breakNaturally(RollbackBlockStore.this, position, tool); }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void ignored) { }
        @Override public Collection<?> rollbackReferences() { return List.of(RollbackBlockStore.this); }
    }

    public static RollbackBlockSnapshot portableSnapshot(Object value) {
        if (!(value instanceof RollbackBlockStore.SavedBlock saved)) return null;
        return saved.portable();
    }

    private final class SavedBlock extends BlockState implements RollbackStateCell<Void> {
        private final View block;
        private final Cell value;
        private SavedBlock(View block, Cell value) { this.block = block; this.value = value; }
        private RollbackBlockSnapshot portable() {
            checkThread();
            return new RollbackBlockSnapshot(block.reference(), value.data(), value.blockEntity());
        }
        @Override public Material getType() { return value.data.getMaterial(); }
        @Override public BlockData getBlockData() { return value.data(); }
        @Override public Block getBlock() { return block; }
        @Override public boolean hasBlockEntity() { return value.hasBlockEntity(); }
        @Override public boolean update(boolean force, boolean physics) {
            if (!force && getType() != block.getType()) return false;
            block.restoreBlockSnapshot(value.data(), value.blockEntity(), force, physics);
            return true;
        }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void ignored) { }
        @Override public Collection<?> rollbackReferences() { return List.of(RollbackBlockStore.this); }
    }

    private static int[] direction(BlockFace face) {
        return switch (Objects.requireNonNull(face, "face")) {
            case DOWN -> new int[]{0, -1, 0};
            case UP -> new int[]{0, 1, 0};
            case NORTH -> new int[]{0, 0, -1};
            case SOUTH -> new int[]{0, 0, 1};
            case EAST -> new int[]{1, 0, 0};
            case WEST -> new int[]{-1, 0, 0};
            case NORTH_EAST -> new int[]{1, 0, -1};
            case NORTH_WEST -> new int[]{-1, 0, -1};
            case SOUTH_EAST -> new int[]{1, 0, 1};
            case SOUTH_WEST -> new int[]{-1, 0, 1};
            case SELF -> new int[]{0, 0, 0};
        };
    }
}
