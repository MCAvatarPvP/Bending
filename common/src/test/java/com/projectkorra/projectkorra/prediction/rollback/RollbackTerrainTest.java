package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.BlockState;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.*;
import com.projectkorra.projectkorra.util.TempBlock;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RollbackTerrainTest {
    private static final Position ORIGIN = new Position(0, 0, 0);
    private static final Position EAST = new Position(1, 0, 0);
    private static final Box CUBE = new Box(0, 0, 0, 1, 1, 1);
    private static final Bounds BOUNDS = new Bounds(-4, -4, -4, 5, 5, 5);

    @Test void stableBlockViewsQueryRestoredNeighborDependentShapesAndDetachedData() {
        TestWorld world = new TestWorld(Map.of(ORIGIN, cell(Material.STONE)), new TestRules());
        Block origin = world.getBlockAt(0, 0, 0);
        assertSame(origin, world.getBlockAt(0, 0, 0));
        assertSame(world.getBlockAt(1, 0, 0), origin.getRelative(BlockFace.EAST));
        assertEquals(BlockFace.EAST, origin.getFace(origin.getRelative(BlockFace.EAST)));
        assertEquals(1, origin.getCollisionBoxes().getFirst().getMaxY());
        var graph = new RollbackStateGraph(ignored -> false, ignored -> true, 100);
        var before = graph.capture(List.of(origin), List.of());
        origin.getRelative(BlockFace.EAST).setType(Material.ICE, false);
        assertEquals(1.5, origin.getCollisionBoxes().getFirst().getMaxY());
        var returned = origin.getCollisionBoxes().getFirst();
        returned.getMax().setY(99);
        assertEquals(1.5, origin.getCollisionBoxes().getFirst().getMaxY());
        before.restore();
        assertSame(origin, world.getBlockAt(0, 0, 0));
        assertEquals(Material.AIR, origin.getRelative(BlockFace.EAST).getType());
        assertEquals(1, origin.getCollisionBoxes().getFirst().getMaxY());
        assertTrue(world.terrain.drainChanges().isEmpty());
    }

    @Test void cellAndSavedBlockStateRetainTypedPropertiesAndOpaqueBlockEntityData() {
        Levelled source = new Levelled(Material.WATER);
        source.setLevel(3);
        source.setExactState("minecraft:water[level=3]");
        byte[] tile = {1, 2, 3};
        Cell original = new Cell(source, tile, Biome.DESERT, (byte) 7, 0.8, 0.4);
        source.setLevel(14);
        tile[0] = 99;
        TestWorld world = new TestWorld(Map.of(ORIGIN, original), new TestRules());
        Block block = world.getBlockAt(0, 0, 0);
        var saved = block.getState();
        assertTrue(saved.hasBlockEntity());
        assertEquals(3, ((Levelled) saved.getBlockData()).getLevel());
        assertEquals("minecraft:water[level=3]", saved.getBlockData().getExactState());
        assertEquals(3, block.getData());
        ((Levelled) block.getBlockData()).setLevel(11);
        assertEquals(3, ((Levelled) block.getBlockData()).getLevel());
        block.setType(Material.ICE, false);
        assertFalse(saved.update(false, false));
        assertTrue(saved.update(true, false));
        assertArrayEquals(new byte[]{1, 2, 3}, world.terrain.cell(ORIGIN).blockEntity());
        byte[] output = world.terrain.cell(ORIGIN).blockEntity();
        output[0] = 100;
        assertArrayEquals(new byte[]{1, 2, 3}, world.terrain.cell(ORIGIN).blockEntity());
        assertEquals(7, block.getLightLevel());
    }

    @Test void existingTempBlockLayersAndExpirationReplayAgainstLogicalTerrain() {
        var graph = new RollbackStateGraph(ignored -> false, ignored -> true, 20_000);
        var shared = RollbackStateGraph.staticFields(TempBlock.class, ignored -> true);
        var outside = isolateTemporaryBlockRegistries();
        try {
            TestWorld world = new TestWorld(Map.of(ORIGIN, cell(Material.STONE)), new TestRules());
            Block block = world.getBlockAt(0, 0, 0);
            TempBlock first, second;
            try (var ignored = RollbackClock.at(0, 0, 50_000_000L)) {
                first = new TempBlock(block, Material.ICE.createBlockData(), 100);
                second = new TempBlock(block, Material.WATER.createBlockData(), 200);
            }
            assertEquals(2, TempBlock.getAll(block).size());
            assertEquals(Material.WATER, block.getType());
            var checkpoint = graph.capture(List.of(world.terrain), shared);
            try (var ignored = RollbackClock.at(0, 5, 50_000_000L)) {
                new TempBlock.TempBlockRevertTask().run();
            }
            assertEquals(Material.STONE, block.getType());
            assertTrue(first.isReverted());
            assertTrue(second.isReverted());
            assertFalse(TempBlock.isTempBlock(block));
            checkpoint.restore();
            assertEquals(Material.WATER, block.getType());
            assertFalse(first.isReverted());
            assertFalse(second.isReverted());
            assertEquals(List.of(first, second), TempBlock.getAll(block));
            try (var ignored = RollbackClock.at(0, 5, 50_000_000L)) {
                new TempBlock.TempBlockRevertTask().run();
            }
            assertEquals(Material.STONE, block.getType());
            assertFalse(TempBlock.isTempBlock(block));
        } finally { outside.restore(); }
    }

    @Test void lateInputReplacesProvisionalTerrainAndOnlyReplayedChangesAreFinalized() {
        TestWorld world = new TestWorld(Map.of(), new TestRules());
        UUID player = new UUID(0, 7);
        var simulation = new RollbackSimulation<State, Boolean, Change>() {
            @Override public State snapshot() { return world.terrain.captureRollbackState(); }
            @Override public void restore(State snapshot) { world.terrain.restoreRollbackState(snapshot); }
            @Override public Boolean predict(UUID participant, Boolean previous) { return false; }
            @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<Change> context) {
                if (inputs.get(player)) world.getBlockAt(0, 0, 0).setType(Material.ICE, false);
                world.terrain.drainChanges().forEach(context::emit);
            }
        };
        var engine = new RollbackEngine<>(simulation, Map.of(player, false),
                new RollbackEngine.Limits(3, 0, 100, 50_000_000L), 0);
        engine.advance();
        engine.advance();
        assertEquals(Material.AIR, world.getBlockAt(0, 0, 0).getType());
        assertEquals(RollbackEngine.Submission.ACCEPTED, engine.submit(player, 1, true));
        engine.reconcile();
        assertEquals(Material.ICE, world.getBlockAt(0, 0, 0).getType());
        assertTrue(engine.advance().finalizedEffects().isEmpty());
        var committed = engine.advance().finalizedEffects();
        assertEquals(1, committed.size());
        assertEquals(ORIGIN, committed.getFirst().value().position());
        assertEquals(Material.ICE, committed.getFirst().value().value().data().getMaterial());
        assertTrue(engine.advance().finalizedEffects().isEmpty());
    }

    @Test void aFailedTemporaryBlockCallbackAbortsTheStepWithoutFinalizingPartialTerrain() {
        var graph = new RollbackStateGraph(ignored -> false, ignored -> true, 20_000);
        var shared = RollbackStateGraph.staticFields(TempBlock.class, ignored -> true);
        var outside = isolateTemporaryBlockRegistries();
        try {
            TestWorld world = new TestWorld(Map.of(ORIGIN, cell(Material.STONE)), new TestRules());
            try (var ignored = RollbackClock.at(0, 0, 50_000_000L)) {
                TempBlock block = new TempBlock(world.getBlockAt(0, 0, 0), Material.ICE.createBlockData(), 10);
                block.setRevertTask(() -> { throw new IllegalArgumentException("callback failed"); });
            }
            var simulation = new RollbackSimulation<RollbackStateGraph.Snapshot, Boolean, Change>() {
                @Override public RollbackStateGraph.Snapshot snapshot() { return graph.capture(List.of(world.terrain), shared); }
                @Override public void restore(RollbackStateGraph.Snapshot state) { state.restore(); }
                @Override public Boolean predict(UUID participant, Boolean previous) { return false; }
                @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<Change> context) {
                    new TempBlock.TempBlockRevertTask().run();
                    world.terrain.drainChanges().forEach(context::emit);
                }
            };
            var engine = new RollbackEngine<>(simulation, Map.of(new UUID(0, 1), false),
                    new RollbackEngine.Limits(0, 0, 100, 50_000_000L), 0);
            assertThrows(IllegalArgumentException.class, engine::advance);
            assertTrue(engine.diagnostics().failed());
            assertEquals(0, engine.confirmed().tick());
            assertThrows(IllegalStateException.class, engine::advance);
        } finally { outside.restore(); }
    }

    @Test void nativeRulesSeeProvisionalNeighborsAndUncapturedReadsCannotUseLiveWorld() {
        TestWorld world = new TestWorld(Map.of(), new TestRules() {
            @Override public void physics(RollbackBlockStore terrain, Position position) {
                if (position.equals(ORIGIN) && terrain.cell(position).data().getMaterial() == Material.WATER) {
                    terrain.block(1, 0, 0).setType(Material.ICE, false);
                }
            }
        });
        world.getBlockAt(0, 0, 0).setType(Material.WATER);
        assertEquals(Material.ICE, world.getBlockAt(1, 0, 0).getType());
        assertEquals(List.of(ORIGIN, EAST), world.terrain.drainChanges().stream().map(Change::position).toList());
        assertThrows(IllegalStateException.class, () -> world.getBlockAt(100, 0, 0));
        assertThrows(IllegalStateException.class, () -> world.getBlockAt(4, 0, 0).getRelative(BlockFace.EAST));
        TestWorld other = new TestWorld(Map.of(), new TestRules());
        assertThrows(IllegalArgumentException.class, () -> other.terrain.restoreRollbackState(world.terrain.captureRollbackState()));
    }

    private static Cell cell(Material material) {
        return new Cell(material.createBlockData(), null, Biome.DESERT, (byte) 0, 0.8, 0.4);
    }

    private static RollbackStateGraph.Snapshot isolateTemporaryBlockRegistries() {
        // Other suites can leave registered fixture layers. Preserve their membership
        // without walking native/test-world handles or executing their expiry callbacks.
        var outsideGraph = new RollbackStateGraph(value -> value instanceof World || value instanceof Block
                || value instanceof BlockState || value instanceof Runnable
                || value instanceof com.projectkorra.projectkorra.ability.CoreAbility
                || value instanceof com.projectkorra.projectkorra.platform.mc.entity.Entity,
                ignored -> true, 20_000);
        var fields = RollbackStateGraph.staticFields(TempBlock.class, ignored -> true);
        var saved = outsideGraph.capture(List.of(), fields);
        try {
            for (var field : fields) {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof Map<?, ?> map) map.clear();
                else if (value instanceof Collection<?> collection) collection.clear();
            }
        } catch (IllegalAccessException failure) {
            saved.restore();
            throw new AssertionError(failure);
        }
        return saved;
    }

    private static final class TestWorld extends World {
        final RollbackBlockStore terrain;
        TestWorld(Map<Position, Cell> seed, Rules rules) {
            terrain = new RollbackBlockStore(this, BOUNDS, seed, cell(Material.AIR), rules, 100);
        }
        @Override public Block getBlockAt(int x, int y, int z) { return terrain.block(x, y, z); }
        @Override public String getName() { return "rollback-terrain"; }
    }

    private static class TestRules implements Rules {
        @Override public Geometry geometry(RollbackBlockStore terrain, Position position, BlockData data) {
            boolean liquid = data.getMaterial() == Material.WATER;
            boolean solid = data.getMaterial() != Material.AIR && !liquid;
            Box shape = CUBE;
            Position east = position.offset(1, 0, 0);
            if (solid && terrain.bounds().contains(east) && terrain.cell(east).data().getMaterial() == Material.ICE) {
                shape = new Box(0, 0, 0, 1, 1.5, 1);
            }
            return new Geometry(solid, liquid, !solid, false, shape,
                    solid ? List.of(shape) : List.of(), liquid ? List.of(CUBE) : List.of());
        }
        @Override public byte legacyData(RollbackBlockStore terrain, Position position, BlockData data) {
            return data instanceof Levelled levelled ? (byte) levelled.getLevel() : 0;
        }
        @Override public void physics(RollbackBlockStore terrain, Position changed) { }
        @Override public Collection<ItemStack> drops(RollbackBlockStore terrain, Position position, ItemStack tool) { return List.of(); }
        @Override public boolean breakNaturally(RollbackBlockStore terrain, Position position, ItemStack tool) {
            terrain.block(position.x(), position.y(), position.z()).setType(Material.AIR);
            return true;
        }
    }
}
