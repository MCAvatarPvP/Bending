package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import net.minecraft.block.BlockState;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.*;
import net.minecraft.world.chunk.*;
import net.minecraft.world.chunk.light.*;
import java.util.*;

/** Synchronous native lighting in detached chunks. No client world or live light queues are retained. */
public final class FabricRollbackLighting implements RollbackStateCell<FabricRollbackLighting.Snapshot> {
    private record ChunkState(ChunkSection[] sections, ChunkNibbleArray[] sky, ChunkNibbleArray[] block) { }
    public static final class Snapshot {
        private final FabricRollbackLighting owner;
        private final Map<Long, ChunkState> chunks;
        private Snapshot(FabricRollbackLighting owner, Map<Long, ChunkState> chunks) { this.owner = owner; this.chunks = chunks; }
    }
    private final Thread thread = Thread.currentThread();
    private final Bounds bounds;
    private final boolean hasSky;
    private final HeightLimitView height;
    private final PalettesFactory palettes;
    private Map<Long, ProtoChunk> chunks;
    private LightingProvider lighting;
    private boolean updating, failed;

    public FabricRollbackLighting(RollbackTerrainSeed terrain, RollbackLightSeed light,
            DynamicRegistryManager.Immutable registries, boolean hasSky) {
        if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Prepare lighting before replay");
        Objects.requireNonNull(terrain); Objects.requireNonNull(light);
        bounds = terrain.bounds(); this.hasSky = hasSky;
        if (!bounds.equals(light.bounds()) || ((bounds.minX() | bounds.minY() | bounds.minZ()
                | bounds.maxX() | bounds.maxY() | bounds.maxZ()) & 15) != 0)
            throw new IllegalArgumentException("Lighting requires matching complete captured sections");
        height = HeightLimitView.create(bounds.minY(), bounds.maxY() - bounds.minY());
        palettes = PalettesFactory.fromRegistryManager(Objects.requireNonNull(registries));
        var initial = new TreeMap<Long, ChunkState>();
        for (int x = bounds.minX() >> 4; x < bounds.maxX() >> 4; x++) for (int z = bounds.minZ() >> 4; z < bounds.maxZ() >> 4; z++) {
            var chunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.NO_UPGRADE_DATA, height, palettes, null);
            var sky = layers(hasSky); var block = layers(false);
            initial.put(ChunkPos.toLong(x, z), new ChunkState(chunk.getSectionArray(), sky, block));
        }
        var palette = terrain.palette().stream().map(cell -> FabricRollbackGeometry.decode(cell.data())).toList();
        int index = 0;
        for (int y = bounds.minY(); y < bounds.maxY(); y++) for (int z = bounds.minZ(); z < bounds.maxZ(); z++) for (int x = bounds.minX(); x < bounds.maxX(); x++) {
            var p = new Position(x, y, z); var state = initial.get(ChunkPos.toLong(x >> 4, z >> 4));
            int section = height.getSectionIndex(y);
            state.sections[section].setBlockState(x & 15, y & 15, z & 15, palette.get(terrain.paletteIndex(index++)));
            int sunlight = light.sky(p);
            if (!hasSky && sunlight != 0) throw new IllegalArgumentException("Sky light in a dimension without sky lighting");
            state.sky[section + 1].set(x & 15, y & 15, z & 15, sunlight);
            state.block[section + 1].set(x & 15, y & 15, z & 15, light.block(p));
        }
        install(initial);
    }
    /**
     * Connect to the logical store before constructing its world. The darkness supplier must
     * read captured simulation time/weather, and its mutable owner must be a graph root.
     */
    public Rules rules(Rules delegate, java.util.function.IntSupplier skyDarkness) {
        check(); return new LitRules(Objects.requireNonNull(delegate), Objects.requireNonNull(skyDarkness));
    }
    private final class LitRules implements Rules, RollbackStateCell<Void> {
        private final Rules delegate;
        private final java.util.function.IntSupplier darkness;
        private LitRules(Rules delegate, java.util.function.IntSupplier darkness) { this.delegate = delegate; this.darkness = darkness; }
        @Override public Geometry geometry(RollbackBlockStore terrain, Position p, BlockData data) { return delegate.geometry(terrain, p, data); }
        @Override public byte legacyData(RollbackBlockStore terrain, Position p, BlockData data) { return delegate.legacyData(terrain, p, data); }
        @Override public void physics(RollbackBlockStore terrain, Position p) { delegate.physics(terrain, p); }
        @Override public java.util.Collection<com.projectkorra.projectkorra.platform.mc.inventory.ItemStack> drops(
                RollbackBlockStore terrain, Position p, com.projectkorra.projectkorra.platform.mc.inventory.ItemStack tool) { return delegate.drops(terrain, p, tool); }
        @Override public boolean breakNaturally(RollbackBlockStore terrain, Position p,
                com.projectkorra.projectkorra.platform.mc.inventory.ItemStack tool) { return delegate.breakNaturally(terrain, p, tool); }
        @Override public void changed(RollbackBlockStore terrain, Position p) {
            requireBounds(terrain);
            apply(Map.of(p, terrain.cell(p).data()));
            delegate.changed(terrain, p);
        }
        @Override public byte light(RollbackBlockStore terrain, Position p) {
            requireBounds(terrain);
            int amount = darkness.getAsInt();
            if (amount < 0 || amount > 15) throw new IllegalStateException("Invalid captured sky darkness");
            return (byte) Math.max(block(p), sky(p) - amount);
        }
        private void requireBounds(RollbackBlockStore terrain) {
            if (!bounds.equals(terrain.bounds())) throw new IllegalArgumentException("Lighting/terrain bounds mismatch");
        }
        @Override public Void captureRollbackState() { check(); return null; }
        @Override public void restoreRollbackState(Void state) { thread(); }
        @Override public Collection<?> rollbackReferences() {
            return delegate instanceof RollbackStateCell<?> ? List.of(FabricRollbackLighting.this, delegate) : List.of(FabricRollbackLighting.this);
        }
    }
    private ChunkNibbleArray[] layers(boolean sky) {
        var result = new ChunkNibbleArray[height.countVerticalSections() + 2];
        for (int i = 0; i < result.length; i++) result[i] = new ChunkNibbleArray(sky && i == result.length - 1 ? 15 : 0);
        return result;
    }
    /** Build a complete replacement before publishing any restored chunk or light engine. */
    private void install(Map<Long, ChunkState> saved) {
        var restored = new TreeMap<Long, ProtoChunk>();
        saved.forEach((key, state) -> {
            var chunk = new ProtoChunk(new ChunkPos(key), UpgradeData.NO_UPGRADE_DATA, height, palettes, null);
            for (int i = 0; i < state.sections.length; i++) chunk.getSectionArray()[i] = state.sections[i].copy();
            chunk.getChunkSkyLight().refreshSurfaceY(chunk);
            restored.put(key, chunk);
        });
        var provider = new LightingProvider(new ChunkProvider() {
            @Override public LightSourceView getChunk(int x, int z) { return restored.get(ChunkPos.toLong(x, z)); }
            @Override public BlockView getWorld() { return restored.firstEntry().getValue(); }
        }, true, hasSky);
        saved.forEach((key, state) -> {
            var chunk = restored.get(key); var pos = chunk.getPos(); provider.setRetainData(pos, true);
            for (int i = 0; i < state.block.length; i++) {
                var section = ChunkSectionPos.from(pos, height.getBottomSectionCoord() - 1 + i);
                if (state.block[i] != null) provider.enqueueSectionData(LightType.BLOCK, section, state.block[i].copy());
                if (hasSky && state.sky[i] != null) provider.enqueueSectionData(LightType.SKY, section, state.sky[i].copy());
            }
            for (int i = 0; i < state.sections.length; i++)
                // Keep captured sections allocated even when all-air: imported boundary light
                // may be nonzero and must not be replaced by empty-column sky extrusion.
                provider.setSectionStatus(ChunkSectionPos.from(pos, height.sectionIndexToCoord(i)), false);
            provider.setColumnEnabled(pos, true);
        });
        settle(provider); chunks = restored; lighting = provider; failed = false;
    }
    public Bounds bounds() { check(); return bounds; }
    public int sky(Position p) { check(); inside(p); return lighting.get(LightType.SKY).getLightLevel(nativePos(p)); }
    public int block(Position p) { check(); inside(p); return lighting.get(LightType.BLOCK).getLightLevel(nativePos(p)); }
    public void apply(Map<Position, BlockData> changes) {
        check(); Objects.requireNonNull(changes);
        var decoded = new LinkedHashMap<Position, BlockState>();
        changes.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<Position, BlockData> e) -> e.getKey().x())
                .thenComparingInt(e -> e.getKey().y()).thenComparingInt(e -> e.getKey().z())).forEach(entry -> {
            var p = entry.getKey(); inside(p);
            if ((long) p.x() - bounds.minX() < 16 || (long) bounds.maxX() - 1 - p.x() < 16
                    || (long) p.z() - bounds.minZ() < 16 || (long) bounds.maxZ() - 1 - p.z() < 16)
                throw new IllegalArgumentException("Lighting mutation requires a captured chunk halo");
            decoded.put(p, FabricRollbackGeometry.decode(Objects.requireNonNull(entry.getValue())));
        });
        updating = true;
        try {
            var touched = new TreeSet<Long>();
            decoded.forEach((p, state) -> {
                long key = ChunkPos.toLong(p.x() >> 4, p.z() >> 4); var chunk = chunks.get(key);
                chunk.getSection(height.getSectionIndex(p.y())).setBlockState(p.x() & 15, p.y() & 15, p.z() & 15, state);
                touched.add(key);
            });
            for (long key : touched) {
                var chunk = chunks.get(key); chunk.getChunkSkyLight().refreshSurfaceY(chunk);
            }
            decoded.keySet().forEach(p -> lighting.checkBlock(nativePos(p)));
            settle(lighting);
        } catch (RuntimeException | Error error) { failed = true; throw error; }
        finally { updating = false; }
    }
    @Override public Snapshot captureRollbackState() {
        check(); var saved = new TreeMap<Long, ChunkState>();
        chunks.forEach((key, chunk) -> {
            var sky = new ChunkNibbleArray[height.countVerticalSections() + 2];
            var block = new ChunkNibbleArray[sky.length];
            for (int i = 0; i < sky.length; i++) {
                var section = ChunkSectionPos.from(chunk.getPos(), height.getBottomSectionCoord() - 1 + i);
                var s = lighting.get(LightType.SKY).getLightSection(section);
                var b = lighting.get(LightType.BLOCK).getLightSection(section);
                sky[i] = s == null ? null : s.copy(); block[i] = b == null ? null : b.copy();
            }
            saved.put(key, new ChunkState(Arrays.stream(chunk.getSectionArray()).map(ChunkSection::copy).toArray(ChunkSection[]::new), sky, block));
        });
        return new Snapshot(this, saved);
    }
    @Override public void restoreRollbackState(Snapshot state) {
        thread();
        if (updating || state.owner != this) throw new IllegalArgumentException("Lighting checkpoint owner/boundary");
        updating = true;
        try { install(state.chunks); } finally { updating = false; }
    }
    private static void settle(LightingProvider provider) {
        for (int pass = 0; provider.hasUpdates(); pass++) {
            if (pass == 100) throw new IllegalStateException("Private lighting failed to settle");
            provider.doLightUpdates();
        }
    }
    private static BlockPos nativePos(Position p) { return new BlockPos(p.x(), p.y(), p.z()); }
    private void inside(Position p) { if (!bounds.contains(p)) throw new IllegalArgumentException("Lighting query outside captured terrain"); }
    private void check() { thread(); if (updating || failed) throw new IllegalStateException("Lighting requires a settled valid checkpoint"); }
    private void thread() { if (Thread.currentThread() != thread) throw new IllegalStateException("Private lighting crossed threads"); }
}
