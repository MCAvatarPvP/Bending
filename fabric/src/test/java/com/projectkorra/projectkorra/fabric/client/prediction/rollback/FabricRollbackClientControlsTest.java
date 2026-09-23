package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackPlayerRendererTest.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackClientControlsTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void nativeSprintPredicatesAndPhysicalDoubleTapWorkWithoutAdvancingThePrivatePlayer() throws Exception {
        var state = source(); var player = state.ownedPlayer(); var keys = new Keys(); var controls = new FabricRollbackClientControls(state);
        var position = player.getEntityPos(); var velocity = player.getVelocity(); float health = player.getHealth(); int age = player.age;
        keys.next = keys(true, false, false, false);
        controls.tick(1, keys, 7, false); assertFalse(controls.sprinting(1));
        keys.next = PlayerInput.DEFAULT; controls.tick(2, keys, 7, false);
        keys.next = keys(true, false, false, false);
        controls.tick(3, keys, 7, false); assertTrue(controls.sprinting(3));
        controls.tick(4, keys, 7, false); assertTrue(controls.sprinting(4), "Double-tap intent must survive an authority wait with no simulation advance");
        assertFalse(player.isSprinting(), "Input must not mutate the private body before its logical tick");
        player.setSprinting(true); controls.tick(5, keys, 7, false); assertTrue(controls.sprinting(5));
        player.setSprinting(false); controls.tick(6, keys, 7, false); assertFalse(controls.sprinting(6), "An ability/correction can revoke sprinting");
        keys.next = keys(true, false, false, true); controls.tick(7, keys, 7, false); assertTrue(controls.sprinting(7));
        keys.next = PlayerInput.DEFAULT; controls.tick(8, keys, 7, false); assertFalse(controls.sprinting(8));
        assertEquals(8, keys.ticks); assertEquals(position, player.getEntityPos()); assertEquals(velocity, player.getVelocity());
        assertEquals(health, player.getHealth()); assertEquals(age, player.age);
    }

    @Test void nativeEligibilityUsesPredictedHungerPoseEffectsCollisionsAndActiveItem() throws Exception {
        var state = source(); var player = state.ownedPlayer(); var keys = new Keys(); var controls = new FabricRollbackClientControls(state);
        keys.next = keys(true, false, false, true);
        player.getHungerManager().setFoodLevel(6); controls.tick(1, keys, 7, false); assertFalse(controls.sprinting(1));
        player.getHungerManager().setFoodLevel(20); controls.tick(2, keys, 7, false); assertTrue(controls.sprinting(2));
        player.horizontalCollision = true; player.collidedSoftly = false;
        controls.tick(3, keys, 7, false); assertFalse(controls.sprinting(3));
        player.horizontalCollision = false;
        player.setPose(EntityPose.CROUCHING); controls.tick(4, keys, 7, false); assertFalse(controls.sprinting(4));
        player.setPose(EntityPose.STANDING);
        state.use(value -> value.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 20)));
        controls.tick(5, keys, 7, false); assertFalse(controls.sprinting(5));
        state.use(value -> value.removeStatusEffect(StatusEffects.BLINDNESS));
        state.use(value -> { value.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.APPLE, 4)); value.setCurrentHand(Hand.MAIN_HAND); return null; });
        assertTrue(player.isUsingItem());
        controls.tick(6, keys, 7, false); assertFalse(controls.sprinting(6));
        assertEquals(4, player.getActiveItem().getCount());
        state.use(value -> { value.clearActiveItem(); return null; });
        controls.tick(7, keys, 7, false); assertTrue(controls.sprinting(7));
    }

    @Test void inputClockIsStrictAndExistingGlidingDoesNotRunOrdinaryMovement() throws Exception {
        var state = source(); var player = state.ownedPlayer(); var keys = new Keys(); var controls = new FabricRollbackClientControls(state);
        assertThrows(IllegalStateException.class, () -> controls.sprinting(1));
        controls.tick(1, keys, 7, true); assertEquals(1, keys.ticks); assertFalse(keys.playerInput.jump());
        assertThrows(IllegalStateException.class, () -> controls.tick(1, keys, 7, false));
        assertThrows(IllegalStateException.class, () -> controls.tick(3, keys, 7, false));
        assertThrows(IllegalStateException.class, () -> controls.sprinting(2));
        var failure = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> controls.tick(2, keys, 7, false)).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        ((com.projectkorra.projectkorra.fabric.mixin.client.EntityRollbackControlAccess) player).rollback$setFlag(7, true);
        controls.tick(2, keys, 7, false); assertTrue(player.isGliding()); assertFalse(controls.glideRequest(2));
    }

    @Test void airborneJumpSamplesNativeGlideEligibilityWithoutStartingOrRepeatingGliding() throws Exception {
        var state = source(); var player = state.ownedPlayer(); var keys = new Keys(); var controls = new FabricRollbackClientControls(state);
        player.setOnGround(false); player.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        var position = player.getEntityPos(); var velocity = player.getVelocity();
        keys.next = keys(false, true, false, false); controls.tick(1, keys, 7, false);
        assertTrue(controls.glideRequest(1)); assertFalse(player.isGliding());
        controls.tick(2, keys, 7, false); assertFalse(controls.glideRequest(2));
        keys.next = PlayerInput.DEFAULT; controls.tick(3, keys, 7, false);
        keys.next = keys(false, true, false, false); controls.tick(4, keys, 7, false);
        assertFalse(controls.glideRequest(4), "An authority wait must retain the pending glide intent");
        player.age++; keys.next = PlayerInput.DEFAULT; controls.tick(5, keys, 7, false);
        keys.next = keys(false, true, false, false); controls.tick(6, keys, 7, false);
        assertTrue(controls.glideRequest(6), "After a rejected simulated request, a later jump can request again");
        assertEquals(position, player.getEntityPos()); assertEquals(velocity, player.getVelocity()); assertFalse(player.isGliding());
    }

    @Test void glideInputRequiresNativeEquipmentContactAndEffectEligibility() throws Exception {
        for (String invalid : List.of("ground", "equipment", "levitation", "flying")) {
            var state = source(); var player = state.ownedPlayer(); var keys = new Keys(); var controls = new FabricRollbackClientControls(state);
            player.setOnGround(invalid.equals("ground"));
            if (!invalid.equals("equipment")) player.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
            if (invalid.equals("flying")) { player.getAbilities().allowFlying = true; player.getAbilities().flying = true; }
            if (invalid.equals("levitation")) state.use(p -> p.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 30)));
            keys.next = keys(false, true, false, false); controls.tick(1, keys, 7, false);
            assertFalse(controls.glideRequest(1), invalid); assertFalse(player.isGliding());
        }
    }

    @Test void nativeTickAndCommandTransportCarryOneGlideEdgeWithCurrentAim() throws Exception {
        var state = source(); var player = state.ownedPlayer(); player.setOnGround(false);
        player.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        var keys = new Keys(); var world = RollbackNativeQueryShell.create(World.class).instance();
        var sent = new ArrayList<RollbackInputPacket>(); var stopped = new ArrayList<RollbackStartServerEndpoint.Failure>();
        var visible = visible(world, keys, new float[]{-60, -15}); var runtime = runtime(state, visible, sent, stopped); runtime.start(100);
        keys.next = keys(false, true, false, false); runtime.nativeTick(101); runtime.tick(101);
        assertEquals(List.of(new RollbackInputPacket.Edge(1, RollbackInputActions.Kind.GLIDE_START, -1, -60, -15)), sent.getFirst().actions());
        runtime.nativeTick(102); runtime.tick(102); assertTrue(sent.getLast().actions().isEmpty());
        assertFalse(player.isGliding());
        runtime.packet(new net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket(visible,
                net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket.Mode.START_FALL_FLYING), 102);
        runtime.nativeTick(103); runtime.tick(103);
        assertEquals(List.of(new RollbackInputPacket.Edge(2, RollbackInputActions.Kind.GLIDE_START, -1, -60, -15)), sent.getLast().actions());
        assertTrue(stopped.isEmpty());
    }

    @Test void flightDoubleTapRetainsIntentDuringAWaitAndResynchronizesAfterARejectedRequest() throws Exception {
        var state = source(); var player = state.ownedPlayer(); var keys = new Keys(); var controls = new FabricRollbackClientControls(state);
        player.getAbilities().allowFlying = true;
        keys.next = keys(false, true, false, false); controls.tick(1, keys, 7, false); assertNull(controls.flightRequest(1));
        keys.next = PlayerInput.DEFAULT; controls.tick(2, keys, 7, false);
        keys.next = keys(false, true, false, false); controls.tick(3, keys, 7, false); assertEquals(true, controls.flightRequest(3));
        assertFalse(player.getAbilities().flying); assertEquals(0, player.getVelocity().y);
        controls.tick(4, keys, 7, false); assertNull(controls.flightRequest(4), "Holding jump cannot repeat the request");
        keys.next = PlayerInput.DEFAULT; controls.tick(5, keys, 7, false);
        keys.next = keys(false, true, false, false); controls.tick(6, keys, 7, false);
        keys.next = PlayerInput.DEFAULT; controls.tick(7, keys, 7, false);
        keys.next = keys(false, true, false, false); controls.tick(8, keys, 7, false); assertEquals(false, controls.flightRequest(8));
        player.age++; // An executed frame rejected the first request, leaving the native flag false.
        keys.next = PlayerInput.DEFAULT; controls.tick(9, keys, 7, false);
        keys.next = keys(false, true, false, false); controls.tick(10, keys, 7, false);
        keys.next = PlayerInput.DEFAULT; controls.tick(11, keys, 7, false);
        keys.next = keys(false, true, false, false); controls.tick(12, keys, 7, false); assertEquals(true, controls.flightRequest(12));
        assertFalse(player.getAbilities().flying);
    }

    @Test void nativeTickTransportSendsAFlightEdgeOnlyOnceWithTheCurrentAim() throws Exception {
        var state = source(); state.ownedPlayer().getAbilities().allowFlying = true;
        var keys = new Keys(); var world = RollbackNativeQueryShell.create(World.class).instance();
        var sent = new ArrayList<RollbackInputPacket>(); var stopped = new ArrayList<RollbackStartServerEndpoint.Failure>();
        var runtime = runtime(state, visible(world, keys, new float[]{75, -20}), sent, stopped); runtime.start(100);
        for (int i = 1; i <= 4; i++) {
            keys.next = i == 2 ? PlayerInput.DEFAULT : keys(false, true, false, false);
            runtime.nativeTick(100 + i); runtime.tick(100 + i);
        }
        assertEquals(4, sent.size()); assertTrue(sent.get(0).actions().isEmpty()); assertTrue(sent.get(1).actions().isEmpty());
        assertEquals(List.of(new RollbackInputPacket.Edge(1, RollbackInputActions.Kind.FLIGHT_START, -1, 75, -20)), sent.get(2).actions());
        assertTrue(sent.get(3).actions().isEmpty()); assertEquals(4, keys.ticks); assertTrue(stopped.isEmpty());
        assertFalse(state.ownedPlayer().getAbilities().flying);
    }

    @Test void flightDoubleTapWindowExpiresAndSwimmingOrRevokedPermissionCannotProduceARequest() throws Exception {
        for (int gap : new int[]{6, 7, 8}) {
            var state = source(); var keys = new Keys(); var controls = new FabricRollbackClientControls(state);
            state.ownedPlayer().getAbilities().allowFlying = true;
            keys.next = keys(false, true, false, false); controls.tick(1, keys, 7, false);
            keys.next = PlayerInput.DEFAULT;
            for (int i = 2; i <= gap; i++) controls.tick(i, keys, 7, false);
            keys.next = keys(false, true, false, false); controls.tick(gap + 1, keys, 7, false);
            assertEquals(gap < 7 ? Boolean.TRUE : null, controls.flightRequest(gap + 1), "Native seven-tick flight window, gap " + gap);
        }
        for (boolean swimming : new boolean[]{false, true}) {
            var state = source(); var player = state.ownedPlayer(); var keys = new Keys(); var controls = new FabricRollbackClientControls(state);
            player.getAbilities().allowFlying = true;
            keys.next = keys(false, true, false, false); controls.tick(1, keys, 7, false);
            keys.next = PlayerInput.DEFAULT; controls.tick(2, keys, 7, false);
            player.setSwimming(swimming); player.getAbilities().allowFlying = swimming;
            keys.next = keys(false, true, false, false); controls.tick(3, keys, 7, false);
            assertNull(controls.flightRequest(3)); assertFalse(player.getAbilities().flying);
        }
    }

    @Test void transformedLocalTickSamplesControlsOnceAndRuntimeSendsThemWithCurrentAimWithoutVanillaMovement() throws Exception {
        var state = source(); var keys = new Keys(); var visibleWorld = RollbackNativeQueryShell.create(World.class).instance();
        float[] aim = {35, -10}; var visible = visible(visibleWorld, keys, aim); visible.age = 50;
        var sent = new ArrayList<RollbackInputPacket>(); var stopped = new ArrayList<RollbackStartServerEndpoint.Failure>();
        var runtime = runtime(state, visible, sent, stopped); long[] tick = {101}; var current = new AtomicBoolean(true);
        try (var lease = new FabricRollbackNativeTick(visible, visibleWorld, current::get, () -> runtime.nativeTick(tick[0]), stoppedFailure -> fail(stoppedFailure))) {
            runtime.start(100); keys.next = keys(true, true, true, true);
            visible.tick(); assertEquals(1, keys.ticks); assertEquals(50, visible.age);
            aim[0] = 80; aim[1] = 20; runtime.tick(101);
            assertEquals(1, sent.size()); var packet = sent.getFirst();
            assertEquals(new RollbackMovementInput(0, 1, true, 80, 20), packet.movement()); assertTrue(packet.sprinting());
            assertEquals(List.of(RollbackInputActions.Kind.SNEAK_START), packet.actions().stream().map(RollbackInputPacket.Edge::kind).toList());
            tick[0] = 102; keys.next = PlayerInput.DEFAULT; visible.tick(); runtime.tick(102);
            assertFalse(sent.getLast().sprinting()); assertEquals(RollbackInputActions.Kind.SNEAK_STOP, sent.getLast().actions().getFirst().kind());
            assertEquals(2, keys.ticks); assertEquals(50, visible.age); assertTrue(stopped.isEmpty());
            assertThrows(IllegalStateException.class, () -> runtime.tick(103), "Missing native sampling must not replay stale keyboard input");
        }
        assertThrows(NullPointerException.class, visible::tick, "Releasing the lease restores the ordinary native tick");
    }

    @Test void failedOrStaleNativeTickCannotFallThroughWhenCleanupReleasesOwnership() throws Exception {
        var world = RollbackNativeQueryShell.create(World.class).instance(); var keys = new Keys(); var player = visible(world, keys, new float[2]);
        var current = new AtomicBoolean(true); var failures = new ArrayList<Throwable>(); var calls = new int[1];
        var holder = new FabricRollbackNativeTick[1];
        try (var lease = new FabricRollbackNativeTick(player, world, current::get, () -> { calls[0]++; throw new IllegalArgumentException("input failed"); },
                failure -> { failures.add(failure); holder[0].close(); })) {
            holder[0] = lease;
            var foreign = visible(world, keys, new float[2]); assertFalse(FabricRollbackNativeTick.tick(foreign));
            player.tick(); assertEquals(1, calls[0]); assertInstanceOf(IllegalArgumentException.class, failures.getFirst());
        }
        try (var lease = new FabricRollbackNativeTick(player, world, current::get, () -> calls[0]++, failure -> { failures.add(failure); holder[0].close(); })) {
            holder[0] = lease; current.set(false); player.tick(); assertEquals(1, calls[0]); assertEquals(2, failures.size());
        }
        assertThrows(NullPointerException.class, player::tick);
    }

    @Test void cleanupFailureRetainsNativeTickOwnershipAndOldCleanupCannotReleaseAReplacement() {
        var world = RollbackNativeQueryShell.create(World.class).instance(); var player = visible(world, new Keys(), new float[2]);
        var cleanup = new IllegalStateException("cleanup failed"); int[] sampled = {0};
        try (var lease = new FabricRollbackNativeTick(player, world, () -> true, () -> { throw new IllegalArgumentException("input failed"); }, failure -> { throw cleanup; })) {
            assertSame(cleanup, assertThrows(IllegalStateException.class, player::tick));
            assertThrows(IllegalStateException.class, () -> new FabricRollbackNativeTick(player, world, () -> true, () -> { }, failure -> { }));
            assertSame(cleanup, assertThrows(IllegalStateException.class, player::tick)); lease.close();
            try (var replacement = new FabricRollbackNativeTick(player, world, () -> true, () -> sampled[0]++, failure -> fail(failure))) {
                lease.close(); player.tick(); assertEquals(1, sampled[0]);
            }
        }
    }

    private static FabricRollbackNativePlayerState source() throws Exception {
        var state = roster().players().get(A); var player = state.ownedPlayer();
        player.setPose(EntityPose.STANDING); player.setSprinting(false); player.horizontalCollision = false;
        player.getAbilities().allowFlying = false; player.getAbilities().flying = false; player.getHungerManager().setFoodLevel(20);
        return state;
    }
    private static PlayerInput keys(boolean forward, boolean jump, boolean sneak, boolean sprint) {
        return new PlayerInput(forward, false, false, false, jump, sneak, sprint);
    }
    private static final class Keys extends Input {
        PlayerInput next = PlayerInput.DEFAULT; int ticks;
        @Override public void tick() { ticks++; playerInput = next; movementVector = new Vec2f(0, next.forward() ? 1 : 0); }
    }
    private static ClientPlayerEntity visible(World world, Keys keys, float[] aim) {
        var player = RollbackNativeQueryShell.create(ClientPlayerEntity.class)
                .constant(PlayerEntity::getEntityWorld, world).constant(PlayerEntity::getUuid, A)
                .constant(PlayerEntity::getId, 17)
                .query(PlayerEntity::getYaw, 0F, args -> aim[0]).query(PlayerEntity::getPitch, 0F, args -> aim[1])
                .nativeAction(ClientPlayerEntity::tick, args -> { }).instance();
        player.input = keys; return player;
    }
    private static FabricRollbackClientRuntime<Integer, Integer> runtime(FabricRollbackNativePlayerState state, ClientPlayerEntity visible,
            List<RollbackInputPacket> sent, List<RollbackStartServerEndpoint.Failure> stopped) {
        var idle = new RollbackPlayerInput(new RollbackMovementInput(0, 0, false, 0, 0), false, List.of());
        var engine = RollbackEngine.replica(new RollbackSimulation<Integer, RollbackPlayerInput, Integer>() {
            int tick;
            @Override public Integer snapshot() { return tick; }
            @Override public void restore(Integer state) { tick = state; }
            @Override public RollbackPlayerInput predict(UUID player, RollbackPlayerInput previous) { return previous.predict(); }
            @Override public void step(long next, Map<UUID, RollbackPlayerInput> inputs, RollbackStep<Integer> effects) { tick++; }
        }, Map.of(A, idle, B, idle), new RollbackEngine.Limits(8, 1, 10, 50_000_000), 0, 0);
        return new FabricRollbackClientRuntime<>(UUID.randomUUID(), 55, visible, engine, sent::add, new RollbackClientRuntime.Output<>() {
            @Override public void update(RollbackEngine.Update<Integer, RollbackPlayerInput, Integer> update) { }
            @Override public void stop(RollbackStartServerEndpoint.Failure failure) { stopped.add(failure); }
        }, state, () -> 7, () -> false);
    }
}
