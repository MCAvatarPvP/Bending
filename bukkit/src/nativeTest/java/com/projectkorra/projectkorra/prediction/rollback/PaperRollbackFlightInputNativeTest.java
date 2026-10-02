package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackFlightInputNativeTest {
    private static final UUID A = new UUID(0, 7001), B = new UUID(0, 7002);
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void nativeFlightEventCancellationTakeoffAndTravelRestoreTogether() throws Exception {
        onTickThread(() -> {
            var scene = new PaperRollbackPrivateQueriesNativeTest.Scene(); var state = scene.players.get(A); var player = (ServerPlayer) state.ownedPlayer();
            scene.players.get(B).ownedPlayer().setPos(3, 1, 3);
            player.setPos(.5, 1, .5); player.setDeltaMovement(Vec3.ZERO); player.setOnGround(true);
            player.getAbilities().mayfly = true; player.getAbilities().flying = false;
            var saved = new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(scene.queries), List.of());
            try (var clock = RollbackClock.at(1000, 0, 1, 50_000_000)) {
                assertFalse(state.requestFlight(true, true)); assertFalse(player.getAbilities().flying); assertEquals(Vec3.ZERO, player.getDeltaMovement());
            }
            saved.restore(); scene.combat.cancel = true;
            try (var clock = RollbackClock.at(1000, 0, 1, 50_000_000)) {
                assertFalse(state.requestFlight(true, false)); assertFalse(player.getAbilities().flying); assertEquals(Vec3.ZERO, player.getDeltaMovement());
            }
            saved.restore(); takeoff(state);
            var position = player.position(); var velocity = player.getDeltaMovement(); var events = List.copyOf(scene.combat.events);
            assertTrue(position.y > 1); assertTrue(events.contains("org.bukkit.event.player.PlayerToggleFlightEvent"));
            try (var clock = RollbackClock.at(1000, 0, 2, 50_000_000)) {
                assertFalse(state.requestFlight(false, true)); assertTrue(player.getAbilities().flying);
                assertTrue(state.requestFlight(false, false)); assertFalse(player.getAbilities().flying);
            }
            saved.restore(); takeoff(state);
            assertEquals(position, player.position()); assertEquals(velocity, player.getDeltaMovement()); assertEquals(events, scene.combat.events);
            assertNull(org.bukkit.Bukkit.getServer()); return null;
        });
    }
    private static void takeoff(PaperRollbackNativePlayerState state) {
        try (var clock = RollbackClock.at(1000, 0, 1, 50_000_000)) {
            assertTrue(state.requestFlight(true, false)); assertTrue(state.ownedPlayer().getAbilities().flying);
            assertTrue(state.ownedPlayer().getDeltaMovement().y > 0);
            state.movementInput(new RollbackMovementInput(0, 0, true, 0, 0)); state.tick();
        }
    }
    @Test void unauthorizedDuplicateAndForeignRequestsCannotDeliverAnEvent() throws Exception {
        onTickThread(() -> {
            var scene = new PaperRollbackPrivateQueriesNativeTest.Scene(); var state = scene.players.get(A); var player = (ServerPlayer) state.ownedPlayer();
            player.getAbilities().mayfly = false; player.getAbilities().flying = false;
            assertFalse(state.requestFlight(true, false)); assertTrue(scene.combat.events.isEmpty());
            player.getAbilities().mayfly = true;
            assertTrue(state.requestFlight(false, false)); assertTrue(scene.combat.events.isEmpty());
            var foreign = (ServerPlayer) new PaperRollbackPrivateQueriesNativeTest.Scene().players.get(A).ownedPlayer();
            assertThrows(IllegalArgumentException.class, () -> scene.world.requestFlight(foreign, true, false));
            assertTrue(scene.combat.events.isEmpty()); return null;
        });
    }
}
