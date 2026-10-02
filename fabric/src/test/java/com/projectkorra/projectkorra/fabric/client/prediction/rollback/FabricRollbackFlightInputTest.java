package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackMovementInput;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import net.minecraft.entity.EntityPose;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackFlightInputTest {
    private static final UUID A = new UUID(0, 7001), B = new UUID(0, 7002);
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void eventPermissionTakeoffAndNativeTravelRestoreTogether() throws Exception {
        var scene = new FabricRollbackPrivateQueriesTest.Scene(); var state = scene.roster.players().get(A); var player = state.ownedPlayer();
        scene.roster.players().get(B).ownedPlayer().setPosition(3, 1, 3);
        player.setPosition(.5, 1, .5); player.setVelocity(Vec3d.ZERO); player.setOnGround(true); player.setPose(EntityPose.STANDING);
        player.getAbilities().allowFlying = true; player.getAbilities().flying = false;
        var saved = new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(scene.queries), List.of());
        assertFalse(state.requestFlight(true, true)); assertFalse(player.getAbilities().flying); assertEquals(Vec3d.ZERO, player.getVelocity());
        saved.restore(); scene.services.fixture.cancelFlight = true;
        assertFalse(state.requestFlight(true, false)); assertFalse(player.getAbilities().flying); assertEquals(Vec3d.ZERO, player.getVelocity());
        saved.restore(); assertTrue(state.requestFlight(true, false)); assertTrue(player.getAbilities().flying); assertTrue(player.getVelocity().y > 0);
        state.movementInput(new RollbackMovementInput(0, 0, true, 0, 0)); state.tick();
        var position = player.getEntityPos(); var velocity = player.getVelocity(); var events = List.copyOf(scene.services.fixture.events);
        assertTrue(position.y > 1);
        assertFalse(state.requestFlight(false, true)); assertTrue(player.getAbilities().flying);
        assertTrue(state.requestFlight(false, false)); assertFalse(player.getAbilities().flying);
        saved.restore(); assertTrue(state.requestFlight(true, false));
        state.movementInput(new RollbackMovementInput(0, 0, true, 0, 0)); state.tick();
        assertEquals(position, player.getEntityPos()); assertEquals(velocity, player.getVelocity()); assertEquals(events, scene.services.fixture.events);
    }

    @Test void unauthorizedDuplicateAndForeignRequestsCannotApplyFlightOrDeliverAnEvent() throws Exception {
        var scene = new FabricRollbackPrivateQueriesTest.Scene(); var state = scene.roster.players().get(A); var player = state.ownedPlayer();
        player.getAbilities().allowFlying = false; player.getAbilities().flying = false;
        assertFalse(state.requestFlight(true, false)); assertTrue(scene.services.fixture.events.isEmpty());
        player.getAbilities().allowFlying = true;
        assertTrue(state.requestFlight(false, false)); assertTrue(scene.services.fixture.events.isEmpty());
        var foreign = new FabricRollbackPrivateQueriesTest.Scene().roster.players().get(A).ownedPlayer();
        assertThrows(IllegalArgumentException.class, () -> scene.roster.world().flightAllowed(foreign, true, false));
        assertTrue(scene.services.fixture.events.isEmpty());
    }
}
