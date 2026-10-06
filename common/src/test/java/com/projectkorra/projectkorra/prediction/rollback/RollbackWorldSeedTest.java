package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RollbackWorldSeedTest {
    static final RollbackTerrainCodec.Limits LIMITS = new RollbackTerrainCodec.Limits(256, 16, 65_536, 65_536, 4_096);
    static final RollbackTerrainCodec.BlockStates STATES = new RollbackTerrainCodec.BlockStates() {
        @Override public String encode(BlockData data) { return data.getExactState(); }
        @Override public BlockData decode(Material material, String exact) { var data = material.createBlockData(); data.setExactState(exact); return data; }
    };
    static RollbackWorldSeed seed(long tick) {
        var policy = new RollbackWorldSettings.Policy(RollbackWorldSettings.Dimension.NORMAL, true, false, true, false, false, 8,
                .05f, .2f, 6, 1, true, true, 4, 0, OptionalInt.empty());
        var settings = new RollbackWorldSettings(tick, 1234, 5678, 63, RollbackWorldSettings.Difficulty.HARD, policy,
                Map.of("minecraft:fire_damage", new RollbackWorldSettings.Flag(true)));
        var border = new RollbackBorderData(20, 30, 29_999_984, .2, 5, 15, 5, 200,
                new RollbackBorderData.Transition(200, 100, 200, 50, 150, 190));
        var environment = new RollbackEnvironmentData("minecraft:overworld", true, new RollbackEnvironmentData.Weather(.8f, .4f));
        var builder = new RollbackTerrainSeed.Builder(new RollbackBlockStore.Bounds(-17, -2, -1, 1, 0, 1), 65_536);
        var stone = Material.STONE.createBlockData(); stone.setExactState("minecraft:stone");
        builder.append(new RollbackBlockStore.Cell(stone, null, Biome.DESERT, "minecraft:plains", "minecraft:desert", (byte) 10, .8, .4), 72);
        return new RollbackWorldSeed(new UUID(0, 99), "duel-\u6c34", -2, 0, -1, true, settings, border, environment, builder.finish());
    }

    @Test void bothReplicasReceiveTheSameWorldClockRulesBorderWeatherAndCollisionData() {
        var seed = seed(20); byte[] bytes = seed.encode(STATES, LIMITS);
        var copy = RollbackWorldSeed.decode(bytes, STATES, LIMITS);
        assertArrayEquals(bytes, copy.encode(STATES, LIMITS));
        assertEquals(seed.world(), copy.world()); assertEquals(seed.identity(), copy.identity());
        assertEquals(seed.settings(), copy.settings()); assertEquals(seed.border(), copy.border()); assertEquals(seed.environment(), copy.environment());
        assertEquals(23_999, copy.conditions().time()); assertEquals(-1, copy.conditions().fullTime());
        assertEquals("HARD", copy.conditions().difficulty()); assertTrue(copy.conditions().storm());
        assertEquals(Set.of(new RollbackWorld.Chunk(-2, -1), new RollbackWorld.Chunk(-2, 0),
                new RollbackWorld.Chunk(-1, -1), new RollbackWorld.Chunk(-1, 0), new RollbackWorld.Chunk(0, -1), new RollbackWorld.Chunk(0, 0)), copy.conditions().loadedChunks());
        var position = new RollbackBlockStore.Position(-17, -2, -1);
        copy.terrain().cell(position).data().setExactState("minecraft:air");
        assertEquals("minecraft:stone", copy.terrain().cell(position).data().getExactState());
        assertEquals("minecraft:plains", copy.terrain().cell(position).biomeKey());
        assertEquals("minecraft:desert", copy.terrain().cell(position).noiseBiomeKey());
    }

    @Test void nativeLightLayersTravelWithExactTerrainBoundsAndCannotBeInventedForLegacySeeds() {
        var legacy = seed(20);
        assertThrows(IllegalStateException.class, legacy::requireLight);
        var light = RollbackLightSeed.capture(legacy.terrain().bounds(), 256, position -> 3, position -> 15);
        var current = new RollbackWorldSeed(legacy.world(), legacy.name(), legacy.minimumY(), legacy.maximumY(),
                legacy.dayTime(), legacy.storm(), legacy.settings(), legacy.border(), legacy.environment(), legacy.terrain(), light);
        byte[] bytes = current.encode(STATES, LIMITS);
        assertEquals(2, ByteBuffer.wrap(bytes).getInt());
        var copy = RollbackWorldSeed.decode(bytes, STATES, LIMITS);
        assertArrayEquals(bytes, copy.encode(STATES, LIMITS));
        var position = new RollbackBlockStore.Position(-17, -2, -1);
        assertEquals(3, copy.requireLight().sky(position)); assertEquals(15, copy.requireLight().block(position));
        byte[] oldBytes = legacy.encode(STATES, LIMITS);
        assertEquals(1, ByteBuffer.wrap(oldBytes).getInt());
        assertThrows(IllegalStateException.class, () -> RollbackWorldSeed.decode(oldBytes, STATES, LIMITS).requireLight());
        for (int cut = bytes.length - light.encode().length - 4; cut < bytes.length; cut++) {
            byte[] truncated = Arrays.copyOf(bytes, cut);
            assertThrows(IllegalArgumentException.class, () -> RollbackWorldSeed.decode(truncated, STATES, LIMITS));
        }
        var wrong = RollbackLightSeed.capture(new RollbackBlockStore.Bounds(0, -2, 0, 1, 0, 1), 2, p -> 0, p -> 0);
        assertThrows(IllegalArgumentException.class, () -> new RollbackWorldSeed(legacy.world(), legacy.name(), legacy.minimumY(),
                legacy.maximumY(), legacy.dayTime(), legacy.storm(), legacy.settings(), legacy.border(), legacy.environment(), legacy.terrain(), wrong));
    }
    @Test void truncatedNestedLengthsInvalidFlagsAndUtf8AreRejected() {
        var seed = seed(20); byte[] bytes = seed.encode(STATES, LIMITS);
        for (int i = 0; i < bytes.length; i++) {
            byte[] cut = Arrays.copyOf(bytes, i);
            assertThrows(IllegalArgumentException.class, () -> RollbackWorldSeed.decode(cut, STATES, LIMITS));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackWorldSeed.decode(Arrays.copyOf(bytes, bytes.length + 1), STATES, LIMITS));
        byte[] length = bytes.clone(); ByteBuffer.wrap(length).putInt(20, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> RollbackWorldSeed.decode(length, STATES, LIMITS));
        byte[] flag = bytes.clone(); flag[40 + seed.name().getBytes(StandardCharsets.UTF_8).length] = 2;
        assertThrows(IllegalArgumentException.class, () -> RollbackWorldSeed.decode(flag, STATES, LIMITS));
        byte[] text = bytes.clone(); text[24] = (byte) 0xc0;
        assertThrows(IllegalArgumentException.class, () -> RollbackWorldSeed.decode(text, STATES, LIMITS));
    }

    @Test void partialColumnsAndInvalidNamesCannotBecomeANativeWorld() {
        var seed = seed(20);
        assertThrows(IllegalArgumentException.class, () -> new RollbackWorldSeed(seed.world(), seed.name(), -64, 320, seed.dayTime(), true,
                seed.settings(), seed.border(), seed.environment(), seed.terrain()));
        for (String name : List.of("", " ", "\ud800", "\u6c34".repeat(400))) {
            assertThrows(IllegalArgumentException.class, () -> new RollbackWorldSeed(seed.world(), name, -2, 0, -1, true,
                    seed.settings(), seed.border(), seed.environment(), seed.terrain()));
        }
    }
}
