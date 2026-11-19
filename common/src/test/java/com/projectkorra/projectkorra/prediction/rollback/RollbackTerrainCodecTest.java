package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainSeed;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RollbackTerrainCodecTest {
    private static final RollbackTerrainCodec.Limits LIMITS = new RollbackTerrainCodec.Limits(4096, 128, 65536, 131072, 4096);
    private static final RollbackTerrainCodec.BlockStates STATES = new RollbackTerrainCodec.BlockStates() {
        @Override public String encode(BlockData data) { return data.getExactState(); }
        @Override public BlockData decode(Material material, String exact) { var data = new BlockData(material); data.setExactState(exact); return data; }
    };

    @Test void paletteRunsPreserveCompleteCoordinatesEnvironmentAndOwnedTileBytes() {
        var bounds = new Bounds(-2, -1, -1, 2, 1, 1);
        var builder = new RollbackTerrainSeed.Builder(bounds, LIMITS.maximumStorageBytes());
        var stone = cell(Material.STONE, "minecraft:stone", null);
        byte[] tile = {10, 0, 1, 2, 3}; var chest = cell(Material.CHEST, "minecraft:chest[facing=west]", tile);
        builder.append(stone, 3); builder.append(chest, 9); builder.append(stone, 4);
        var seed = builder.finish(); byte[] bytes = RollbackTerrainCodec.encode(seed, STATES, LIMITS);
        var decoded = RollbackTerrainCodec.decode(bytes, STATES, LIMITS);
        assertEquals(bounds, decoded.bounds()); assertEquals(16, decoded.cellCount()); assertEquals(2, decoded.paletteSize());
        assertArrayEquals(bytes, RollbackTerrainCodec.encode(decoded, STATES, LIMITS));
        for (int y = -1; y < 1; y++) for (int z = -1; z < 1; z++) for (int x = -2; x < 2; x++) {
            var expected = seed.cell(new Position(x, y, z)); var actual = decoded.cell(new Position(x, y, z));
            assertEquals(expected.data().getAsString(), actual.data().getAsString());
            assertEquals(expected.biome(), actual.biome()); assertEquals(expected.biomeKey(), actual.biomeKey());
            assertEquals(expected.noiseBiomeKey(), actual.noiseBiomeKey()); assertEquals(expected.light(), actual.light());
            assertEquals(expected.temperature(), actual.temperature()); assertEquals(expected.humidity(), actual.humidity());
            assertArrayEquals(expected.blockEntity(), actual.blockEntity());
        }
        Arrays.fill(bytes, (byte) 0); tile[0] = 0; decoded.palette().get(1).blockEntity()[0] = 0;
        assertArrayEquals(new byte[]{10, 0, 1, 2, 3}, decoded.palette().get(1).blockEntity());
        assertThrows(UnsupportedOperationException.class, () -> decoded.palette().clear());
    }

    @Test void largeUniformRegionsAreCompactButCannotExceedTheDecodedStorageBudget() {
        var builder = new RollbackTerrainSeed.Builder(new Bounds(0, 0, 0, 16, 8, 16), LIMITS.maximumStorageBytes());
        builder.append(cell(Material.AIR, "minecraft:air", null), 2048);
        var seed = builder.finish(); byte[] bytes = RollbackTerrainCodec.encode(seed, STATES, LIMITS);
        assertTrue(bytes.length < 256); assertEquals(2048, RollbackTerrainCodec.decode(bytes, STATES, LIMITS).cellCount());
        var tooSmall = new RollbackTerrainCodec.Limits(4096, 128, 65536, 4096, 4096);
        assertThrows(IllegalArgumentException.class, () -> RollbackTerrainCodec.decode(bytes, STATES, tooSmall));
        assertThrows(IllegalArgumentException.class, () -> RollbackTerrainCodec.encode(seed, STATES, tooSmall));
        var tinyWire = new RollbackTerrainCodec.Limits(4096, 128, 40, 131072, 40);
        assertThrows(IllegalArgumentException.class, () -> RollbackTerrainCodec.encode(seed, STATES, tinyWire));
    }

    @Test void truncatedTrailingMalformedAndIncompleteRunsFailBeforeNativeDecoding() {
        var builder = new RollbackTerrainSeed.Builder(new Bounds(0, 0, 0, 2, 1, 1), 4096);
        builder.append(cell(Material.STONE, "minecraft:stone", null), 2);
        byte[] bytes = RollbackTerrainCodec.encode(builder.finish(), STATES, LIMITS);
        var calls = new AtomicInteger();
        var nativeStates = new RollbackTerrainCodec.BlockStates() {
            @Override public String encode(BlockData data) { throw new AssertionError(); }
            @Override public BlockData decode(Material material, String exact) { calls.incrementAndGet(); return STATES.decode(material, exact); }
        };
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThrows(IllegalArgumentException.class, () -> RollbackTerrainCodec.decode(truncated, nativeStates, LIMITS));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackTerrainCodec.decode(Arrays.copyOf(bytes, bytes.length + 1), nativeStates, LIMITS));
        for (int[] change : new int[][]{{0, 2}, {28, 3}, {32, 0}, {bytes.length - 12, 0},
                {bytes.length - 8, 0}, {bytes.length - 8, 1}, {bytes.length - 8, 3}, {bytes.length - 4, 1}}) {
            byte[] invalid = bytes.clone(); ByteBuffer.wrap(invalid).putInt(change[0], change[1]);
            assertThrows(IllegalArgumentException.class, () -> RollbackTerrainCodec.decode(invalid, nativeStates, LIMITS));
        }
        byte[] invalidUtf8 = bytes.clone(); invalidUtf8[38] = (byte) 0xC0;
        assertThrows(IllegalArgumentException.class, () -> RollbackTerrainCodec.decode(invalidUtf8, nativeStates, LIMITS));
        assertEquals(0, calls.get());
    }

    @Test void varyingTemperaturesUseOneDiscretePaletteEntryAndRoundTripExactly() {
        int count = 131072;
        var bounds = new Bounds(0, 0, 0, 128, 8, 128);
        var limits = new RollbackTerrainCodec.Limits(count, 1, count * 10, count * 16L, 1024);
        var block = cell(Material.AIR, "minecraft:air", null);
        for (boolean precise : new boolean[]{false, true}) {
            var builder = new RollbackTerrainSeed.Builder(bounds, limits.maximumStorageBytes());
            builder.append(block);
            for (int i = 1; i < count; i++) builder.repeatAtTemperature(precise ? Math.scalb((double)i, -30) + .3 : (float)(.3 + i * .000001));
            var seed = builder.finish();
            assertEquals(1, seed.paletteSize());
            var bytes = RollbackTerrainCodec.encode(seed, STATES, limits);
            var copy = RollbackTerrainCodec.decode(bytes, STATES, limits);
            assertTrue(bytes.length < count * (precise ? Double.BYTES : Float.BYTES));
            for (int i = 0; i < count; i++) {
                var pos = new Position(i % 128, i / 16384, (i / 128) % 128);
                assertEquals(Double.doubleToRawLongBits(seed.cell(pos).temperature()),
                        Double.doubleToRawLongBits(copy.cell(pos).temperature()));
            }
        }
    }

    @Test void malformedClimateStreamsAndStorageBoundsFailBeforeNativeDecoding() {
        var builder = new RollbackTerrainSeed.Builder(new Bounds(0, 0, 0, 128, 1, 1), 4096);
        builder.append(cell(Material.STONE, "minecraft:stone", null));
        for (int i = 1; i < 128; i++) builder.repeatAtTemperature(i / 701.0);
        var seed = builder.finish();
        byte[] bytes = RollbackTerrainCodec.encode(seed, STATES, LIMITS);
        var tooSmall = new RollbackTerrainCodec.Limits(128, 1, 4096, 1500, 128);
        assertThrows(IllegalArgumentException.class, () -> RollbackTerrainCodec.encode(seed, STATES, tooSmall));
        var decoder = new RollbackTerrainCodec.BlockStates() {
            @Override public String encode(BlockData data) { throw new AssertionError(); }
            @Override public BlockData decode(Material material, String exact) { throw new AssertionError("Unsafe native decode"); }
        };
        assertThrows(IllegalArgumentException.class, () -> RollbackTerrainCodec.decode(bytes, decoder, tooSmall));
        var compressed = bytes.clone();
        // One final discrete run occupies twelve bytes, immediately following the checksummed climate stream.
        compressed[compressed.length - 13] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> RollbackTerrainCodec.decode(compressed, decoder, LIMITS));
        assertThrows(IllegalArgumentException.class, () -> RollbackTerrainCodec.decode(Arrays.copyOf(bytes, bytes.length - 2), decoder, LIMITS));
    }

    @Test void tileBytesAndNativeMaterialIdentityCannotBypassBudgets() {
        var builder = new RollbackTerrainSeed.Builder(new Bounds(0, 0, 0, 1, 1, 1), 16384);
        builder.append(cell(Material.CHEST, "minecraft:chest", new byte[4097]));
        var seed = builder.finish();
        assertThrows(IllegalArgumentException.class, () -> RollbackTerrainCodec.encode(seed, STATES, LIMITS));
        var larger = new RollbackTerrainCodec.Limits(4096, 128, 65536, 131072, 8192);
        byte[] bytes = RollbackTerrainCodec.encode(seed, STATES, larger);
        assertThrows(IllegalArgumentException.class, () -> RollbackTerrainCodec.decode(bytes, STATES, LIMITS));
        var mismatched = new RollbackTerrainCodec.BlockStates() {
            @Override public String encode(BlockData data) { return data.getExactState(); }
            @Override public BlockData decode(Material material, String exact) { return STATES.decode(Material.AIR, exact); }
        };
        assertThrows(IllegalArgumentException.class, () -> RollbackTerrainCodec.decode(bytes, mismatched, larger));
    }

    private static Cell cell(Material material, String exact, byte[] tile) {
        return new Cell(STATES.decode(material, exact), tile, Biome.DESERT, "custom:smoothed", "minecraft:plains", (byte) 7, 2.0, 0.4);
    }
}
