package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.starlight.chunk.StarlightChunk;
import ca.spottedleaf.moonrise.patches.starlight.light.*;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import java.util.*;

/** Synchronous native light propagation over detached sections; no live world or chunk scheduler. */
public final class PaperRollbackLighting implements RollbackStateCell<PaperRollbackLighting.Snapshot> {
    public static final class Snapshot {
        private final PaperRollbackLighting owner;
        private final Map<Long, ChunkState> chunks;
        private Snapshot(PaperRollbackLighting owner, Map<Long, ChunkState> chunks) { this.owner = owner; this.chunks = chunks; }
    }
    private record ChunkState(LevelChunkSection[] sections, SWMRNibbleArray.SaveState[] sky,
            SWMRNibbleArray.SaveState[] block, boolean[] skyEmpty, boolean[] blockEmpty) { }
    private final Thread owner = Thread.currentThread();
    private final Bounds bounds;
    private final boolean hasSky;
    private final Map<Long, ProtoChunk> chunks = new TreeMap<>();
    private final PaperRollbackBlockStates states = new PaperRollbackBlockStates();
    private final Level world;
    private final LightChunkGetter getter;
    private final StarLightInterface reader;
    private final int minSection, sectionCount;
    private boolean updating, failed;

    /** Full sections and one captured chunk of horizontal light halo are required around mutations. */
    public PaperRollbackLighting(RollbackTerrainSeed terrain, RollbackLightSeed light, RegistryAccess registries, boolean hasSky) {
        if (!TickThread.isTickThread() || RollbackDomain.active() || RollbackClock.active())
            throw new IllegalStateException("Prepare private lighting on the live tick thread");
        Objects.requireNonNull(terrain); Objects.requireNonNull(light); Objects.requireNonNull(registries);
        bounds = terrain.bounds(); this.hasSky = hasSky;
        if (!bounds.equals(light.bounds()) || ((bounds.minX() | bounds.minY() | bounds.minZ()
                | bounds.maxX() | bounds.maxY() | bounds.maxZ()) & 15) != 0)
            throw new IllegalArgumentException("Lighting requires matching complete captured sections");
        var height = LevelHeightAccessor.create(bounds.minY(), bounds.maxY() - bounds.minY());
        minSection = height.getMinSectionY(); sectionCount = height.getSectionsCount();
        var containers = PalettedContainerFactory.create(registries);
        for (int x = bounds.minX() >> 4; x < bounds.maxX() >> 4; x++) for (int z = bounds.minZ() >> 4; z < bounds.maxZ() >> 4; z++) {
            var chunk = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, height, containers, null);
            chunk.setPersistedStatus(ChunkStatus.LIGHT); chunk.setLightCorrect(true);
            var access = (StarlightChunk) chunk;
            access.starlight$setBlockNibbles(emptyLayers(false)); access.starlight$setSkyNibbles(emptyLayers(hasSky));
            chunks.put(ChunkPos.asLong(x, z), chunk);
        }
        var palette = terrain.palette().stream().map(cell -> states.decode(cell.data())).toList(); int index = 0;
        for (int y = bounds.minY(); y < bounds.maxY(); y++) for (int z = bounds.minZ(); z < bounds.maxZ(); z++)
            for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                var position = new Position(x, y, z); var chunk = chunk(position);
                chunk.getSections()[(y >> 4) - minSection].setBlockState(x & 15, y & 15, z & 15, palette.get(terrain.paletteIndex(index++)));
                var access = (StarlightChunk) chunk; int layer = (y >> 4) - minSection + 1;
                int sunlight = light.sky(position);
                if (!hasSky && sunlight != 0) throw new IllegalArgumentException("Sky light in a dimension without sky lighting");
                access.starlight$getSkyNibbles()[layer].set(x, y, z, sunlight);
                access.starlight$getBlockNibbles()[layer].set(x, y, z, light.block(position));
            }
        for (var chunk : chunks.values()) {
            var access = (StarlightChunk) chunk;
            for (var layer : access.starlight$getSkyNibbles()) layer.updateVisible();
            for (var layer : access.starlight$getBlockNibbles()) layer.updateVisible();
            boolean[] empty = new boolean[sectionCount];
            for (int i = 0; i < empty.length; i++) empty[i] = chunk.getSections()[i].hasOnlyAir();
            access.starlight$setSkyEmptinessMap(empty.clone()); access.starlight$setBlockEmptinessMap(empty.clone());
        }
        world = RollbackNativeQueryShell.create(Level.class).constant(Level::isClientSide, true)
                .constant(Level::getMinY, bounds.minY()).constant(Level::getHeight, bounds.maxY() - bounds.minY())
                .constant(Level::getMinSectionY, minSection).constant(Level::getMaxSectionY, height.getMaxSectionY())
                .constant(Level::getSectionsCount, sectionCount).instance();
        getter = new LightChunkGetter() {
            @Override public LightChunk getChunkForLighting(int x, int z) { return chunks.get(ChunkPos.asLong(x, z)); }
            @Override public BlockGetter getLevel() { return world; }
        };
        reader = new LevelLightEngine(getter, true, hasSky).starlight$getLightEngine();
    }
    private SWMRNibbleArray[] emptyLayers(boolean sky) {
        var result = new SWMRNibbleArray[sectionCount + 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = new SWMRNibbleArray();
            if (sky && i == result.length - 1) result[i].setFull(); else result[i].setZero();
            result[i].updateVisible();
        }
        return result;
    }
    public int sky(Position position) { check(); return reader.getSkyLightValue(nativePos(position), chunk(position)); }
    public int block(Position position) { check(); return reader.getBlockLightValue(nativePos(position), chunk(position)); }
    private BlockPos nativePos(Position position) { return new BlockPos(position.x(), position.y(), position.z()); }
    private ProtoChunk chunk(Position position) {
        if (!bounds.contains(position)) throw new IllegalArgumentException("Lighting query outside captured terrain");
        return chunks.get(ChunkPos.asLong(position.x() >> 4, position.z() >> 4));
    }
    /** Apply committed private terrain changes before the next light query. Failure requires checkpoint restoration. */
    public void apply(Map<Position, BlockData> changes) {
        check(); Objects.requireNonNull(changes);
        var decoded = new LinkedHashMap<Position, net.minecraft.world.level.block.state.BlockState>();
        changes.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<Position, BlockData> e) -> e.getKey().x())
                .thenComparingInt(e -> e.getKey().y()).thenComparingInt(e -> e.getKey().z())).forEach(entry -> {
            Position p = entry.getKey(); chunk(p);
            if ((long) p.x() - bounds.minX() < 16 || (long) bounds.maxX() - 1 - p.x() < 16
                    || (long) p.z() - bounds.minZ() < 16 || (long) bounds.maxZ() - 1 - p.z() < 16)
                throw new IllegalArgumentException("Lighting mutation requires a captured chunk halo");
            decoded.put(p, states.decode(Objects.requireNonNull(entry.getValue())));
        });
        updating = true;
        var blockEngine = new BlockStarLightEngine(); var skyEngine = hasSky ? new SkyStarLightEngine() : null;
        try {
            blockEngine.setWorld(world); if (skyEngine != null) skyEngine.setWorld(world);
            var groups = new TreeMap<Long, Set<BlockPos>>();
            decoded.forEach((p, state) -> {
                chunk(p).getSections()[(p.y() >> 4) - minSection].setBlockState(p.x() & 15, p.y() & 15, p.z() & 15, state);
                groups.computeIfAbsent(ChunkPos.asLong(p.x() >> 4, p.z() >> 4), ignored -> new LinkedHashSet<>()).add(nativePos(p));
            });
            for (var entry : groups.entrySet()) {
                var chunk = chunks.get(entry.getKey()); var position = chunk.getPos();
                var empty = new Boolean[sectionCount];
                for (int i = 0; i < empty.length; i++) empty[i] = chunk.getSections()[i].hasOnlyAir();
                blockEngine.blocksChangedInChunk(getter, position.x, position.z, entry.getValue(), empty);
                if (skyEngine != null) skyEngine.blocksChangedInChunk(getter, position.x, position.z, entry.getValue(), empty);
            }
        } catch (RuntimeException | Error failure) { failed = true; throw failure; }
        finally { blockEngine.setWorld(null); if (skyEngine != null) skyEngine.setWorld(null); updating = false; }
    }
    @Override public Snapshot captureRollbackState() {
        check(); var saved = new TreeMap<Long, ChunkState>();
        chunks.forEach((key, chunk) -> {
            var access = (StarlightChunk) chunk;
            saved.put(key, new ChunkState(Arrays.stream(chunk.getSections()).map(LevelChunkSection::copy).toArray(LevelChunkSection[]::new),
                    save(access.starlight$getSkyNibbles()), save(access.starlight$getBlockNibbles()),
                    access.starlight$getSkyEmptinessMap().clone(), access.starlight$getBlockEmptinessMap().clone()));
        });
        return new Snapshot(this, saved);
    }
    @Override public void restoreRollbackState(Snapshot snapshot) {
        thread();
        if (updating || snapshot.owner != this) throw new IllegalArgumentException("Lighting checkpoint owner/boundary");
        // Allocate every replacement before changing any published chunk state.
        record Restored(LevelChunkSection[] sections, SWMRNibbleArray[] sky,
                SWMRNibbleArray[] block, boolean[] skyEmpty, boolean[] blockEmpty) { }
        var staged = new TreeMap<Long, Restored>();
        snapshot.chunks.forEach((key, saved) -> staged.put(key, new Restored(
                Arrays.stream(saved.sections).map(LevelChunkSection::copy).toArray(LevelChunkSection[]::new),
                restore(saved.sky), restore(saved.block), saved.skyEmpty.clone(), saved.blockEmpty.clone())));
        updating = true;
        failed = true;
        try {
            staged.forEach((key, saved) -> {
                var chunk = chunks.get(key); var access = (StarlightChunk) chunk;
                System.arraycopy(saved.sections, 0, chunk.getSections(), 0, saved.sections.length);
                access.starlight$setSkyNibbles(saved.sky); access.starlight$setBlockNibbles(saved.block);
                access.starlight$setSkyEmptinessMap(saved.skyEmpty); access.starlight$setBlockEmptinessMap(saved.blockEmpty);
            });
            failed = false;
        } finally { updating = false; }
    }
    private static SWMRNibbleArray.SaveState[] save(SWMRNibbleArray[] layers) {
        return Arrays.stream(layers).map(SWMRNibbleArray::getSaveState).toArray(SWMRNibbleArray.SaveState[]::new);
    }
    private static SWMRNibbleArray[] restore(SWMRNibbleArray.SaveState[] layers) {
        return Arrays.stream(layers).map(value -> value == null ? new SWMRNibbleArray(null, true)
                : new SWMRNibbleArray(value.data == null ? null : value.data.clone(), value.state)).toArray(SWMRNibbleArray[]::new);
    }
    private void check() { thread(); if (updating || failed) throw new IllegalStateException("Lighting requires a settled valid checkpoint"); }
    private void thread() { if (Thread.currentThread() != owner) throw new IllegalStateException("Private lighting crossed threads"); }
}
