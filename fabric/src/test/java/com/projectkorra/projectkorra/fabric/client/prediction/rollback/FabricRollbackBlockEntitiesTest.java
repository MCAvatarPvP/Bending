package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackBlockEntitiesTest {
    private static final Position POS = new Position(-1, 0, -1);
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void mutationsReachLogicalSnapshotsAndDirtyCellsAndRewindInPlace() throws Exception {
        var tiles = new FabricRollbackBlockEntities(FabricRollbackTestRegistry.simulationRegistries());
        var terrain = terrain(tiles);
        var cell = terrain.cell(POS);
        var original = tag(cell.blockEntity());
        original.putString("PaperExtra", "owned by server");
        terrain.replace(POS, withTile(cell, bytes(original)), false);
        terrain.drainChanges();

        var chest = chest(tiles, terrain);
        assertEquals(4, chest.getStack(0).getCount());
        assertTrue(terrain.drainChanges().isEmpty());
        var originalStack = chest.getStack(0);
        var start = terrain.captureRollbackState();
        chest.onSyncedBlockEvent(1, 1);
        ChestBlockEntity.clientTick(null, chest.getPos(), chest.getCachedState(), chest);
        assertTrue(chest.getAnimationProgress(1) > 0);
        var block = terrain.block(POS.x(), POS.y(), POS.z());

        chest.getStack(0).setCount(9);
        var saved = block.getState();
        var dirty = terrain.drainChanges();
        assertEquals(1, dirty.size());
        var edited = tag(dirty.getFirst().value().blockEntity());
        assertEquals(9, edited.getListOrEmpty("Items").getCompound(0).orElseThrow().getInt("count").orElseThrow());
        assertEquals("owned by server", edited.getString("PaperExtra").orElseThrow());
        assertTrue(terrain.drainChanges().isEmpty());

        chest.setStack(0, ItemStack.EMPTY);
        var propertyChange = cell.data();
        var facing = FabricRollbackGeometry.decode(propertyChange).cycle(net.minecraft.state.property.Properties.HORIZONTAL_FACING);
        propertyChange = com.projectkorra.projectkorra.platform.fabric.FabricMC.blockData(facing);
        block.setBlockData(propertyChange, false);
        assertSame(chest, chest(tiles, terrain));
        assertSame(facing, chest.getCachedState());
        assertTrue(chest.getStack(0).isEmpty());

        saved.update(true, false);
        var restoredSaved = chest(tiles, terrain);
        assertNotSame(chest, restoredSaved);
        assertEquals(9, restoredSaved.getStack(0).getCount());
        restoredSaved.setStack(0, new ItemStack(Items.GOLD_INGOT, 12));
        terrain.restoreRollbackState(start);
        assertSame(chest, chest(tiles, terrain));
        assertSame(originalStack, chest.getStack(0));
        assertEquals(0, chest.getAnimationProgress(1));
        assertEquals(4, chest.getStack(0).getCount());
        assertTrue(chest.getStack(0).isOf(Items.DIAMOND));
        assertTrue(terrain.drainChanges().isEmpty());
        restoredSaved.setStack(0, ItemStack.EMPTY);
        assertEquals(4, chest.getStack(0).getCount());
    }

    @Test void createsNewAndPackedTilesAndRejectsWrongPositionOrType() throws Exception {
        var tiles = new FabricRollbackBlockEntities(FabricRollbackTestRegistry.simulationRegistries());
        var terrain = terrain(tiles);
        var cell = terrain.cell(POS);
        var captured = tag(cell.blockEntity());
        captured.putInt("z", 99);
        terrain.replace(POS, withTile(cell, bytes(captured)), false);
        assertThrows(IllegalArgumentException.class, () -> chest(tiles, terrain));
        captured.putInt("z", POS.z());
        captured.putString("id", "minecraft:barrel");
        terrain.replace(POS, withTile(cell, bytes(captured)), false);
        assertThrows(IllegalArgumentException.class, () -> chest(tiles, terrain));
        captured.putString("id", "DUMMY");
        terrain.replace(POS, withTile(cell, bytes(captured)), false);
        var packed = chest(tiles, terrain);
        assertTrue(packed.getStack(0).isEmpty());
        packed.setStack(0, new ItemStack(Items.DIAMOND));
        assertEquals("minecraft:chest", tag(terrain.cell(POS).blockEntity()).getString("id").orElseThrow());
        terrain.replace(POS, withTile(cell, new byte[0]), false);
        assertTrue(chest(tiles, terrain).getStack(0).isEmpty());
        terrain.replace(POS, withTile(cell, null), false);
        assertNull(chest(tiles, terrain));
        terrain.block(POS.x(), POS.y(), POS.z()).setBlockData(cell.data(), false);
        assertFalse(terrain.cell(POS).hasBlockEntity());
        assertFalse(terrain.blockEntityPositions().contains(POS));
    }

    private static ChestBlockEntity chest(FabricRollbackBlockEntities tiles, RollbackBlockStore terrain) {
        return (ChestBlockEntity) tiles.get(terrain, POS, FabricRollbackGeometry.decode(terrain.cell(POS).data()));
    }
    private static Cell withTile(Cell cell, byte[] bytes) {
        return new Cell(cell.data(), bytes, cell.biome(), cell.biomeKey(), cell.noiseBiomeKey(), cell.light(), cell.temperature(), cell.humidity());
    }
    private static RollbackBlockStore terrain(FabricRollbackBlockEntities tiles) throws IOException {
        var limits = new RollbackTerrainCodec.Limits(32, 32, 1_048_576, 1_048_576, 65_536);
        try (var in = Objects.requireNonNull(FabricRollbackBlockEntitiesTest.class.getResourceAsStream("/rollback/terrain.base64"))) {
            var seed = FabricRollbackTerrainTransfer.decode(Base64.getMimeDecoder().decode(in.readAllBytes()), limits);
            var rules = (Rules) Proxy.newProxyInstance(Rules.class.getClassLoader(), new Class<?>[]{Rules.class}, (proxy, method, args) -> {
                if (method.getName().equals("geometry")) return new Geometry(true, false, false,
                        ((BlockData) args[2]).getMaterial() == Material.CHEST, null, List.of(), List.of());
                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                throw new AssertionError(method);
            });
            var terrain = new RollbackBlockStore(new World(), seed, rules, 32);
            tiles.bind(terrain);
            return terrain;
        }
    }
    private static NbtCompound tag(byte[] bytes) throws IOException {
        return NbtIo.readCompound(new DataInputStream(new ByteArrayInputStream(bytes)), NbtSizeTracker.of(65_536));
    }
    private static byte[] bytes(NbtCompound tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        NbtIo.writeCompound(tag, new DataOutputStream(bytes));
        return bytes.toByteArray();
    }
}
