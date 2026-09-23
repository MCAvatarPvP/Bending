package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Fire;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainSeed;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.state.property.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Proxy;
import java.util.Base64;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackTerrainTransferTest {
    private static final RollbackTerrainCodec.Limits LIMITS = new RollbackTerrainCodec.Limits(32, 32, 1_048_576, 1_048_576, 65_536);
    @BeforeAll static void bootstrap() { SharedConstants.createGameVersion(); Bootstrap.initialize(); }

    @Test void importsTheActualPaperCaptureWithMatchingShapesFirePropertiesAndChestContents() throws Exception {
        byte[] bytes;
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/terrain.base64"))) {
            bytes = Base64.getMimeDecoder().decode(resource.readAllBytes());
        }
        var seed = FabricRollbackTerrainTransfer.decode(bytes, LIMITS);
        assertArrayEquals(bytes, FabricRollbackTerrainTransfer.encode(seed, LIMITS));
        assertEquals(new Bounds(-1, 0, -1, 3, 1, 1), seed.bounds());
        assertEquals(8, seed.cellCount()); assertEquals(5, seed.paletteSize());
        var chest = tag(seed.cell(new Position(-1, 0, -1)).blockEntity());
        assertEquals("minecraft:chest", chest.getString("id").orElseThrow());
        var item = chest.getList("Items").orElseThrow().getCompound(0).orElseThrow();
        assertEquals("minecraft:diamond", item.getString("id").orElseThrow()); assertEquals(4, item.getInt("count").orElseThrow());
        assertEquals("before", tag(seed.cell(new Position(1, 0, 0)).blockEntity()).getString("fixture").orElseThrow());
        var cell = seed.cell(new Position(2, 0, 0)); var fire = assertInstanceOf(Fire.class, cell.data());
        assertTrue(fire.hasFace(BlockFace.NORTH));
        assertEquals(7, FabricRollbackGeometry.decode(fire).get(Properties.AGE_15));
        fire.setFace(BlockFace.NORTH, false);
        assertEquals(7, FabricRollbackGeometry.decode(fire).get(Properties.AGE_15));
        assertFalse(FabricRollbackGeometry.decode(fire).get(Properties.NORTH));
        assertEquals("minecraft:desert", cell.biomeKey()); assertEquals("minecraft:plains", cell.noiseBiomeKey());
        assertEquals(2.0, cell.temperature()); assertEquals(7, cell.light());

        var unused = (Rules) Proxy.newProxyInstance(Rules.class.getClassLoader(), new Class<?>[]{Rules.class}, (proxy, method, args) -> { throw new AssertionError(method); });
        var terrain = new RollbackBlockStore(new World(), seed, unused, 32);
        var shapes = new FabricRollbackGeometry(-64, 320, (store, position, state) -> { throw new AssertionError("No tile query for slab"); });
        var position = new Position(0, 0, 0); var before = terrain.captureRollbackState();
        var initial = shapes.geometry(terrain, position, terrain.cell(position).data());
        assertEquals(0.5, initial.collision().getFirst().minY());
        var air = new BlockData(Material.AIR); air.setExactState("minecraft:air");
        terrain.replace(position, new Cell(air, null, Biome.DESERT, (byte) 7, 2.0, 0.4), false);
        assertTrue(shapes.geometry(terrain, position, terrain.cell(position).data()).collision().isEmpty());
        terrain.restoreRollbackState(before);
        assertEquals(initial, shapes.geometry(terrain, position, terrain.cell(position).data()));
    }

    @Test void mutableFacadeEditsAreBakedIntoTheWireWithoutChangingTheOriginalSeed() {
        var data = new Levelled(Material.WATER); data.setExactState("minecraft:water[level=0]"); data.setLevel(8);
        var builder = new RollbackTerrainSeed.Builder(new Bounds(0, 0, 0, 1, 1, 1), 4096);
        builder.append(new Cell(data, null, Biome.DESERT, (byte) 0, 0.8, 0.4));
        var seed = builder.finish();
        var imported = FabricRollbackTerrainTransfer.decode(FabricRollbackTerrainTransfer.encode(seed, LIMITS), LIMITS);
        var water = assertInstanceOf(Levelled.class, imported.palette().getFirst().data());
        assertEquals(8, water.getLevel()); assertEquals("minecraft:water[level=8]", water.getExactState());
        assertEquals("minecraft:water[level=0]", seed.palette().getFirst().data().getExactState());
    }

    @Test void missingOrMismatchedNativeStateCannotTurnIntoDefaultTerrain() {
        var adapter = new FabricRollbackTerrainTransfer();
        assertThrows(IllegalArgumentException.class, () -> adapter.decode(Material.STONE, "minecraft:missing_rollback_block"));
        assertThrows(IllegalArgumentException.class, () -> adapter.decode(Material.STONE, "minecraft:oak_slab[type=top]"));
    }

    private static NbtCompound tag(byte[] bytes) throws Exception {
        return NbtIo.readCompound(new DataInputStream(new ByteArrayInputStream(bytes)), NbtSizeTracker.of(65_536));
    }
}
