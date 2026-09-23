package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainSeed;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.craftbukkit.block.data.CraftBlockData;

import java.io.*;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Read-only, synchronous capture of loaded terrain before private simulation starts. */
public final class PaperRollbackTerrainCapture {
    private PaperRollbackTerrainCapture() { }

    public record Limits(int maximumCells, int maximumLoadedChunks, long maximumStorageBytes, int maximumBlockEntityBytes) {
        public Limits {
            if (maximumCells < 1 || maximumCells > 16_777_216 || maximumLoadedChunks < 1 || maximumLoadedChunks > 65_536
                    || maximumStorageBytes < 1 || maximumStorageBytes > 1_073_741_824L
                    || maximumBlockEntityBytes < 1 || maximumBlockEntityBytes > maximumStorageBytes) {
                throw new IllegalArgumentException("Terrain capture limits");
            }
        }
    }

    public static RollbackTerrainSeed capture(ServerLevel source, Bounds bounds, Limits limits) {
        Objects.requireNonNull(source, "source world"); Objects.requireNonNull(bounds, "bounds"); Objects.requireNonNull(limits, "limits");
        if (!TickThread.isTickThread() || RollbackClock.active()) throw new IllegalStateException("Capture terrain on the server tick thread before replay");
        long count = ((long) bounds.maxX() - bounds.minX()) * (bounds.maxY() - bounds.minY()) * (bounds.maxZ() - bounds.minZ());
        if (count > limits.maximumCells()) throw new IllegalArgumentException("Terrain cell budget exceeded");
        // Smoothed biome temperature can consult an adjacent chunk. Preflight that
        // halo too, so native biome reads never generate/load neighboring terrain.
        int minX = Math.floorDiv(bounds.minX(), 16) - 1, maxX = Math.floorDiv(bounds.maxX() - 1, 16) + 1;
        int minZ = Math.floorDiv(bounds.minZ(), 16) - 1, maxZ = Math.floorDiv(bounds.maxZ() - 1, 16) + 1;
        long chunks = ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
        if (chunks > limits.maximumLoadedChunks()) throw new IllegalArgumentException("Terrain chunk budget exceeded");
        var builder = new RollbackTerrainSeed.Builder(bounds, limits.maximumStorageBytes());
        long tick = source.getGameTime();
        Map<Long, LevelChunk> loaded = new HashMap<>();
        Map<net.minecraft.world.level.block.state.BlockState, com.projectkorra.projectkorra.platform.mc.block.data.BlockData> states = new IdentityHashMap<>();
        var chunkSource = source.getChunkSource();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            var chunk = chunkSource.getChunkAtIfLoadedImmediately(x, z);
            if (chunk == null) throw new IllegalStateException("Terrain capture requires loaded chunk " + x + "," + z);
            loaded.put(ChunkPos.asLong(x, z), chunk);
        }
        for (int y = bounds.minY(); y < bounds.maxY(); y++) for (int z = bounds.minZ(); z < bounds.maxZ(); z++) for (int x = bounds.minX(); x < bounds.maxX(); x++) {
            var position = new BlockPos(x, y, z);
            var chunk = loaded.get(ChunkPos.asLong(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
            var nativeState = chunk.getBlockState(position);
            var data = states.computeIfAbsent(nativeState, state -> {
                var nativeData = CraftBlockData.fromData(state);
                var detached = BukkitMC.blockData(nativeData);
                // Preserve properties beyond the common mutable facade (e.g. fire age).
                detached.setExactState(nativeData.getAsString());
                return detached;
            });
            byte[] tile = null;
            if (nativeState.hasBlockEntity()) {
                // getBlockEntity/getBlockEntityNbtForSaving may instantiate packed
                // entities. Read the existing maps without changing the live chunk.
                var entity = chunk.getBlockEntities().get(position);
                if (entity != null && entity.isRemoved()) throw new IllegalStateException("Removed block entity during terrain capture at " + position);
                CompoundTag tag = entity == null ? chunk.getBlockEntityNbt(position) : entity.saveWithFullMetadata(source.registryAccess());
                if (tag == null) throw new IllegalStateException("Missing block entity during terrain capture at " + position);
                tile = encode(tag, limits.maximumBlockEntityBytes());
            }
            var biome = source.getNoiseBiome(x >> 2, y >> 2, z >> 2);
            var key = biome.unwrapKey().orElseThrow(() -> new IllegalStateException("Unregistered biome in terrain capture")).identifier();
            var smoothedBiome = source.getBiome(position);
            var smoothedKey = smoothedBiome.unwrapKey().orElseThrow(() -> new IllegalStateException("Unregistered smoothed biome in terrain capture")).identifier();
            // Preserve the existing BukkitMC common-biome mapping. The full native
            // registry key is retained separately for native world queries.
            Biome common = Biome.DESERT;
            if (key.getNamespace().equals("minecraft")) {
                try { common = Biome.valueOf(key.getPath().toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ignored) { }
            }
            builder.append(new Cell(data, tile, common, smoothedKey.toString(), key.toString(), (byte) source.getMaxLocalRawBrightness(position),
                    smoothedBiome.value().getTemperature(position, source.getSeaLevel()), biome.value().climateSettings.downfall()));
        }
        if (source.getGameTime() != tick) throw new IllegalStateException("World advanced during synchronous terrain capture");
        return builder.finish();
    }

    private static byte[] encode(CompoundTag tag, int maximumBytes) {
        var bytes = new ByteArrayOutputStream(Math.min(maximumBytes, 1024));
        var bounded = new OutputStream() {
            private void room(int count) {
                if (count > maximumBytes - bytes.size()) throw new IllegalStateException("Block entity exceeds terrain capture budget");
            }
            @Override public void write(int value) { room(1); bytes.write(value); }
            @Override public void write(byte[] data, int offset, int length) { room(length); bytes.write(data, offset, length); }
        };
        try { NbtIo.write(tag, new DataOutputStream(bounded)); }
        catch (IOException failure) { throw new UncheckedIOException("Could not detach block entity", failure); }
        return bytes.toByteArray();
    }
}
