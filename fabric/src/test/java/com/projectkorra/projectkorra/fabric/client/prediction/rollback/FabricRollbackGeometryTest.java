package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockRay;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackGeometryTest {
    private static final Position ORIGIN = new Position(0, 0, 0);

    @BeforeAll static void bootstrapRegistry() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test void slabsStairsAndRestoredNeighborFluidsMatchPaperNativeFixtures() {
        LogicalWorld world = new LogicalWorld();
        Block block = world.getBlockAt(0, 0, 0);
        block.setBlockData(data(Material.OAK_SLAB, "minecraft:oak_slab[type=bottom]"), false);
        assertEquals(0.5, block.getCollisionBoxes().getFirst().getMaxY());
        block.setBlockData(data(Material.OAK_SLAB, "minecraft:oak_slab[type=top]"), false);
        assertEquals(0.5, block.getCollisionBoxes().getFirst().getMinY());
        block.setBlockData(data(Material.OAK_STAIRS, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight]"), false);
        assertTrue(block.getCollisionBoxes().size() > 1);
        assertEquals(1, block.getBoundingBox().getMaxY());
        block.setBlockData(data(Material.WATER, "minecraft:water[level=0]"), false);
        var saved = world.terrain.captureRollbackState();
        double before = world.terrain.geometry(ORIGIN).fluid().getFirst().maxY();
        assertTrue(before > 0 && before < 1);
        world.getBlockAt(0, 1, 0).setBlockData(data(Material.WATER, "minecraft:water[level=0]"), false);
        assertEquals(1, world.terrain.geometry(ORIGIN).fluid().getFirst().maxY());
        world.terrain.restoreRollbackState(saved);
        assertEquals(before, world.terrain.geometry(ORIGIN).fluid().getFirst().maxY());
    }

    @Test void malformedOrMismatchedBlockStatesFailInsteadOfSubstitutingGeometry() {
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackGeometry.decode(data(Material.STONE, "minecraft:missing_rollback_block")));
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackGeometry.decode(data(Material.OAK_SLAB, "minecraft:oak_slab[type=invalid]")));
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackGeometry.decode(data(Material.STONE, "minecraft:oak_slab[type=top]")));
    }

    @Test void nativeRaysRespectPartialShapesAndRestoreChangedTerrain() {
        LogicalWorld world = new LogicalWorld();
        world.getBlockAt(0, 0, 0).setBlockData(data(Material.OAK_SLAB, "minecraft:oak_slab[type=bottom]"), false);
        var high = new RollbackBlockRay(-1.5, 0.75, 0.5, 2.5, 0.75, 0.5, RollbackBlockRay.Fluids.NEVER, true);
        assertNull(world.shapes.rayTrace(world.terrain, high));
        var initial = world.terrain.captureRollbackState();
        world.getBlockAt(0, 0, 0).setBlockData(data(Material.OAK_SLAB, "minecraft:oak_slab[type=top]"), false);
        var hit = world.shapes.rayTrace(world.terrain, high);
        assertEquals(ORIGIN, hit.block());
        assertEquals(0.0, hit.x(), 1.0E-8);
        assertEquals(BlockFace.WEST, hit.face());
        world.terrain.restoreRollbackState(initial);
        assertNull(world.shapes.rayTrace(world.terrain, high));
        world.getBlockAt(0, 0, 0).setBlockData(data(Material.OAK_STAIRS,
                "minecraft:oak_stairs[facing=east,half=bottom,shape=straight]"), false);
        assertNull(world.shapes.rayTrace(world.terrain,
                new RollbackBlockRay(0.25, 0.75, -1.5, 0.25, 0.75, 2.5, RollbackBlockRay.Fluids.NEVER, true)));
        assertNotNull(world.shapes.rayTrace(world.terrain,
                new RollbackBlockRay(0.75, 0.75, -1.5, 0.75, 0.75, 2.5, RollbackBlockRay.Fluids.NEVER, true)));
    }

    @Test void nativeRayFluidSelectionAndNeighborHeightSurviveRewind() {
        LogicalWorld world = new LogicalWorld();
        world.getBlockAt(0, 0, 0).setBlockData(data(Material.WATER, "minecraft:water[level=0]"), false);
        var high = new RollbackBlockRay(-1.5, 0.95, 0.5, 2.5, 0.95, 0.5, RollbackBlockRay.Fluids.ALWAYS, false);
        assertNull(world.shapes.rayTrace(world.terrain, high));
        var initial = world.terrain.captureRollbackState();
        world.getBlockAt(0, 1, 0).setBlockData(data(Material.WATER, "minecraft:water[level=0]"), false);
        assertEquals(ORIGIN, world.shapes.rayTrace(world.terrain, high).block());
        world.terrain.restoreRollbackState(initial);
        assertNull(world.shapes.rayTrace(world.terrain, high));
        var sources = new RollbackBlockRay(-1.5, 0.1, 0.5, 2.5, 0.1, 0.5, RollbackBlockRay.Fluids.SOURCE_ONLY, false);
        assertNotNull(world.shapes.rayTrace(world.terrain, sources));
        world.getBlockAt(0, 0, 0).setBlockData(data(Material.WATER, "minecraft:water[level=1]"), false);
        assertNull(world.shapes.rayTrace(world.terrain, sources));
        assertNotNull(world.shapes.rayTrace(world.terrain,
                new RollbackBlockRay(-1.5, 0.1, 0.5, 2.5, 0.1, 0.5, RollbackBlockRay.Fluids.ALWAYS, false)));
        assertNull(world.shapes.rayTrace(world.terrain,
                new RollbackBlockRay(-1.5, 0.1, 0.5, 2.5, 0.1, 0.5, RollbackBlockRay.Fluids.NEVER, false)));
    }

    @Test void nativeRaysPreserveOutlineModeNegativeCoordinatesAndCapturedBounds() {
        LogicalWorld world = new LogicalWorld();
        world.getBlockAt(0, 0, 0).setBlockData(data(Material.TORCH, "minecraft:torch"), false);
        assertNotNull(world.shapes.rayTrace(world.terrain,
                new RollbackBlockRay(-1.5, 0.3, 0.5, 2.5, 0.3, 0.5, RollbackBlockRay.Fluids.NEVER, false)));
        assertNull(world.shapes.rayTrace(world.terrain,
                new RollbackBlockRay(-1.5, 0.3, 0.5, 2.5, 0.3, 0.5, RollbackBlockRay.Fluids.NEVER, true)));
        world.getBlockAt(-2, 0, 0).setBlockData(data(Material.STONE, "minecraft:stone"), false);
        var hit = world.shapes.rayTrace(world.terrain,
                new RollbackBlockRay(0.5, 0.8, 0.5, -3, 0.8, 0.5, RollbackBlockRay.Fluids.NEVER, true));
        assertEquals(new Position(-2, 0, 0), hit.block());
        assertEquals(-1, hit.x(), 1.0E-8);
        assertEquals(BlockFace.EAST, hit.face());
        assertTrue(world.shapes.rayTrace(world.terrain,
                new RollbackBlockRay(-1.5, 0.5, 0.5, -3, 0.5, 0.5, RollbackBlockRay.Fluids.NEVER, true)).inside());
        assertNull(world.shapes.rayTrace(world.terrain,
                new RollbackBlockRay(-1.5, 0.5, 0.5, -1.5, 0.5, 0.5, RollbackBlockRay.Fluids.NEVER, true)));
        assertThrows(IllegalStateException.class, () -> world.shapes.rayTrace(world.terrain,
                new RollbackBlockRay(0.5, 2.5, 0.5, 20, 2.5, 0.5, RollbackBlockRay.Fluids.NEVER, true)));
    }

    private static BlockData data(Material material, String exact) {
        BlockData data = new BlockData(material);
        data.setExactState(exact);
        return data;
    }

    static final class LogicalWorld extends World {
        final RollbackBlockStore terrain;
        final FabricRollbackGeometry shapes;
        LogicalWorld() {
            this(new Bounds(-4, -4, -4, 5, 5, 5));
        }
        LogicalWorld(Bounds bounds) {
            shapes = new FabricRollbackGeometry(-64, 320, (store, position, state) -> null);
            var rules = new Rules() {
                @Override public Geometry geometry(RollbackBlockStore terrain, Position position, BlockData data) {
                    return shapes.geometry(terrain, position, data);
                }
                @Override public byte legacyData(RollbackBlockStore terrain, Position position, BlockData data) { return 0; }
                @Override public void physics(RollbackBlockStore terrain, Position position) { throw new AssertionError("Unexpected physics"); }
                @Override public Collection<ItemStack> drops(RollbackBlockStore terrain, Position position, ItemStack tool) { return List.of(); }
                @Override public boolean breakNaturally(RollbackBlockStore terrain, Position position, ItemStack tool) { throw new AssertionError("Unexpected break"); }
            };
            var air = new Cell(data(Material.AIR, "minecraft:air"), null, Biome.DESERT, (byte) 0, 0.8, 0.4);
            terrain = new RollbackBlockStore(this, bounds, Map.of(), air, rules, 100);
        }
        @Override public Block getBlockAt(int x, int y, int z) { return terrain.block(x, y, z); }
    }
}
