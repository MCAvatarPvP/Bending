package com.projectkorra.projectkorra.prediction.rollback;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
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
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real Paper 1.21.11 state/shape implementations, with no live Minecraft world or server. */
class PaperRollbackGeometryNativeTest {
    private static final Position ORIGIN = new Position(0, 0, 0);

    @BeforeAll static void bootstrapNativeRegistry() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void nativeSlabAndStairShapesArePreservedAndRestored() {
        LogicalWorld world = new LogicalWorld(Map.of(ORIGIN, cell(Material.OAK_SLAB, "minecraft:oak_slab[type=bottom]")));
        Block block = world.getBlockAt(0, 0, 0);
        assertEquals(0.5, block.getCollisionBoxes().getFirst().getMaxY());
        var saved = world.terrain.captureRollbackState();
        block.setBlockData(data(Material.OAK_SLAB, "minecraft:oak_slab[type=top]"), false);
        assertEquals(0.5, block.getCollisionBoxes().getFirst().getMinY());
        block.setBlockData(data(Material.OAK_STAIRS, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight]"), false);
        assertTrue(block.getCollisionBoxes().size() > 1, "stairs must not become a full cube");
        assertEquals(1, block.getBoundingBox().getMaxY());
        world.terrain.restoreRollbackState(saved);
        assertEquals(0, block.getCollisionBoxes().getFirst().getMinY());
        assertEquals(0.5, block.getCollisionBoxes().getFirst().getMaxY());
    }

    @Test void fluidShapeQueriesReadProvisionalNeighborCellsAndRewindThem() {
        LogicalWorld world = new LogicalWorld(Map.of(ORIGIN, cell(Material.WATER, "minecraft:water[level=0]")));
        var initial = world.terrain.captureRollbackState();
        double initialHeight = world.terrain.geometry(ORIGIN).fluid().getFirst().maxY();
        assertTrue(initialHeight > 0 && initialHeight < 1);
        world.getBlockAt(0, 1, 0).setBlockData(data(Material.WATER, "minecraft:water[level=0]"), false);
        assertEquals(1, world.terrain.geometry(ORIGIN).fluid().getFirst().maxY());
        world.terrain.restoreRollbackState(initial);
        assertEquals(initialHeight, world.terrain.geometry(ORIGIN).fluid().getFirst().maxY());
    }

    @Test void airAndWaterloggedSolidsKeepTheirNativeCollisionSemantics() {
        LogicalWorld world = new LogicalWorld(Map.of());
        Geometry air = world.terrain.geometry(ORIGIN);
        assertTrue(air.passable());
        assertTrue(air.collision().isEmpty());
        assertTrue(air.fluid().isEmpty());
        assertNull(air.bounds());
        world.getBlockAt(0, 0, 0).setBlockData(data(Material.OAK_SLAB,
                "minecraft:oak_slab[type=bottom,waterlogged=true]"), false);
        Geometry slab = world.terrain.geometry(ORIGIN);
        assertFalse(slab.passable());
        assertFalse(slab.liquid(), "waterlogging must not turn a solid block into a liquid block");
        assertFalse(slab.fluid().isEmpty());
        assertEquals(0.5, slab.collision().getFirst().maxY());
    }

    @Test void nativeRaysRespectPartialShapesAndRestoreChangedTerrain() {
        LogicalWorld world = new LogicalWorld(Map.of(ORIGIN, cell(Material.OAK_SLAB, "minecraft:oak_slab[type=bottom]")));
        var high = new RollbackBlockRay(-1.5, 0.75, 0.5, 2.5, 0.75, 0.5, RollbackBlockRay.Fluids.NEVER, true);
        assertNull(world.geometry.rayTrace(world.terrain, high));
        var initial = world.terrain.captureRollbackState();
        world.getBlockAt(0, 0, 0).setBlockData(data(Material.OAK_SLAB, "minecraft:oak_slab[type=top]"), false);
        var hit = world.geometry.rayTrace(world.terrain, high);
        assertEquals(ORIGIN, hit.block());
        assertEquals(0.0, hit.x(), 1.0E-8);
        assertEquals(BlockFace.WEST, hit.face());
        world.terrain.restoreRollbackState(initial);
        assertNull(world.geometry.rayTrace(world.terrain, high));
        world.getBlockAt(0, 0, 0).setBlockData(data(Material.OAK_STAIRS,
                "minecraft:oak_stairs[facing=east,half=bottom,shape=straight]"), false);
        assertNull(world.geometry.rayTrace(world.terrain,
                new RollbackBlockRay(0.25, 0.75, -1.5, 0.25, 0.75, 2.5, RollbackBlockRay.Fluids.NEVER, true)));
        assertNotNull(world.geometry.rayTrace(world.terrain,
                new RollbackBlockRay(0.75, 0.75, -1.5, 0.75, 0.75, 2.5, RollbackBlockRay.Fluids.NEVER, true)));
    }

    @Test void nativeRayFluidSelectionAndNeighborHeightSurviveRewind() {
        LogicalWorld world = new LogicalWorld(Map.of(ORIGIN, cell(Material.WATER, "minecraft:water[level=0]")));
        var high = new RollbackBlockRay(-1.5, 0.95, 0.5, 2.5, 0.95, 0.5, RollbackBlockRay.Fluids.ALWAYS, false);
        assertNull(world.geometry.rayTrace(world.terrain, high));
        var initial = world.terrain.captureRollbackState();
        world.getBlockAt(0, 1, 0).setBlockData(data(Material.WATER, "minecraft:water[level=0]"), false);
        assertEquals(ORIGIN, world.geometry.rayTrace(world.terrain, high).block());
        world.terrain.restoreRollbackState(initial);
        assertNull(world.geometry.rayTrace(world.terrain, high));
        var sources = new RollbackBlockRay(-1.5, 0.1, 0.5, 2.5, 0.1, 0.5, RollbackBlockRay.Fluids.SOURCE_ONLY, false);
        assertNotNull(world.geometry.rayTrace(world.terrain, sources));
        world.getBlockAt(0, 0, 0).setBlockData(data(Material.WATER, "minecraft:water[level=1]"), false);
        assertNull(world.geometry.rayTrace(world.terrain, sources));
        assertNotNull(world.geometry.rayTrace(world.terrain,
                new RollbackBlockRay(-1.5, 0.1, 0.5, 2.5, 0.1, 0.5, RollbackBlockRay.Fluids.ALWAYS, false)));
        assertNull(world.geometry.rayTrace(world.terrain,
                new RollbackBlockRay(-1.5, 0.1, 0.5, 2.5, 0.1, 0.5, RollbackBlockRay.Fluids.NEVER, false)));
    }

    @Test void nativeRaysPreserveOutlineModeNegativeCoordinatesAndCapturedBounds() {
        LogicalWorld world = new LogicalWorld(Map.of(ORIGIN, cell(Material.TORCH, "minecraft:torch")));
        assertNotNull(world.geometry.rayTrace(world.terrain,
                new RollbackBlockRay(-1.5, 0.3, 0.5, 2.5, 0.3, 0.5, RollbackBlockRay.Fluids.NEVER, false)));
        assertNull(world.geometry.rayTrace(world.terrain,
                new RollbackBlockRay(-1.5, 0.3, 0.5, 2.5, 0.3, 0.5, RollbackBlockRay.Fluids.NEVER, true)));
        world.getBlockAt(-2, 0, 0).setBlockData(data(Material.STONE, "minecraft:stone"), false);
        var hit = world.geometry.rayTrace(world.terrain,
                new RollbackBlockRay(0.5, 0.8, 0.5, -3, 0.8, 0.5, RollbackBlockRay.Fluids.NEVER, true));
        assertEquals(new Position(-2, 0, 0), hit.block());
        assertEquals(-1, hit.x(), 1.0E-8);
        assertEquals(BlockFace.EAST, hit.face());
        assertTrue(world.geometry.rayTrace(world.terrain,
                new RollbackBlockRay(-1.5, 0.5, 0.5, -3, 0.5, 0.5, RollbackBlockRay.Fluids.NEVER, true)).inside());
        assertNull(world.geometry.rayTrace(world.terrain,
                new RollbackBlockRay(-1.5, 0.5, 0.5, -1.5, 0.5, 0.5, RollbackBlockRay.Fluids.NEVER, true)));
        assertThrows(IllegalStateException.class, () -> world.geometry.rayTrace(world.terrain,
                new RollbackBlockRay(0.5, 2.5, 0.5, 20, 2.5, 0.5, RollbackBlockRay.Fluids.NEVER, true)));
    }

    private static BlockData data(Material material, String state) {
        BlockData data = new BlockData(material);
        data.setExactState(state);
        return data;
    }
    private static Cell cell(Material material, String state) {
        return new Cell(data(material, state), null, Biome.DESERT, (byte) 0, 0.8, 0.4);
    }

    static final class LogicalWorld extends World {
        final RollbackBlockStore terrain;
        final PaperRollbackGeometry geometry;
        LogicalWorld(Map<Position, Cell> seed) {
            this(seed, new Bounds(-4, -4, -4, 5, 5, 5));
        }
        LogicalWorld(Map<Position, Cell> seed, Bounds bounds) {
            geometry = new PaperRollbackGeometry(-64, 320,
                    (store, position, state) -> {
                        assertFalse(((BlockState) state).hasBlockEntity(), "this fixture contains no block entities");
                        return null;
                    }, new PaperRollbackGeometry.States() {
                @Override public Object decode(BlockData data) {
                    try { return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, data.getAsString(), false).blockState(); }
                    catch (CommandSyntaxException failure) { throw new IllegalArgumentException(failure); }
                }
                @Override public boolean solid(BlockData data) { return ((BlockState) decode(data)).blocksMotion(); }
            });
            Rules rules = new Rules() {
                @Override public Geometry geometry(RollbackBlockStore terrain, Position position, BlockData data) {
                    return geometry.geometry(terrain, position, data);
                }
                @Override public byte legacyData(RollbackBlockStore terrain, Position position, BlockData data) {
                    return geometry.legacyData(data);
                }
                @Override public void physics(RollbackBlockStore terrain, Position changed) {
                    throw new AssertionError("Physics was not requested by this shape test");
                }
                @Override public Collection<ItemStack> drops(RollbackBlockStore terrain, Position position, ItemStack tool) {
                    throw new AssertionError("Drops were not requested by this shape test");
                }
                @Override public boolean breakNaturally(RollbackBlockStore terrain, Position position, ItemStack tool) {
                    throw new AssertionError("Breaking was not requested by this shape test");
                }
            };
            terrain = new RollbackBlockStore(this, bounds, seed,
                    cell(Material.AIR, "minecraft:air"), rules, 100);
        }
        @Override public Block getBlockAt(int x, int y, int z) { return terrain.block(x, y, z); }
    }
}
