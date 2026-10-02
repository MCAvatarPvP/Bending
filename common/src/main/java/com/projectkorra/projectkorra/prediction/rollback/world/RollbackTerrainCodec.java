package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Cell;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared detached arena format. Native adapters bake facade edits and validate registry state on import. */
public final class RollbackTerrainCodec {
    public static final int VERSION = 1;
    public record Limits(int maximumCells, int maximumPalette, int maximumWireBytes, long maximumStorageBytes, int maximumBlockEntityBytes) {
        public Limits {
            if (maximumCells < 1 || maximumCells > 16_777_216 || maximumPalette < 1 || maximumPalette > maximumCells
                    || maximumWireBytes < 40 || maximumWireBytes > 134_217_728 || maximumStorageBytes < 4 || maximumStorageBytes > 1_073_741_824L
                    || maximumBlockEntityBytes < 1 || maximumBlockEntityBytes > maximumWireBytes) throw new IllegalArgumentException("Terrain wire limits");
        }
    }
    public interface BlockStates {
        /** Full native state after applying the facade's mutable properties. */
        String encode(BlockData data);
        /** Restore typed common properties as well as the exact state, rejecting missing/mismatched native blocks. */
        BlockData decode(Material material, String exact);
    }
    private RollbackTerrainCodec() { }

    public static byte[] encode(RollbackTerrainSeed seed, BlockStates states, Limits limits) {
        Objects.requireNonNull(seed); Objects.requireNonNull(states); Objects.requireNonNull(limits);
        if (seed.cellCount() > limits.maximumCells() || seed.paletteSize() > limits.maximumPalette()
                || (long) seed.cellCount() * Integer.BYTES > limits.maximumStorageBytes()) throw new IllegalArgumentException("Terrain exceeds wire/storage budget");
        var bytes = new ByteArrayOutputStream(Math.min(limits.maximumWireBytes(), 8192));
        var bounded = new OutputStream() {
            private void room(int count) { if (count > limits.maximumWireBytes() - bytes.size()) throw new IllegalArgumentException("Terrain exceeds wire byte budget"); }
            @Override public void write(int value) { room(1); bytes.write(value); }
            @Override public void write(byte[] value, int offset, int length) { room(length); bytes.write(value, offset, length); }
        };
        try {
            var out = new DataOutputStream(bounded); out.writeInt(VERSION);
            var bounds = seed.bounds(); out.writeInt(bounds.minX()); out.writeInt(bounds.minY()); out.writeInt(bounds.minZ());
            out.writeInt(bounds.maxX()); out.writeInt(bounds.maxY()); out.writeInt(bounds.maxZ());
            out.writeInt(seed.cellCount()); out.writeInt(seed.paletteSize());
            long storage = (long) seed.cellCount() * Integer.BYTES;
            for (Cell cell : seed.palette()) {
                var data = cell.data(); String exact = Objects.requireNonNull(states.encode(data), "native state");
                byte[] tile = cell.blockEntity();
                if (tile != null && tile.length > limits.maximumBlockEntityBytes()) throw new IllegalArgumentException("Terrain block entity byte budget");
                storage = Math.addExact(storage, cost(exact, cell.biomeKey(), cell.noiseBiomeKey(), tile));
                if (storage > limits.maximumStorageBytes()) throw new IllegalArgumentException("Terrain palette storage budget");
                string(out, data.getMaterial().name(), 128); string(out, exact, 4096); string(out, cell.biome().name(), 128);
                string(out, cell.biomeKey(), 512); string(out, cell.noiseBiomeKey(), 512);
                out.writeByte(cell.light()); out.writeDouble(cell.temperature()); out.writeDouble(cell.humidity());
                out.writeInt(tile == null ? -1 : tile.length); if (tile != null) out.write(tile);
            }
            int runs = 0;
            for (int i = 0; i < seed.cellCount(); i++) if (i == 0 || seed.paletteIndex(i) != seed.paletteIndex(i - 1)) runs++;
            out.writeInt(runs);
            for (int i = 0; i < seed.cellCount();) {
                int index = seed.paletteIndex(i), end = i + 1;
                while (end < seed.cellCount() && seed.paletteIndex(end) == index) end++;
                out.writeInt(end - i); out.writeInt(index); i = end;
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    public static RollbackTerrainSeed decode(byte[] bytes, BlockStates states, Limits limits) {
        Objects.requireNonNull(bytes); Objects.requireNonNull(states); Objects.requireNonNull(limits);
        if (bytes.length > limits.maximumWireBytes()) throw new IllegalArgumentException("Terrain exceeds wire byte budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) throw new IllegalArgumentException("Unsupported terrain version");
            var bounds = new Bounds(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
            int cells = in.readInt(), entries = in.readInt();
            long volume = ((long) bounds.maxX() - bounds.minX()) * ((long) bounds.maxY() - bounds.minY()) * ((long) bounds.maxZ() - bounds.minZ());
            if (cells != volume || cells > limits.maximumCells() || entries < 1 || entries > limits.maximumPalette() || entries > cells
                    || (long) cells * Integer.BYTES + entries * 128L > limits.maximumStorageBytes() || entries > in.available() / 31) {
                throw new IllegalArgumentException("Terrain header/storage bounds");
            }
            // Parse the complete detached payload before calling any native decoder or allocating the region index.
            var palette = new ArrayList<WireCell>(entries); long storage = (long) cells * Integer.BYTES;
            for (int i = 0; i < entries; i++) {
                var material = Material.valueOf(string(in, 128)); String exact = string(in, 4096);
                var biome = Biome.valueOf(string(in, 128)); String smooth = string(in, 512), noise = string(in, 512);
                byte light = in.readByte(); double temperature = in.readDouble(), humidity = in.readDouble(); int length = in.readInt();
                if (length < -1 || length > limits.maximumBlockEntityBytes() || length > in.available()) throw new IllegalArgumentException("Terrain tile bounds");
                byte[] tile = length < 0 ? null : in.readNBytes(length);
                storage = Math.addExact(storage, cost(exact, smooth, noise, tile));
                if (storage > limits.maximumStorageBytes()) throw new IllegalArgumentException("Terrain palette storage budget");
                var validation = new BlockData(material); validation.setExactState(exact);
                if (!exact.equals(validation.getExactState())) throw new IllegalArgumentException("Terrain state is blank or padded");
                new Cell(validation, tile, biome, smooth, noise, light, temperature, humidity);
                palette.add(new WireCell(material, exact, biome, smooth, noise, light, temperature, humidity, tile));
            }
            int runs = in.readInt();
            if (runs < 1 || runs > cells || (long) runs * 8 != in.available()) throw new IllegalArgumentException("Terrain run/trailing-data bounds");
            // Two passes over the wire bytes avoid an additional per-cell/run allocation.
            in.mark(in.available()); long total = 0; int previous = -1, nextPalette = 0;
            for (int i = 0; i < runs; i++) {
                int count = in.readInt(), index = in.readInt();
                if (count < 1 || count > cells - total || index < 0 || index >= entries || index == previous || index > nextPalette) {
                    throw new IllegalArgumentException("Invalid terrain run or palette order");
                }
                if (index == nextPalette) nextPalette++;
                previous = index; total += count;
            }
            if (total != cells || nextPalette != entries) throw new IllegalArgumentException("Incomplete terrain region or unused palette");
            var decoded = new ArrayList<Cell>(entries);
            for (var cell : palette) {
                var data = Objects.requireNonNull(states.decode(cell.material, cell.exact), "native block data");
                if (data.getMaterial() != cell.material || !cell.exact.equals(data.getExactState())) throw new IllegalArgumentException("Native terrain decoder changed state identity");
                decoded.add(new Cell(data, cell.tile, cell.biome, cell.smooth, cell.noise, cell.light, cell.temperature, cell.humidity));
            }
            var builder = new RollbackTerrainSeed.Builder(bounds, limits.maximumStorageBytes());
            in.reset();
            for (int i = 0; i < runs; i++) { int count = in.readInt(), index = in.readInt(); builder.append(decoded.get(index), count); }
            return builder.finish();
        } catch (IOException malformed) { throw new IllegalArgumentException("Truncated terrain payload", malformed); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("Terrain dimensions/storage overflow", overflow); }
    }

    private record WireCell(Material material, String exact, Biome biome, String smooth, String noise,
                            byte light, double temperature, double humidity, byte[] tile) { }
    private static long cost(String exact, String smooth, String noise, byte[] tile) {
        return 128L + exact.getBytes(StandardCharsets.UTF_8).length + smooth.getBytes(StandardCharsets.UTF_8).length
                + noise.getBytes(StandardCharsets.UTF_8).length + (tile == null ? 0 : tile.length);
    }
    private static void string(DataOutputStream out, String value, int maximum) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > maximum) throw new IllegalArgumentException("Terrain string bounds");
        out.writeShort(bytes.length); out.write(bytes);
    }
    private static String string(DataInputStream in, int maximum) throws IOException {
        int count = in.readUnsignedShort();
        if (count < 1 || count > maximum || count > in.available()) throw new IllegalArgumentException("Terrain string bounds");
        byte[] bytes = in.readNBytes(count);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException malformed) { throw new IllegalArgumentException("Malformed terrain UTF-8", malformed); }
    }
}
