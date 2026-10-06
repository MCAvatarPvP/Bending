package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objenesis.ObjenesisStd;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackPlayerRendererTest.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackNativeActionsTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(Hand.class)
    void transformedItemUseCapturesSlotAndAimBeforeNativeUseAndDoesNotRepeatSlotEdges(Hand hand) throws Exception {
        var f = new Fixture(); var stack = new ItemStack(Items.APPLE, 4); f.player.getInventory().setStack(3, stack);
        if (hand == Hand.OFF_HAND) f.player.setStackInHand(hand, stack);
        f.player.getInventory().setSelectedSlot(3); f.player.setYaw(72); f.player.setPitch(-18);
        try (var lease = f.acquire(f.player)) {
            // An uninitialized native manager cannot run its use/packet body. The actual injected entry must intercept first.
            assertSame(ActionResult.CONSUME, f.manager.interactItem(f.player, hand));
            assertSame(stack, f.player.getStackInHand(hand)); assertEquals(4, stack.getCount()); assertFalse(f.player.isUsingItem());
            f.input.packet(new UpdateSelectedSlotC2SPacket(3), 100, 72, -18); // Later native slot synchronization is redundant.
            assertSame(ActionResult.CONSUME, f.manager.interactItem(f.player, hand));
            f.runtime.tick(101);
            var actions = f.sent.getFirst().actions();
            assertEquals(List.of(RollbackInputActions.Kind.SLOT_CHANGE, RollbackInputActions.Kind.RIGHT_CLICK, RollbackInputActions.Kind.RIGHT_CLICK), actions.stream().map(RollbackInputPacket.Edge::kind).toList());
            assertEquals(3, actions.getFirst().slot()); assertEquals(72, actions.get(1).yaw()); assertEquals(-18, actions.get(1).pitch());
            assertEquals(hand == Hand.MAIN_HAND ? RollbackInputActions.Hand.MAIN : RollbackInputActions.Hand.OFF, actions.get(1).hand());
            assertEquals(actions.get(1).hand(), actions.get(2).hand());
            assertTrue(f.failures.isEmpty());
        }
        assertThrows(NullPointerException.class, () -> f.manager.interactItem(f.player, hand), "After release the same manager must resume its native body");
    }

    @ParameterizedTest @ValueSource(strings = {"attack", "inventory", "break", "release", "creative"})
    void unboundInteractionsStopBeforeTouchingNativeItemsEntitiesOrBlocksEvenWhenCleanupReleasesTheLease(String operation) throws Exception {
        var f = new Fixture(); var target = f.roster.players().get(B).ownedPlayer();
        var stack = new ItemStack(Items.APPLE, 4); f.player.getInventory().setStack(0, stack);
        float health = target.getHealth(); var position = target.getEntityPos(); var velocity = target.getVelocity();
        try (var lease = f.acquire(f.player)) {
            switch (operation) {
                case "attack" -> f.manager.attackEntity(f.player, target);
                case "inventory" -> f.manager.clickSlot(0, 36, 0, SlotActionType.PICKUP, f.player);
                case "break" -> assertFalse(f.manager.attackBlock(BlockPos.ORIGIN, Direction.UP));
                case "release" -> f.manager.stopUsingItem(f.player);
                case "creative" -> f.manager.dropCreativeStack(stack);
                default -> throw new AssertionError(operation);
            }
            assertEquals(1, f.failures.size()); assertInstanceOf(UnsupportedOperationException.class, f.failures.getFirst());
            assertSame(stack, f.player.getInventory().getStack(0)); assertEquals(4, stack.getCount());
            assertEquals(health, target.getHealth()); assertEquals(position, target.getEntityPos()); assertEquals(velocity, target.getVelocity());
            assertTrue(f.player.currentScreenHandler.getCursorStack().isEmpty());
            assertThrows(NullPointerException.class, () -> f.manager.interactItem(f.player, Hand.MAIN_HAND));
        }
    }

    @Test void actualClientSwingDropAndBlockUseMethodsAreInterceptedBeforeTheirNativeBodies() throws Exception {
        var f = new Fixture(); var stack = new ItemStack(Items.APPLE, 4); f.player.getInventory().setStack(0, stack);
        var shell = RollbackNativeQueryShell.create(ClientPlayerEntity.class)
                .constant(PlayerEntity::getEntityWorld, f.player.getEntityWorld())
                .constant(PlayerEntity::getInventory, f.player.getInventory())
                .constant(PlayerEntity::getYaw, 30F).constant(PlayerEntity::getPitch, -10F)
                .nativeAction(value -> value.swingHand(Hand.MAIN_HAND), args -> { })
                .nativeQuery(value -> value.dropSelectedItem(false), false, args -> { });
        var player = shell.instance();
        try (var lease = f.acquire(player)) {
            player.swingHand(Hand.MAIN_HAND);
            var hit = new BlockHitResult(new Vec3d(1, 2, 3), Direction.UP, new BlockPos(1, 2, 3), false);
            assertSame(ActionResult.CONSUME, f.manager.interactBlock(player, Hand.MAIN_HAND, hit));
            player.swingHand(Hand.MAIN_HAND); // Native block-use feedback must not add a second bending click.
            f.runtime.tick(101);
            assertEquals(List.of(RollbackInputActions.Kind.SWING, RollbackInputActions.Kind.RIGHT_CLICK_BLOCK), f.sent.getFirst().actions().stream().map(RollbackInputPacket.Edge::kind).toList());
            assertFalse(player.dropSelectedItem(true));
            assertEquals(1, f.failures.size()); assertEquals(4, stack.getCount()); assertSame(stack, f.player.getMainHandStack());
        }
    }

    @Test void exactManagerPlayerAndThreadOwnershipDoNotAffectUnownedCallsAndStaleConnectionCancelsTheCurrentInvocation() throws Exception {
        var f = new Fixture(); var current = new AtomicBoolean(true);
        try (var lease = new FabricRollbackNativeActions(f.manager, f.player, f.player.getEntityWorld(), current::get, f::packet, failure -> { f.failures.add(failure); })) {
            var otherManager = new ObjenesisStd(false).newInstance(ClientPlayerInteractionManager.class);
            var otherPlayer = f.roster.players().get(B).ownedPlayer();
            assertFalse(FabricRollbackNativeActions.packet(otherManager, f.player, () -> { throw new AssertionError("Unowned supplier evaluated"); }));
            assertFalse(FabricRollbackNativeActions.playerPacket(otherPlayer, () -> { throw new AssertionError("Unowned supplier evaluated"); }));
            assertThrows(NullPointerException.class, () -> f.manager.interactItem(otherPlayer, Hand.MAIN_HAND));
            var error = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> f.manager.interactItem(f.player, Hand.MAIN_HAND)).join());
            assertInstanceOf(IllegalStateException.class, error.getCause());
            current.set(false);
            assertSame(ActionResult.CONSUME, f.manager.interactItem(f.player, Hand.MAIN_HAND)); assertEquals(1, f.failures.size());
            assertInstanceOf(IllegalStateException.class, f.failures.getFirst()); assertTrue(f.packets.isEmpty());
        }
    }

    @Test void cleanupFailureKeepsTheNativeMutationGuardUntilExplicitReleaseAndOldCleanupCannotReleaseAReplacement() throws Exception {
        var f = new Fixture(); var cleanup = new IllegalStateException("cleanup failed");
        try (var owner = new FabricRollbackNativeActions(f.manager, f.player, f.player.getEntityWorld(), () -> true, f::packet, failure -> { throw cleanup; })) {
            assertSame(cleanup, assertThrows(IllegalStateException.class, () -> f.manager.clickSlot(0, 36, 0, SlotActionType.PICKUP, f.player)));
            assertThrows(IllegalStateException.class, () -> f.acquire(f.player));
            assertSame(cleanup, assertThrows(IllegalStateException.class, () -> f.manager.stopUsingItem(f.player)));
            owner.close();
            try (var replacement = f.acquire(f.player)) {
                owner.close(); assertSame(ActionResult.CONSUME, f.manager.interactItem(f.player, Hand.MAIN_HAND));
            }
        }
    }

    private static final class Fixture {
        final FabricRollbackRoster roster;
        final PlayerEntity player;
        final ClientPlayerInteractionManager manager = new ObjenesisStd(false).newInstance(ClientPlayerInteractionManager.class);
        final List<RollbackInputPacket> sent = new ArrayList<>();
        final List<Packet<?>> packets = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        final RollbackClientRuntime<Integer, Integer> runtime;
        final FabricRollbackInput input;
        FabricRollbackNativeActions lease;
        Fixture() throws Exception {
            roster = roster(); player = roster.players().get(A).ownedPlayer(); player.getInventory().setSelectedSlot(0);
            var idle = new RollbackPlayerInput(new RollbackMovementInput(0, 0, false, 0, 0), false, List.of());
            var engine = RollbackEngine.replica(new RollbackSimulation<Integer, RollbackPlayerInput, Integer>() {
                int tick;
                @Override public Integer snapshot() { return tick; }
                @Override public void restore(Integer state) { tick = state; }
                @Override public RollbackPlayerInput predict(UUID player, RollbackPlayerInput previous) { return previous.predict(); }
                @Override public void step(long next, Map<UUID, RollbackPlayerInput> inputs, RollbackStep<Integer> effects) { tick++; }
            }, Map.of(A, idle, B, idle), new RollbackEngine.Limits(8, 1, 10, 50_000_000), 0, 0);
            runtime = new RollbackClientRuntime<>(UUID.randomUUID(), A, 42, engine,
                    () -> new RollbackClientRuntime.Held(idle.movement(), false), sent::add, new RollbackClientRuntime.Output<>() {
                @Override public void update(RollbackEngine.Update<Integer, RollbackPlayerInput, Integer> update) { }
                @Override public void stop(RollbackStartServerEndpoint.Failure failure) { }
            });
            input = new FabricRollbackInput(runtime, false, 0); runtime.start(100);
        }
        FabricRollbackNativeActions acquire(PlayerEntity visible) {
            lease = new FabricRollbackNativeActions(manager, visible, visible.getEntityWorld(), () -> true, this::packet,
                    failure -> { failures.add(failure); lease.close(); }); return lease;
        }
        void packet(Packet<?> packet) { packets.add(packet); input.packet(packet, 100, player.getYaw(), player.getPitch()); }
    }
}
