package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Proxy;
import java.util.*;

import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

/** Uses native states/entities and the production capture entry point with a strictly read-only world fixture. */
class PaperRollbackTerrainCaptureNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }
    private static final Bounds BOUNDS = new Bounds(-1, 0, -1, 3, 1, 1);
    private static final PaperRollbackTerrainCapture.Limits LIMITS = new PaperRollbackTerrainCapture.Limits(32, 32, 1_048_576, 65_536);

    @Test void capturesExactTerrainAndExistingAndPackedTilesWithoutKeepingLiveReferences() throws Exception {
        onTickThread(() -> {
            var fixture = new Fixture();
            var slabPos = new BlockPos(0, 0, 0); var packedPos = new BlockPos(1, 0, 0); var chestPos = new BlockPos(-1, 0, -1);
            var firePos = new BlockPos(2, 0, 0);
            fixture.blocks.put(slabPos, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            fixture.blocks.put(firePos, Blocks.FIRE.defaultBlockState().setValue(FireBlock.AGE, 7).setValue(FireBlock.NORTH, true));
            fixture.blocks.put(packedPos, Blocks.CHEST.defaultBlockState());
            var packed = new CompoundTag(); packed.putString("id", "minecraft:chest"); packed.putString("fixture", "before");
            fixture.packed.put(packedPos, packed);
            fixture.blocks.put(chestPos, Blocks.CHEST.defaultBlockState());
            var chest = new ChestBlockEntity(chestPos, Blocks.CHEST.defaultBlockState());
            chest.setItem(0, new ItemStack(Items.DIAMOND, 4)); fixture.entities.put(chestPos, chest);
            var expectedChest = chest.saveWithFullMetadata(fixture.registries);
            var seed = PaperRollbackTerrainCapture.capture(fixture.world, BOUNDS, LIMITS);
            assertEquals(8, seed.cellCount()); assertEquals(5, seed.paletteSize());
            assertEquals(16, fixture.chunkReads); assertEquals(8, fixture.blockReads);
            assertSame(packed, fixture.packed.get(packedPos)); assertEquals(1, fixture.entities.size());
            assertEquals(expectedChest, tag(seed.cell(position(chestPos)).blockEntity()));
            assertEquals(packed, tag(seed.cell(position(packedPos)).blockEntity()));
            assertEquals(CraftBlockData.fromData(fixture.blocks.get(firePos)).getAsString(), seed.cell(position(firePos)).data().getExactState());
            var changedFire = (com.projectkorra.projectkorra.platform.mc.block.data.type.Fire) seed.cell(position(firePos)).data();
            changedFire.setFace(com.projectkorra.projectkorra.platform.mc.block.BlockFace.NORTH, false);
            var decodedFire = new PaperRollbackBlockStates().decode(changedFire);
            assertEquals(7, decodedFire.getValue(FireBlock.AGE)); assertFalse(decodedFire.getValue(FireBlock.NORTH));
            assertEquals("minecraft:desert", seed.cell(position(slabPos)).biomeKey());
            assertEquals("minecraft:plains", seed.cell(position(slabPos)).noiseBiomeKey());
            assertEquals(2.0, seed.cell(position(slabPos)).temperature());
            assertEquals(7, seed.cell(position(slabPos)).light());

            var wireLimits = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec.Limits(32, 32, 1_048_576, 1_048_576, 65_536);
            byte[] encoded = PaperRollbackTerrainTransfer.encode(seed, wireLimits);
            var decoded = PaperRollbackTerrainTransfer.decode(encoded, wireLimits);
            assertArrayEquals(encoded, PaperRollbackTerrainTransfer.encode(decoded, wireLimits));
            assertEquals(expectedChest, tag(decoded.cell(position(chestPos)).blockEntity()));
            assertEquals(fixture.blocks.get(firePos), new PaperRollbackBlockStates().decode(decoded.cell(position(firePos)).data()));
            try (var fixtureBytes = getClass().getResourceAsStream("/rollback/terrain.base64")) {
                String current = Base64.getEncoder().encodeToString(encoded);
                assertNotNull(fixtureBytes, "Current Paper terrain fixture: " + current);
                assertArrayEquals(Base64.getMimeDecoder().decode(fixtureBytes.readAllBytes()), encoded, "Current Paper terrain fixture: " + current);
            }

            fixture.blocks.put(slabPos, Blocks.AIR.defaultBlockState());
            packed.putString("fixture", "after"); chest.getItem(0).setCount(1);
            assertEquals(expectedChest, tag(seed.cell(position(chestPos)).blockEntity()));
            assertEquals("before", tag(seed.cell(position(packedPos)).blockEntity()).getString("fixture").orElseThrow());
            var store = new RollbackBlockStore(new World(), seed, unusedRules(), 20);
            var geometry = new PaperRollbackGeometry(-64, 320, (terrain, position, state) -> { throw new AssertionError("No tile query for slab"); });
            var point = position(slabPos); var before = store.captureRollbackState();
            var shape = geometry.geometry(store, point, store.cell(point).data());
            assertEquals(0.5, shape.collision().getFirst().minY());
            var air = new BlockData(Material.AIR); air.setExactState("minecraft:air");
            store.replace(point, new Cell(air, null, store.cell(point).biome(), "minecraft:plains", (byte) 7, 0.8, 0.4), false);
            assertTrue(geometry.geometry(store, point, store.cell(point).data()).collision().isEmpty());
            store.restoreRollbackState(before);
            assertEquals(shape, geometry.geometry(store, point, store.cell(point).data()));
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void missingChunksAndBudgetsAbortBeforeReadingTerrainAndMissingTilesNeverBecomeEmptyOnes() throws Exception {
        onTickThread(() -> {
            var fixture = new Fixture(); fixture.missing = true;
            assertThrows(IllegalStateException.class, () -> PaperRollbackTerrainCapture.capture(fixture.world, BOUNDS, LIMITS));
            assertEquals(0, fixture.blockReads);
            fixture.missing = false;
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackTerrainCapture.capture(fixture.world, BOUNDS,
                    new PaperRollbackTerrainCapture.Limits(1, 32, 4096, 1024)));
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackTerrainCapture.capture(fixture.world, BOUNDS,
                    new PaperRollbackTerrainCapture.Limits(32, 1, 4096, 1024)));
            assertEquals(0, fixture.blockReads);
            fixture.blocks.put(new BlockPos(-1, 0, -1), Blocks.CHEST.defaultBlockState());
            assertThrows(IllegalStateException.class, () -> PaperRollbackTerrainCapture.capture(fixture.world, BOUNDS, LIMITS));
            assertTrue(fixture.entities.isEmpty());
            var huge = new CompoundTag(); huge.putString("data", "x".repeat(4096)); fixture.packed.put(new BlockPos(-1, 0, -1), huge);
            assertThrows(IllegalStateException.class, () -> PaperRollbackTerrainCapture.capture(fixture.world, BOUNDS,
                    new PaperRollbackTerrainCapture.Limits(32, 32, 8192, 128)));
            try (var clock = RollbackClock.at(1_000, 0, 50_000_000)) {
                assertThrows(IllegalStateException.class, () -> PaperRollbackTerrainCapture.capture(fixture.world, BOUNDS, LIMITS));
            }
            return null;
        });
    }

    @Test void portableTerrainBakesMutableFacadeEditsAndRejectsMismatchedNativeBlocks() {
        var data = new com.projectkorra.projectkorra.platform.mc.block.data.Levelled(Material.WATER);
        data.setExactState("minecraft:water[level=0]"); data.setLevel(8);
        var builder = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainSeed.Builder(new Bounds(0, 0, 0, 1, 1, 1), 4096);
        builder.append(new Cell(data, null, com.projectkorra.projectkorra.platform.mc.block.Biome.DESERT, (byte) 0, 0.8, 0.4));
        var seed = builder.finish();
        var limits = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec.Limits(32, 32, 1_048_576, 1_048_576, 65_536);
        var imported = PaperRollbackTerrainTransfer.decode(PaperRollbackTerrainTransfer.encode(seed, limits), limits);
        var water = assertInstanceOf(com.projectkorra.projectkorra.platform.mc.block.data.Levelled.class, imported.palette().getFirst().data());
        assertEquals(8, water.getLevel()); assertEquals("minecraft:water[level=8]", water.getExactState());
        assertEquals("minecraft:water[level=0]", seed.palette().getFirst().data().getExactState());
        var adapter = new PaperRollbackTerrainTransfer();
        assertThrows(IllegalArgumentException.class, () -> adapter.decode(Material.STONE, "minecraft:missing_rollback_block"));
        assertThrows(IllegalArgumentException.class, () -> adapter.decode(Material.STONE, "minecraft:oak_slab[type=top]"));
    }

    @Test void capturesDistinctLightLayersWithoutLoadingMissingChunks() throws Exception {
        onTickThread(() -> {
            var fixture = new Fixture();
            var seed = PaperRollbackLightCapture.capture(fixture.world, BOUNDS, 8);
            assertEquals(4, fixture.chunkReads); assertEquals(0, fixture.blockReads);
            assertEquals(15, seed.sky(new Position(0, 0, 0)));
            assertEquals(3, seed.sky(new Position(1, 0, 0)));
            assertEquals(10, seed.block(new Position(0, 0, 0)));
            var copy = com.projectkorra.projectkorra.prediction.rollback.world.RollbackLightSeed.decode(seed.encode(), 8);
            assertEquals(3, copy.sky(new Position(1, 0, 0)));
            fixture.missing = true;
            assertThrows(IllegalStateException.class, () -> PaperRollbackLightCapture.capture(fixture.world, BOUNDS, 8));
            assertEquals(15, seed.sky(new Position(0, 0, 0)));
            return null;
        });
    }
    private static Position position(BlockPos position) { return new Position(position.getX(), position.getY(), position.getZ()); }
    private static CompoundTag tag(byte[] bytes) throws Exception {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)), NbtAccounter.create(1_048_576));
    }
    private static Rules unusedRules() {
        return (Rules) Proxy.newProxyInstance(Rules.class.getClassLoader(), new Class<?>[]{Rules.class},
                (proxy, method, args) -> {
                    if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    throw new AssertionError(method);
                });
    }
    private static final class Fixture {
        final RegistryAccess registries = (RegistryAccess) new PaperRollbackDamageNativeTest.Combat().registryAccess();
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Map<BlockPos, BlockEntity> entities = new HashMap<>();
        final Map<BlockPos, CompoundTag> packed = new HashMap<>();
        final ServerLevel world;
        int chunkReads, blockReads;
        boolean missing;
        Fixture() {
            var chunk = RollbackNativeQueryShell.create(LevelChunk.class)
                    .query(value -> value.getBlockState(BlockPos.ZERO), null, args -> { blockReads++; return blocks.getOrDefault(args[0], Blocks.AIR.defaultBlockState()); })
                    .query(LevelChunk::getBlockEntities, null, args -> entities)
                    .query(value -> value.getBlockEntityNbt(BlockPos.ZERO), null, args -> packed.get(args[0])).instance();
            var cache = RollbackNativeQueryShell.create(ServerChunkCache.class)
                    .query(value -> value.getChunkAtIfLoadedImmediately(0, 0), null, args -> { chunkReads++; return missing ? null : chunk; }).instance();
            var biome = registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
            var smoothed = registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.DESERT);
            world = RollbackNativeQueryShell.create(ServerLevel.class)
                    .constant(ServerLevel::getChunkSource, cache).constant(ServerLevel::registryAccess, registries)
                    .constant(ServerLevel::getGameTime, 100L).constant(ServerLevel::getSeaLevel, 63)
                    .query(value -> value.getNoiseBiome(0, 0, 0), null, args -> biome)
                    .query(value -> value.getBiome(BlockPos.ZERO), null, args -> smoothed)
                    .query(value -> value.getBrightness(net.minecraft.world.level.LightLayer.SKY, BlockPos.ZERO), 0,
                            args -> args[0] == net.minecraft.world.level.LightLayer.BLOCK ? 10 : ((BlockPos) args[1]).getX() == 0 ? 15 : 3)
                    .query(value -> value.getMaxLocalRawBrightness(BlockPos.ZERO), 0, args -> 7).instance();
        }
    }
}
