package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.UUID;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackGlidingNativeTest {
    private static final UUID A = new UUID(0, 7001), B = new UUID(0, 7002);
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void nativeEligibilityEventCancellationAndGlidingMotionRestoreTogether() throws Exception {
        onTickThread(() -> {
            var scene = new PaperRollbackPrivateQueriesNativeTest.Scene(); var state = prepare(scene); var player = state.ownedPlayer();
            var saved = new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(scene.queries), List.of());
            scene.combat.cancel = true;
            var velocity = player.getDeltaMovement(); assertFalse(state.requestGlide()); assertFalse(player.isFallFlying()); assertEquals(velocity, player.getDeltaMovement());
            assertTrue(scene.combat.events.contains("org.bukkit.event.entity.EntityToggleGlideEvent"));
            saved.restore(); assertTrue(state.requestGlide()); step(state);
            var position = player.position(); velocity = player.getDeltaMovement(); var events = List.copyOf(scene.combat.events);
            assertTrue(player.isFallFlying()); assertNotEquals(new Vec3(.5, 3, .5), position);
            player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY); step(state); assertFalse(player.isFallFlying());
            saved.restore(); assertTrue(state.requestGlide()); step(state);
            assertEquals(position, player.position()); assertEquals(velocity, player.getDeltaMovement()); assertEquals(events, scene.combat.events);
            assertTrue(player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)); assertNull(org.bukkit.Bukkit.getServer()); return null;
        });
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void automaticAndExplicitStopsHonorNativeCancellationWithoutEquipment(boolean cancelled) throws Exception {
        onTickThread(() -> {
            var scene = new PaperRollbackPrivateQueriesNativeTest.Scene(); var state = prepare(scene); var player = state.ownedPlayer();
            assertTrue(state.requestGlide()); player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            scene.combat.cancel = cancelled; step(state); assertEquals(cancelled, player.isFallFlying());
            assertEquals(cancelled, state.requestGlide());
            scene.combat.cancel = false; assertFalse(state.requestGlide()); assertFalse(player.isFallFlying());
            var foreign = new PaperRollbackPrivateQueriesNativeTest.Scene().players.get(A).ownedPlayer();
            assertThrows(IllegalArgumentException.class, () -> scene.world.requestGlide(foreign)); return null;
        });
    }
    private static PaperRollbackNativePlayerState prepare(PaperRollbackPrivateQueriesNativeTest.Scene scene) {
        var state = scene.players.get(A); var player = state.ownedPlayer();
        scene.players.get(B).ownedPlayer().setPos(3, 1, 3);
        player.setPos(.5, 3, .5); player.setDeltaMovement(.15, -.1, .2); player.setOnGround(false);
        player.getAbilities().mayfly = false; player.getAbilities().flying = false;
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA)); return state;
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void climbingStopsGlidingThroughPrivateNativeEvents(boolean cancelled) throws Exception {
        onTickThread(() -> {
            var scene = new PaperRollbackPrivateQueriesNativeTest.Scene(); var state = prepare(scene); var player = state.ownedPlayer();
            scene.block(new net.minecraft.core.BlockPos(0, 2, 0), com.projectkorra.projectkorra.platform.mc.Material.LADDER, "minecraft:ladder[facing=north,waterlogged=false]");
            player.setPos(.5, 2, .5); player.setDeltaMovement(Vec3.ZERO); assertTrue(state.requestGlide());
            scene.combat.cancel = cancelled; step(state);
            assertTrue(player.onClimbable()); assertEquals(cancelled, player.isFallFlying()); assertNull(org.bukkit.Bukkit.getServer()); return null;
        });
    }
    @Test void nativeGlidingWallDamageAndMomentumRewindTogether() throws Exception {
        onTickThread(() -> {
            var scene = new PaperRollbackPrivateQueriesNativeTest.Scene(); var state = prepare(scene); var player = state.ownedPlayer();
            for (int y = 1; y <= 4; y++) scene.block(new net.minecraft.core.BlockPos(1, y, 0),
                    com.projectkorra.projectkorra.platform.mc.Material.STONE, "minecraft:stone");
            player.setPos(.5, 2, .5); player.setDeltaMovement(1.5, 0, 0); player.setHealth(20); assertTrue(state.requestGlide());
            var saved = new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(scene.queries), List.of());
            wallStep(state); var position = player.position(); var velocity = player.getDeltaMovement(); float health = player.getHealth();
            assertTrue(player.horizontalCollision); assertTrue(health < 20);
            saved.restore(); assertEquals(20, player.getHealth()); wallStep(state);
            assertEquals(position, player.position()); assertEquals(velocity, player.getDeltaMovement()); assertEquals(health, player.getHealth());
            assertNull(org.bukkit.Bukkit.getServer()); return null;
        });
    }
    private static void wallStep(PaperRollbackNativePlayerState state) {
        try (var clock = RollbackClock.at(1000, 0, 1, 50_000_000)) {
            state.movementInput(new RollbackMovementInput(0, 0, false, -90, 0)); state.tick();
        }
    }
    private static void step(PaperRollbackNativePlayerState state) {
        try (var clock = RollbackClock.at(1000, 0, 1, 50_000_000)) {
            state.movementInput(new RollbackMovementInput(0, 0, false, 30, -15)); state.tick();
        }
    }
}
