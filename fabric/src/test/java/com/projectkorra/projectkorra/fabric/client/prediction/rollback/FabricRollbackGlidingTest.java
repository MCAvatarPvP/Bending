package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackMovementInput;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.EntityPose;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackGlidingTest {
    private static final UUID A = new UUID(0, 7001), B = new UUID(0, 7002);
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void nativeEligibilityEventCancellationAndGlidingMotionRestoreTogether() throws Exception {
        var scene = new FabricRollbackPrivateQueriesTest.Scene(); var state = prepare(scene); var player = state.ownedPlayer();
        var saved = new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(scene.queries), List.of());
        scene.services.fixture.cancelGlide = true;
        var velocity = player.getVelocity(); assertFalse(state.requestGlide()); assertFalse(player.isGliding()); assertEquals(velocity, player.getVelocity());
        assertTrue(scene.services.fixture.events.stream().anyMatch(event -> event.event().equals("glide:true:false")));
        saved.restore(); assertTrue(state.requestGlide()); step(state);
        var position = player.getEntityPos(); velocity = player.getVelocity(); var events = List.copyOf(scene.services.fixture.events);
        assertTrue(player.isGliding()); assertNotEquals(new Vec3d(.5, 3, .5), position);
        player.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY); step(state);
        assertFalse(player.isGliding(), "Native equipment loss requests glide stop");
        saved.restore(); assertTrue(state.requestGlide()); step(state);
        assertEquals(position, player.getEntityPos()); assertEquals(velocity, player.getVelocity()); assertEquals(events, scene.services.fixture.events);
        assertTrue(player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void automaticAndExplicitStopsHonorCapturedPolicyWithoutEquipment(boolean cancelled) throws Exception {
        var scene = new FabricRollbackPrivateQueriesTest.Scene(); var state = prepare(scene); var player = state.ownedPlayer();
        assertTrue(state.requestGlide()); player.equipStack(EquipmentSlot.CHEST, ItemStack.EMPTY);
        scene.services.fixture.cancelGlide = cancelled; step(state);
        assertEquals(cancelled, player.isGliding());
        assertTrue(scene.services.fixture.events.stream().anyMatch(event -> event.event().equals("glide:false:false")));
        // A duplicate/invalid START_FALL_FLYING command uses the server's native stop path.
        assertEquals(cancelled, state.requestGlide());
        scene.services.fixture.cancelGlide = false; assertFalse(state.requestGlide()); assertFalse(player.isGliding());
    }

    @Test void glideEventScopeRejectsForeignBodiesAndUnscopedWritesAndCleansUpAfterFailure() throws Exception {
        var scene = new FabricRollbackPrivateQueriesTest.Scene(); var state = prepare(scene); var player = state.ownedPlayer();
        assertThrows(IllegalStateException.class, player::startGliding); assertFalse(player.isGliding());
        var other = new FabricRollbackPrivateQueriesTest.Scene(); var foreign = prepare(other);
        assertThrows(IllegalArgumentException.class, () -> state.use(p -> { foreign.ownedPlayer().startGliding(); return null; }));
        assertThrows(IllegalStateException.class, () -> state.use(p -> foreign.requestGlide()));
        assertFalse(player.isGliding()); assertFalse(foreign.ownedPlayer().isGliding());
        assertTrue(state.requestGlide()); assertTrue(foreign.requestGlide());
        assertThrows(IllegalStateException.class, player::stopGliding); assertTrue(player.isGliding());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void climbingStopsGlidingThroughThePrivateEventRoute(boolean cancelled) throws Exception {
        var scene = new FabricRollbackPrivateQueriesTest.Scene(); var state = prepare(scene); var player = state.ownedPlayer();
        scene.block(new net.minecraft.util.math.BlockPos(0, 2, 0), com.projectkorra.projectkorra.platform.mc.Material.LADDER, "minecraft:ladder[facing=north,waterlogged=false]");
        player.setPosition(.5, 2, .5); player.setVelocity(Vec3d.ZERO); assertTrue(state.requestGlide());
        scene.services.fixture.cancelGlide = cancelled; step(state);
        assertTrue(player.isClimbing()); assertEquals(cancelled, player.isGliding());
    }

    @Test void nativeGlidingWallDamageAndMomentumRewindTogether() throws Exception {
        var scene = new FabricRollbackPrivateQueriesTest.Scene(); var state = prepare(scene); var player = state.ownedPlayer();
        for (int y = 1; y <= 4; y++) scene.block(new net.minecraft.util.math.BlockPos(1, y, 0),
                com.projectkorra.projectkorra.platform.mc.Material.STONE, "minecraft:stone");
        player.setPosition(.5, 2, .5); player.setVelocity(1.5, 0, 0); player.setHealth(20); assertTrue(state.requestGlide());
        var saved = new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(scene.queries), List.of());
        state.movementInput(new RollbackMovementInput(0, 0, false, -90, 0)); state.tick();
        var position = player.getEntityPos(); var velocity = player.getVelocity(); float health = player.getHealth();
        assertTrue(player.horizontalCollision); assertTrue(health < 20);
        saved.restore(); assertEquals(20, player.getHealth());
        state.movementInput(new RollbackMovementInput(0, 0, false, -90, 0)); state.tick();
        assertEquals(position, player.getEntityPos()); assertEquals(velocity, player.getVelocity()); assertEquals(health, player.getHealth());
    }
    private static FabricRollbackNativePlayerState prepare(FabricRollbackPrivateQueriesTest.Scene scene) {
        var state = scene.roster.players().get(A); var player = state.ownedPlayer();
        scene.roster.players().get(B).ownedPlayer().setPosition(3, 1, 3);
        player.setPosition(.5, 3, .5); player.setVelocity(.15, -.1, .2); player.setOnGround(false); player.setPose(EntityPose.STANDING);
        player.getAbilities().allowFlying = false; player.getAbilities().flying = false;
        player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA)); return state;
    }
    private static void step(FabricRollbackNativePlayerState state) {
        state.movementInput(new RollbackMovementInput(0, 0, false, 30, -15)); state.tick();
    }
}
