package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import java.util.*;
import java.util.function.Function;
import java.util.function.LongFunction;

/** Copies vanilla's per-query-chunk structure references and inclusive piece bounds without loading chunks. */
final class PaperRollbackStructureCapture {
    private PaperRollbackStructureCapture() { }

    static RollbackStructurePieces capture(ServerLevel level, Bounds bounds, int maximumChunks) {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Capture structure pieces at the live terrain boundary");
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        return capture(bounds, maximumChunks, structure -> registry.getResourceKey(structure).orElseThrow(
                () -> new IllegalStateException("Unregistered native structure reference")).identifier().toString(),
                key -> level.getChunkSource().getChunkAtIfLoadedImmediately(ChunkPos.getX(key), ChunkPos.getZ(key)));
    }

    static RollbackStructurePieces capture(Bounds bounds, int maximumChunks, Function<Structure, String> registryKey,
            LongFunction<? extends ChunkAccess> lookup) {
        int minX = bounds.minX() >> 4, maxX = (bounds.maxX() - 1) >> 4;
        int minZ = bounds.minZ() >> 4, maxZ = (bounds.maxZ() - 1) >> 4;
        if (maximumChunks < 1 || ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1) > maximumChunks)
            throw new IllegalArgumentException("Structure capture exceeds loaded chunk budget");
        var chunks = new HashMap<Long, ChunkAccess>();
        LongFunction<ChunkAccess> loaded = key -> chunks.computeIfAbsent(key, pos -> {
            if (chunks.size() >= maximumChunks) throw new IllegalArgumentException("Structure start chunk budget exceeded");
            var chunk = lookup.apply(pos);
            if (chunk == null) throw new IllegalStateException("Structure capture requires loaded chunk " + new ChunkPos(pos));
            return chunk;
        });
        var rows = new LinkedHashSet<RollbackStructurePieces.Piece>();
        int remainingChecks = RollbackStructurePieces.MAXIMUM_PIECES * 64;
        for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++) {
            var references = loaded.apply(ChunkPos.asLong(cx, cz)).getAllReferences();
            for (var entry : references.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                String key = registryKey.apply(entry.getKey());
                var origins = entry.getValue().iterator();
                while (origins.hasNext()) {
                    if (--remainingChecks < 0) throw new IllegalArgumentException("Structure capture exceeds reference inspection budget");
                    long origin = origins.nextLong();
                    var start = loaded.apply(origin).getStartForStructure(entry.getKey());
                    if (start == null) throw new IllegalStateException("Missing referenced " + key + " start at " + new ChunkPos(origin));
                    if (!start.isValid()) continue;
                    if (start.getStructure() != entry.getKey() || start.getChunkPos().toLong() != origin)
                        throw new IllegalStateException("Mismatched native structure start at " + new ChunkPos(origin));
                    for (var piece : start.getPieces()) {
                        if (--remainingChecks < 0) throw new IllegalArgumentException("Structure capture exceeds piece inspection budget");
                        var box = piece.getBoundingBox();
                        int x1 = Math.max(Math.max(bounds.minX(), cx << 4), box.minX());
                        int x2 = Math.min(Math.min(bounds.maxX() - 1, (cx << 4) + 15), box.maxX());
                        int z1 = Math.max(Math.max(bounds.minZ(), cz << 4), box.minZ());
                        int z2 = Math.min(Math.min(bounds.maxZ() - 1, (cz << 4) + 15), box.maxZ());
                        int y1 = Math.max(bounds.minY(), box.minY()), y2 = Math.min(bounds.maxY() - 1, box.maxY());
                        if (x1 > x2 || y1 > y2 || z1 > z2) continue;
                        rows.add(new RollbackStructurePieces.Piece(key, x1, y1, z1, x2, y2, z2));
                        if (rows.size() > RollbackStructurePieces.MAXIMUM_PIECES)
                            throw new IllegalArgumentException("Structure capture exceeds piece budget");
                    }
                }
            }
        }
        return rows.isEmpty() ? RollbackStructurePieces.EMPTY : new RollbackStructurePieces(List.copyOf(rows));
    }
}
