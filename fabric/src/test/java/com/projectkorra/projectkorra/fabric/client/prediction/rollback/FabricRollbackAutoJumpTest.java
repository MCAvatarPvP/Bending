package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackAutoJumpTest {
    private static final UUID A = new UUID(0, 7001), B = new UUID(0, 7002);
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void nativeGeometrySchedulesOneFutureJumpWithoutMovingOrRetickingThePlayerDuringInputSampling() throws Exception {
        var scene = new Scene(); var controls = new FabricRollbackClientControls(scene.state); var keys = new Keys();
        controls.tick(1, keys, 7, true);
        controls.simulate(() -> scene.step(false));
        var position = scene.player.getEntityPos(); var velocity = scene.player.getVelocity(); int age = scene.player.age;
        controls.tick(2, keys, 7, true);
        assertTrue(keys.playerInput.jump(), "The native forward collision should schedule a jump on the next input frame");
        assertEquals(position, scene.player.getEntityPos()); assertEquals(velocity, scene.player.getVelocity()); assertEquals(age, scene.player.age);
        controls.tick(3, keys, 7, true);
        assertFalse(keys.playerInput.jump(), "Waiting on the same simulated head must not repeat the automatic key press");
        assertEquals(3, keys.samples);
    }

    @ParameterizedTest @ValueSource(strings = {"disabled", "ceiling", "crouching", "slab", "no_wall"})
    void nativeGeometryAndPreferenceSuppressUnnecessaryOrBlockedJumps(String condition) throws Exception {
        var scene = new Scene(); var controls = new FabricRollbackClientControls(scene.state); var keys = new Keys();
        switch (condition) {
            case "ceiling" -> scene.terrain.block(new BlockPos(0, 3, 0), Material.STONE, "minecraft:stone");
            case "crouching" -> { scene.player.setSneaking(true); scene.player.setPose(EntityPose.CROUCHING); }
            case "slab" -> scene.terrain.block(new BlockPos(0, 1, 1), Material.STONE_SLAB, "minecraft:stone_slab[type=bottom,waterlogged=false]");
            case "no_wall" -> scene.wall(false);
        }
        boolean enabled = !condition.equals("disabled");
        controls.tick(1, keys, 7, enabled); controls.simulate(() -> scene.step(false)); controls.tick(2, keys, 7, enabled);
        assertFalse(keys.playerInput.jump(), condition);
    }

    @Test void replayedTerrainCanRetractAnUnsentJumpButCannotResampleAnAlreadyConsumedHead() throws Exception {
        var scene = new Scene(); var controls = new FabricRollbackClientControls(scene.state); var keys = new Keys();
        var saved = scene.snapshot(); controls.tick(1, keys, 7, true);
        controls.simulate(() -> scene.step(false));
        saved.restore(); scene.wall(false); controls.simulate(() -> scene.step(false));
        controls.tick(2, keys, 7, true); assertFalse(keys.playerInput.jump(), "The corrected terrain has no obstacle");
        saved.restore(); controls.simulate(() -> scene.step(false));
        controls.tick(3, keys, 7, true); assertFalse(keys.playerInput.jump(), "A correction cannot insert another input into the consumed physical frame");
        controls.simulate(() -> scene.step(false)); controls.tick(4, keys, 7, true); assertTrue(keys.playerInput.jump());
        assertEquals(4, keys.samples);
    }

    @Test void failedOrForeignSimulationDoesNotPublishAJumpAndObservationAlwaysCloses() throws Exception {
        var scene = new Scene(); var other = new Scene(); var controls = new FabricRollbackClientControls(scene.state); var keys = new Keys();
        controls.tick(1, keys, 7, true); controls.simulate(() -> other.step(false));
        controls.tick(2, keys, 7, true); assertFalse(keys.playerInput.jump());
        var failure = new IllegalStateException("failed simulation");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> controls.simulate(() -> { scene.step(false); throw failure; })));
        controls.tick(3, keys, 7, true); assertFalse(keys.playerInput.jump());
        assertThrows(IllegalStateException.class, () -> controls.simulate(() -> controls.simulate(() -> { })));
        controls.simulate(() -> scene.step(false)); controls.tick(4, keys, 7, true); assertTrue(keys.playerInput.jump());
        assertSame(scene.player, FabricRollbackAutoJump.collisionSource(scene.player));
        controls.clear(); controls.tick(5, keys, 7, true); assertFalse(keys.playerInput.jump());
        controls.simulate(() -> { scene.step(false); controls.clear(); });
        controls.tick(6, keys, 7, true); assertFalse(keys.playerInput.jump(), "Stopping during output cannot republish a pending jump when observation returns");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void runtimeTransmitsAutomaticJumpUnlessAuthorityRetractsItsObstacleBeforeSampling(boolean corrected) throws Exception {
        var scene = new Scene(); var keys = new Keys(); var sent = new ArrayList<RollbackInputPacket>();
        var visibleWorld = RollbackNativeQueryShell.create(World.class).instance();
        var visible = RollbackNativeQueryShell.create(ClientPlayerEntity.class).constant(PlayerEntity::getEntityWorld, visibleWorld)
                .constant(PlayerEntity::getUuid, A).constant(PlayerEntity::getYaw, 0F).constant(PlayerEntity::getPitch, 0F).instance();
        visible.input = keys;
        var idle = new RollbackPlayerInput(new RollbackMovementInput(0, 0, false, 0, 0), false, List.of());
        var initial = Map.of(A, idle, B, idle); var limits = new RollbackEngine.Limits(8, 1, 10, 50_000_000);
        var engine = RollbackEngine.replica(simulation(scene), initial, limits, 0, 0); var id = UUID.randomUUID();
        var runtime = new FabricRollbackClientRuntime<>(id, 55, visible, engine, sent::add, new RollbackClientRuntime.Output<RollbackStateGraph.Snapshot, Void>() {
            @Override public void update(RollbackEngine.Update<RollbackStateGraph.Snapshot, RollbackPlayerInput, Void> update) { }
            @Override public void stop(RollbackStartServerEndpoint.Failure failure) { }
        }, scene.state, () -> 7, () -> true);
        runtime.start(0); runtime.nativeTick(1); runtime.tick(1);
        assertFalse(sent.getFirst().movement().jump()); double y = scene.player.getY();
        if (corrected) {
            Object local = new Object(), opponent = new Object(); var authority = new Scene();
            var server = new RollbackSession<>(id, 55, new RollbackEngine<>(simulation(authority), initial, limits, 0, 0),
                    List.of(new RollbackSession.Peer(A, local, 0), new RollbackSession.Peer(B, opponent, 0)));
            assertEquals(RollbackSession.Status.ACCEPTED, server.receive(local, RollbackInputPacket.decode(sent.getFirst().encode())).status());
            assertEquals(RollbackSession.Status.ACCEPTED, server.receive(opponent,
                    new RollbackInputPacket(id, 1, new RollbackMovementInput(0, 0, true, 0, 0), false, List.of())).status());
            server.advance(); var publication = server.publish(); var wire = new RollbackAuthorityChunk.Assembler(id, 0, 40);
            for (var chunk : RollbackAuthorityChunk.split(publication)) {
                var update = wire.receive(RollbackAuthorityChunk.decode(chunk.encode()), publication.headTick());
                if (update != null) runtime.authority(update);
            }
            assertEquals(1, keys.samples); assertEquals(1, sent.size()); assertTrue(engine.diagnostics().replayedSteps() > 0);
        }
        runtime.nativeTick(2); runtime.tick(2);
        assertEquals(!corrected, sent.getLast().movement().jump());
        if (corrected) assertEquals(y, scene.player.getY()); else assertTrue(scene.player.getY() > y);
        assertEquals(2, keys.samples); assertEquals(2, sent.size()); assertEquals(0, visible.age);
    }

    private static RollbackSimulation<RollbackStateGraph.Snapshot, RollbackPlayerInput, Void> simulation(Scene scene) {
        return new RollbackSimulation<>() {
            @Override public RollbackStateGraph.Snapshot snapshot() { return scene.snapshot(); }
            @Override public void restore(RollbackStateGraph.Snapshot state) { state.restore(); }
            @Override public RollbackPlayerInput predict(UUID id, RollbackPlayerInput previous) { return previous.predict(); }
            @Override public void step(long tick, Map<UUID, RollbackPlayerInput> inputs, RollbackStep<Void> output) {
                // Causal terrain-change fixture; the native player and collision queries are production adapters.
                if (inputs.get(B).movement().jump()) scene.wall(false);
                scene.state.movementInput(inputs.get(A).movement()); scene.state.tick();
            }
        };
    }

    private static final class Scene {
        final FabricRollbackPrivateQueriesTest.Scene terrain = new FabricRollbackPrivateQueriesTest.Scene();
        final FabricRollbackNativePlayerState state = terrain.roster.players().get(A);
        final PlayerEntity player = state.ownedPlayer();
        Scene() throws Exception {
            player.setPosition(.5, 1, .5); player.setVelocity(Vec3d.ZERO); player.setYaw(0); player.setPitch(0); player.setOnGround(true);
            player.setSneaking(false); player.setSprinting(false); player.setPose(EntityPose.STANDING);
            player.getAbilities().allowFlying = false; player.getAbilities().flying = false;
            player.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED).setBaseValue(.1); player.getAttributeInstance(EntityAttributes.SCALE).setBaseValue(1);
            player.getAttributeInstance(EntityAttributes.STEP_HEIGHT).setBaseValue(.6);
            terrain.roster.players().get(B).ownedPlayer().setPosition(3, 1, 3); wall(true);
        }
        void wall(boolean present) { terrain.block(new BlockPos(0, 1, 1), present ? Material.STONE : Material.AIR, present ? "minecraft:stone" : "minecraft:air"); }
        void step(boolean jump) { state.movementInput(new RollbackMovementInput(0, 1, jump, 0, 0)); state.tick(); }
        RollbackStateGraph.Snapshot snapshot() { return new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(terrain.queries), List.of()); }
    }
    private static final class Keys extends Input {
        int samples;
        @Override public void tick() { samples++; playerInput = new PlayerInput(true, false, false, false, false, false, false); movementVector = new Vec2f(0, 1); }
    }
}
