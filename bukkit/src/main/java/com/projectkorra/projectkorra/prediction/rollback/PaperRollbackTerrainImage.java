package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.bukkit.craftbukkit.block.data.CraftBlockData;

import java.util.*;

/** Exact immutable inputs for arena expansion. Only capture and revalidation touch the live level. */
final class PaperRollbackTerrainImage {
    record Result(RollbackTerrainSeed terrain, RollbackLightSeed light) { }
    private record Section(PalettedContainerRO<BlockState> states,
                           PalettedContainerRO<Holder<net.minecraft.world.level.biome.Biome>> biomes,
                           byte[] sky, byte[] block) { }
    private record Chunk(Section[] sections, byte[] paletteBytes, Map<Long, byte[]> tiles) {
        boolean same(Chunk other) {
            if (!Arrays.equals(paletteBytes, other.paletteBytes) || !tiles.keySet().equals(other.tiles.keySet())) return false;
            for (var tile : tiles.entrySet()) if (!Arrays.equals(tile.getValue(), other.tiles.get(tile.getKey()))) return false;
            if (sections.length != other.sections.length) return false;
            for (int i = 0; i < sections.length; i++) if (!Arrays.equals(sections[i].sky, other.sections[i].sky)
                    || !Arrays.equals(sections[i].block, other.sections[i].block)) return false;
            return true;
        }
    }
    private final Bounds bounds;
    private final PaperRollbackTerrainCapture.Limits limits;
    private final int minChunkX, minChunkZ, width, minSection, seaLevel, skyDarken;
    private final UUID world;
    private final Chunk[] chunks;
    private final Map<BlockState, BlockData> states = new IdentityHashMap<>();
    private final BiomeManager biomeManager;
    private static final byte[] DARK = {0}, FULL = {(byte) 255};

    static PaperRollbackTerrainImage capture(ServerLevel source, Bounds bounds, PaperRollbackTerrainCapture.Limits limits, boolean retain) {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Snapshot terrain on the live tick thread");
        return new PaperRollbackTerrainImage(source, bounds, limits, retain);
    }
    private PaperRollbackTerrainImage(ServerLevel source, Bounds bounds, PaperRollbackTerrainCapture.Limits limits, boolean retain) {
        this.bounds = bounds; this.limits = limits;
        long count = ((long)bounds.maxX() - bounds.minX()) * (bounds.maxY() - bounds.minY()) * (bounds.maxZ() - bounds.minZ());
        if (count > limits.maximumCells() || count * Integer.BYTES > limits.maximumStorageBytes())
            throw new IllegalArgumentException("Terrain snapshot exceeds storage budget");
        minChunkX = (bounds.minX() >> 4) - 1; minChunkZ = (bounds.minZ() >> 4) - 1;
        width = ((bounds.maxX() - 1) >> 4) - minChunkX + 2;
        int depth = ((bounds.maxZ() - 1) >> 4) - minChunkZ + 2;
        if ((long)width * depth > limits.maximumLoadedChunks()) throw new IllegalArgumentException("Terrain chunk budget exceeded");
        world = source.getWorld().getUID(); seaLevel = source.getSeaLevel(); skyDarken = source.getSkyDarken();
        minSection = source.getMinSectionY();
        if (bounds.minY() < source.getMinY() || bounds.maxY() > source.getMaxY() + 1)
            throw new IllegalArgumentException("Terrain bounds outside world height");
        chunks = new Chunk[width * depth];
        long[] budget = {limits.maximumStorageBytes()};
        var cache = source.getChunkSource();
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) {
                int cx = minChunkX + x, cz = minChunkZ + z;
                var chunk = cache.getChunkAtIfLoadedImmediately(cx, cz);
                if (chunk == null) throw new IllegalStateException("Terrain requires loaded chunk " + cx + "," + cz);
                boolean interior = x > 0 && x < width - 1 && z > 0 && z < depth - 1;
                chunks[z * width + x] = snapshotChunk(source, chunk, cx, cz, interior, retain, buffer, budget);
            }
        } finally { buffer.release(); }
        biomeManager = retain ? source.getBiomeManager().withDifferentSource(this::noise) : null;
    }
    private Chunk snapshotChunk(ServerLevel source, LevelChunk chunk, int cx, int cz, boolean interior, boolean retain,
                                FriendlyByteBuf buffer, long[] budget) {
        var sections = chunk.getSections();
        var frozen = new Section[sections.length];
        int lightOffset = minSection - source.getLightEngine().getMinLightSection();
        var skyNibbles = interior ? chunk.starlight$getSkyNibbles() : null;
        var blockNibbles = interior ? chunk.starlight$getBlockNibbles() : null;
        buffer.clear();
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            var biomes = section.getBiomes();
            biomes.write(buffer);
            boolean own = interior && ((minSection + i) << 4) < bounds.maxY()
                    && ((minSection + i + 1) << 4) > bounds.minY();
            byte[] sky = DARK, block = DARK;
            if (own) {
                section.getStates().write(buffer);
                if (retain) section.getStates().getAll(state -> states.computeIfAbsent(state, PaperRollbackTerrainImage::detach));
                sky = sky(source, chunk, skyNibbles, i, lightOffset);
                block = layer(blockNibbles[i + lightOffset]);
                reserve(budget, sky.length + block.length);
            }
            frozen[i] = new Section(own && retain ? section.getStates().copy() : null, retain ? biomes.copy() : null, sky, block);
        }
        var encoded = new byte[buffer.readableBytes()];
        reserve(budget, encoded.length); buffer.readBytes(encoded);
        var tiles = new HashMap<Long, byte[]>();
        if (interior) for (var pos : chunk.getBlockEntitiesPos()) {
            if (!bounds.contains(new Position(pos.getX(), pos.getY(), pos.getZ()))) continue;
            var bytes = PaperRollbackTerrainCapture.encode(PaperRollbackTerrainCapture.blockEntity(source, chunk, pos), limits.maximumBlockEntityBytes());
            reserve(budget, bytes.length); tiles.put(pos.asLong(), bytes);
        }
        return new Chunk(frozen, encoded, tiles);
    }
    private static void reserve(long[] budget, long amount) {
        budget[0] -= amount;
        if (budget[0] < 0) throw new IllegalArgumentException("Detached chunk snapshot exceeds storage budget");
    }
    private static BlockData detach(BlockState state) {
        var nativeData = CraftBlockData.fromData(state); var data = BukkitMC.blockData(nativeData);
        data.setExactState(nativeData.getAsString()); return data;
    }
    private static byte[] layer(SWMRNibbleArray nibble) {
        var snapshot = nibble.getSaveState();
        return snapshot == null || snapshot.data == null ? DARK : snapshot.data;
    }
    private static byte[] sky(ServerLevel source, LevelChunk chunk, SWMRNibbleArray[] nibbles, int section, int offset) {
        if (!source.dimensionType().hasSkyLight()) return DARK;
        if (!chunk.isLightCorrect()) return FULL;
        var nibble = nibbles[section + offset];
        if (!nibble.isNullNibbleVisible()) return layer(nibble);
        // Starlight returns full sky above the highest non-empty section. Below it,
        // a missing sky section inherits the bottom plane of the first visible nibble above.
        var empty = chunk.starlight$getSkyEmptinessMap();
        if (empty == null) return FULL;
        int highest = empty.length - 1;
        while (highest >= 0 && empty[highest]) highest--;
        if (section > highest) return FULL;
        for (int i = section + offset + 1; i < nibbles.length; i++) {
            if (nibbles[i].isNullNibbleVisible()) continue;
            var above = layer(nibbles[i]);
            return above.length == 1 ? above : Arrays.copyOf(above, 128);
        }
        return FULL;
    }
    private static int light(byte[] layer, int x, int y, int z) {
        int n = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        return layer[(n >> 1) & (layer.length - 1)] >>> ((n & 1) << 2) & 15;
    }
    boolean matches(ServerLevel source) {
        if (!world.equals(source.getWorld().getUID()) || source.getSeaLevel() != seaLevel || source.getSkyDarken() != skyDarken) return false;
        var current = capture(source, bounds, limits, false);
        if (current.minSection != minSection) return false;
        for (int i = 0; i < chunks.length; i++) if (!chunks[i].same(current.chunks[i])) return false;
        return true;
    }
    private Chunk chunk(int cx, int cz) { return chunks[(cz - minChunkZ) * width + cx - minChunkX]; }
    private Holder<net.minecraft.world.level.biome.Biome> noise(int x, int y, int z) {
        var sections = chunk(x >> 2, z >> 2).sections;
        int quart = Math.clamp(y - 4 * minSection, 0, sections.length * 4 - 1);
        return sections[quart >> 2].biomes.get(x & 3, quart & 3, z & 3);
    }
    private byte[] layer(Position pos, boolean sky) {
        var section = chunk(pos.x() >> 4, pos.z() >> 4).sections[(pos.y() >> 4) - minSection];
        return sky ? section.sky : section.block;
    }
    Result expand() {
        var builder = new RollbackTerrainSeed.Builder(bounds, limits.maximumStorageBytes());
        var pos = new BlockPos.MutableBlockPos();
        BlockState previous = null;
        Holder<net.minecraft.world.level.biome.Biome> previousNoise = null, previousSmooth = null;
        int previousLight = -1;
        for (int y = bounds.minY(); y < bounds.maxY(); y++) for (int z = bounds.minZ(); z < bounds.maxZ(); z++)
            for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                pos.set(x, y, z);
                var chunk = chunk(x >> 4, z >> 4); var section = chunk.sections[(y >> 4) - minSection];
                var state = section.states.get(x & 15, y & 15, z & 15);
                var noise = noise(x >> 2, y >> 2, z >> 2); var smooth = biomeManager.getBiome(pos);
                int brightness = Math.max(light(section.sky, x, y, z) - skyDarken, light(section.block, x, y, z));
                if (x < -30_000_000 || x >= 30_000_000 || z < -30_000_000 || z >= 30_000_000) brightness = 15;
                double temperature = smooth.value().getTemperature(pos, seaLevel);
                if (state == previous && noise == previousNoise && smooth == previousSmooth && brightness == previousLight && !state.hasBlockEntity()) {
                    builder.repeatAtTemperature(temperature);
                    continue;
                }
                var noiseKey = noise.unwrapKey().orElseThrow().identifier();
                var smoothKey = smooth.unwrapKey().orElseThrow().identifier();
                Biome common = Biome.DESERT;
                if (noiseKey.getNamespace().equals("minecraft")) {
                    try { common = Biome.valueOf(noiseKey.getPath().toUpperCase(Locale.ROOT)); }
                    catch (IllegalArgumentException ignored) { }
                }
                byte[] tile = state.hasBlockEntity() ? Objects.requireNonNull(chunk.tiles.get(pos.asLong()), "Missing captured native block entity at " + pos) : null;
                builder.append(new Cell(states.get(state), tile, common, smoothKey.toString(), noiseKey.toString(), (byte)brightness, temperature,
                        noise.value().climateSettings.downfall()));
                previous = state; previousNoise = noise; previousSmooth = smooth; previousLight = brightness;
            }
        return new Result(builder.finish(), RollbackLightSeed.capture(bounds, limits.maximumCells(),
                p -> light(layer(p, true), p.x(), p.y(), p.z()), p -> light(layer(p, false), p.x(), p.y(), p.z())));
    }
}
