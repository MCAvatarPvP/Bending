package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Box;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.Motion;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackMovementSolver;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackMovementSolver.*;
import net.minecraft.SharedConstants;
import net.minecraft.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native shape iteration and clipping; the source uses an empty entity context for these solid-block fixtures only. */
class FabricRollbackMovementTest {
    private static final Box BODY = new Box(-0.3, 0, -0.3, 0.3, 1.8, 0.3);
    @BeforeAll static void bootstrap() {
        SharedConstants.createGameVersion(); Bootstrap.initialize();
    }

    @Test void nativeClippingSlidesAlongWallsAndLandsOnCapturedTerrain() {
        var scene = new Scene();
        scene.block(1, 0, 0, Material.STONE, "minecraft:stone");
        var result = scene.resolve(new Motion(2, -1, 0.4), 0, true);
        assertMotion(result.displacement(), 0.7, 0, 0.4);
        assertTrue(result.horizontalCollision()); assertTrue(result.verticalCollision()); assertTrue(result.collisionBelow());
        assertFalse(result.stepped());
    }

    @Test void nativeStepCandidatesFollowRestoredBlockShapeAndStepHeight() {
        var scene = new Scene();
        scene.block(1, 0, 0, Material.OAK_SLAB, "minecraft:oak_slab[type=bottom]");
        var checkpoint = scene.world.terrain.captureRollbackState();
        var stepped = scene.resolve(new Motion(1.5, -0.08, 0), 0.6F, true);
        assertMotion(stepped.displacement(), 1.5, 0.5, 0);
        assertTrue(stepped.stepped());
        assertMotion(scene.resolve(new Motion(1.5, -0.08, 0), 0.49F, true).displacement(), 0.7, 0, 0);
        scene.block(1, 0, 0, Material.STONE, "minecraft:stone");
        assertMotion(scene.resolve(new Motion(1.5, -0.08, 0), 0.6F, true).displacement(), 0.7, 0, 0);
        scene.world.terrain.restoreRollbackState(checkpoint);
        assertMotion(scene.resolve(new Motion(1.5, -0.08, 0), 0.6F, true).displacement(), 1.5, 0.5, 0);
    }

    @Test void ceilingAndAirborneStatePreventAnOtherwiseReachableStep() {
        var scene = new Scene();
        scene.block(1, 0, 0, Material.OAK_SLAB, "minecraft:oak_slab[type=bottom]");
        assertFalse(scene.resolve(new Motion(1.5, 0, 0), 0.6F, false).stepped());
        scene.block(0, 2, 0, Material.STONE, "minecraft:stone");
        var result = scene.resolve(new Motion(1.5, -0.08, 0), 0.6F, true);
        assertFalse(result.stepped());
        assertMotion(result.displacement(), 0.7, 0, 0);
    }

    @Test void logicalEntityCollidersAreRetainedDuringStepSearchAndZeroMotionDoesNotQuery() {
        var scene = new Scene();
        scene.entities.add(new Box(0.9, 0, -0.5, 1.2, 2, 0.5));
        var result = scene.resolve(new Motion(1.5, -0.08, 0), 0.6F, true);
        assertMotion(result.displacement(), 0.6, 0, 0);
        assertEquals(1, scene.entityQueries);
        assertFalse(result.stepped());
        scene.entities.clear();
        assertMotion(scene.resolve(new Motion(1.5, -0.08, 0), 0.6F, true).displacement(), 1.5, 0, 0);
        int queried = scene.worldQueries;
        scene.resolve(new Motion(0, 0, 0), 0.6F, true);
        assertEquals(queried, scene.worldQueries);
    }

    @Test void movementCollectorRejectsUncapturedTerrainAndExcessiveSweepBeforeIteration() {
        var scene = new Scene();
        assertThrows(IllegalStateException.class, () -> scene.resolve(new Motion(10, 0, 0), 0, true));
        assertThrows(IllegalStateException.class, () -> scene.resolve(new Motion(1_000, 1_000, 1_000), 0, true));
    }


    @Test void lateTerrainInputReplaysNativeMovementAndReplacesTheProvisionalPath() {
        UUID player = new UUID(0, 1);
        var onTime = new MovementSimulation();
        var delayed = new MovementSimulation();
        var limits = new com.projectkorra.projectkorra.prediction.rollback.RollbackEngine.Limits(4, 2, 100, 50_000_000);
        var first = new com.projectkorra.projectkorra.prediction.rollback.RollbackEngine<>(onTime, Map.of(player, false), limits, 0);
        var second = new com.projectkorra.projectkorra.prediction.rollback.RollbackEngine<>(delayed, Map.of(player, false), limits, 0);
        first.submit(player, 1, true);
        for (int tick = 1; tick <= 3; tick++) { first.advance(); second.advance(); }
        assertNotEquals(first.head().state().position(), second.head().state().position());
        second.submit(player, 1, true);
        assertEquals(1, second.reconcile().replayedFrom());
        assertEquals(first.head().state().position(), second.head().state().position());
        assertEquals(first.head().effects(), second.head().effects());
        for (int tick = 4; tick <= 7; tick++) {
            assertEquals(first.advance().finalizedEffects(), second.advance().finalizedEffects());
        }
    }

    private record Frame(com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.State terrain, Motion position) { }
    private static final class MovementSimulation implements com.projectkorra.projectkorra.prediction.rollback.RollbackSimulation<Frame, Boolean, Motion> {
        private final Scene scene = new Scene();
        private Motion position = new Motion(0, 0, 0);
        @Override public Frame snapshot() { return new Frame(scene.world.terrain.captureRollbackState(), position); }
        @Override public void restore(Frame snapshot) { scene.world.terrain.restoreRollbackState(snapshot.terrain()); position = snapshot.position(); }
        @Override public Boolean predict(UUID player, Boolean previous) { return false; }
        @Override public void step(long tick, Map<UUID, Boolean> inputs, com.projectkorra.projectkorra.prediction.rollback.RollbackStep<Motion> effects) {
            if (inputs.values().stream().anyMatch(Boolean::booleanValue)) scene.block(1, 0, 0, Material.STONE, "minecraft:stone");
            // Variable displacement is a test input to native clipping, not a replacement for native travel.
            Motion requested = new Motion(0.6 + tick * 0.1, -0.08, 0);
            var clipped = scene.solver.resolve(new Input(RollbackMovementSolver.move(BODY, position), requested, 0, true), scene).displacement();
            position = new Motion(position.x() + clipped.x(), position.y() + clipped.y(), position.z() + clipped.z());
            effects.emit(position);
        }
    }

    private static void assertMotion(Motion actual, double x, double y, double z) {
        assertEquals(x, actual.x(), 1e-9); assertEquals(y, actual.y(), 1e-9); assertEquals(z, actual.z(), 1e-9);
    }
    private static class Scene implements Source<net.minecraft.util.shape.VoxelShape> {
        final FabricRollbackGeometryTest.LogicalWorld world = new FabricRollbackGeometryTest.LogicalWorld();
        final List<Box> entities = new ArrayList<>();
        final RollbackMovementSolver<net.minecraft.util.shape.VoxelShape> solver = new RollbackMovementSolver<>(new FabricRollbackMovement(), Limits.standard());
        int entityQueries, worldQueries;
        Scene() {
            for (int x = -2; x <= 3; x++) for (int z = -2; z <= 2; z++) block(x, -1, z, Material.STONE, "minecraft:stone");
        }
        void block(int x, int y, int z, Material material, String state) {
            BlockData data = material.createBlockData(); data.setExactState(state);
            world.getBlockAt(x, y, z).setBlockData(data, false);
        }
        Result resolve(Motion movement, float stepHeight, boolean onGround) {
            return solver.resolve(new Input(BODY, movement, stepHeight, onGround), this);
        }
        @Override public List<Box> entities(Box bounds) { entityQueries++; return List.copyOf(entities); }
        @Override public Colliders<net.minecraft.util.shape.VoxelShape> terrainAndBorder(Box bounds) {
            worldQueries++;
            return new Colliders<>(world.shapes.movementColliders(world.terrain, bounds,
                    net.minecraft.block.ShapeContext.absent()), List.of());
        }
    }
}
