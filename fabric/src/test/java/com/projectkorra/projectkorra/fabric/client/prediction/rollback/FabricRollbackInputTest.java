package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import net.minecraft.client.input.Input;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackInputTest {
    private static final UUID ID = new UUID(0, 5), A = new UUID(0, 1), B = new UUID(0, 2);

    @Test void nativePacketsPreserveActionAimAndHeldInputWithoutTrustingMovementCoordinates() {
        var f = new Fixture();
        f.input.packet(new HandSwingC2SPacket(Hand.MAIN_HAND), 100, 10, 20);
        f.runtime.start(100);
        f.input.packet(new PlayerMoveC2SPacket.Full(999, 999, 999, -130, 45, false, false), 100, 30, 20);
        f.input.packet(new UpdateSelectedSlotC2SPacket(4), 100, 30, 20);
        f.input.packet(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 99, 70, -25), 100, -90, 30);
        f.input.packet(new HandSwingC2SPacket(Hand.OFF_HAND), 100, 80, 15);
        f.keyboard.playerInput = new PlayerInput(true, false, false, false, true, true, true);
        f.input.packet(new PlayerInputC2SPacket(f.keyboard.playerInput), 100, 80, 15);
        f.input.packet(new PlayerInputC2SPacket(f.keyboard.playerInput), 100, 80, 15);
        f.runtime.tick(101);
        var packet = f.sent.getFirst();
        assertEquals(new RollbackMovementInput(0, 1, true, 90, 5), packet.movement()); assertTrue(packet.sprinting());
        assertEquals(List.of(RollbackInputActions.Kind.SLOT_CHANGE, RollbackInputActions.Kind.RIGHT_CLICK,
                RollbackInputActions.Kind.SWING, RollbackInputActions.Kind.SNEAK_START), packet.actions().stream().map(RollbackInputPacket.Edge::kind).toList());
        assertEquals(List.of(30F, 70F, 80F, 80F), packet.actions().stream().map(RollbackInputPacket.Edge::yaw).toList());
        assertEquals(4, packet.actions().getFirst().slot()); assertEquals(-25, packet.actions().get(1).pitch());
        f.runtime.tick(102); assertTrue(f.sent.getLast().actions().isEmpty());
    }

    @Test void blockInteractionDoesNotGenerateDuplicateItemOrSwingAbilityActions() {
        var f = new Fixture(); f.runtime.start(100);
        f.input.packet(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND,
                new BlockHitResult(new Vec3d(1, 2, 3), Direction.UP, new BlockPos(1, 2, 3), false), 1), 100, 45, 20);
        f.input.packet(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 2, 45, 20), 100, 45, 20);
        f.input.packet(new HandSwingC2SPacket(Hand.MAIN_HAND), 100, 45, 20);
        f.runtime.tick(101);
        assertEquals(List.of(RollbackInputActions.Kind.RIGHT_CLICK_BLOCK), f.sent.getFirst().actions().stream().map(RollbackInputPacket.Edge::kind).toList());
        f.runtime.tick(102);
        f.input.packet(new HandSwingC2SPacket(Hand.MAIN_HAND), 102, 0, 0);
        f.runtime.tick(103);
        assertEquals(RollbackInputActions.Kind.SWING, f.sent.getLast().actions().getFirst().kind());
        assertEquals(2, f.sent.getLast().actions().getFirst().sequence());
    }

    @Test void abilityPacketsCarryOnlyFlightIntentAndNeverRepeatItInMissingFrames() {
        var f = new Fixture(); f.runtime.start(100);
        var abilities = new net.minecraft.entity.player.PlayerAbilities(); abilities.allowFlying = true; abilities.flying = true;
        f.input.packet(new UpdatePlayerAbilitiesC2SPacket(abilities), 100, 10, -15);
        abilities.flying = false;
        f.input.packet(new UpdatePlayerAbilitiesC2SPacket(abilities), 100, 20, 5);
        f.runtime.tick(101);
        assertEquals(List.of(new RollbackInputPacket.Edge(1, RollbackInputActions.Kind.FLIGHT_START, -1, 10, -15),
                new RollbackInputPacket.Edge(2, RollbackInputActions.Kind.FLIGHT_STOP, -1, 20, 5)), f.sent.getFirst().actions());
        f.runtime.tick(102); assertTrue(f.sent.getLast().actions().isEmpty());
    }

    @Test void unboundNativeInteractionsRejectAndUnownedConnectionsKeepNormalTraffic() {
        var f = new Fixture(); f.runtime.start(100);
        var drop = new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.DROP_ITEM, BlockPos.ORIGIN, Direction.DOWN);
        assertTrue(FabricRollbackInput.gameplay(drop));
        assertThrows(UnsupportedOperationException.class, () -> f.input.packet(drop, 100, 0, 0));
        assertThrows(UnsupportedOperationException.class, () -> f.input.packet(new PlayerInteractItemC2SPacket(Hand.OFF_HAND, 1, 0, 0), 100, 0, 0));
        var keepAlive = new KeepAliveC2SPacket(1);
        assertFalse(FabricRollbackInput.gameplay(keepAlive));
        assertFalse(FabricRollbackInput.gameplay(new CommandExecutionC2SPacket("neptune latency")));
        var unprepared = new FabricRollbackStarts();
        assertFalse(unprepared.consumePacket(null, null, drop, 100));
        assertFalse(unprepared.consumePacket(null, null, keepAlive, 100));
        f.input.packet(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN), 100, 0, 0);
        f.runtime.tick(101); assertEquals(RollbackInputActions.Kind.SWAP_HANDS, f.sent.getFirst().actions().getFirst().kind());
    }

    private static final class Fixture {
        final List<RollbackInputPacket> sent = new ArrayList<>();
        final Input keyboard = new Input() { @Override public Vec2f getMovementInput() { return new Vec2f(0, 1); } };
        final RollbackClientRuntime<Integer, Integer> runtime;
        final FabricRollbackInput input;
        Fixture() {
            var idle = new RollbackPlayerInput(new RollbackMovementInput(0, 0, false, 0, 0), false, List.of());
            var engine = RollbackEngine.replica(new RollbackSimulation<Integer, RollbackPlayerInput, Integer>() {
                int tick;
                @Override public Integer snapshot() { return tick; }
                @Override public void restore(Integer state) { tick = state; }
                @Override public RollbackPlayerInput predict(UUID player, RollbackPlayerInput previous) { return previous.predict(); }
                @Override public void step(long next, Map<UUID, RollbackPlayerInput> inputs, RollbackStep<Integer> effects) { tick++; }
            }, Map.of(A, idle, B, idle), new RollbackEngine.Limits(8, 1, 10, 50_000_000), 0, 0);
            runtime = new RollbackClientRuntime<>(ID, A, 42, engine, this::sample, sent::add, new RollbackClientRuntime.Output<>() {
                @Override public void update(RollbackEngine.Update<Integer, RollbackPlayerInput, Integer> update) { }
                @Override public void stop(RollbackStartServerEndpoint.Failure failure) { }
            });
            input = new FabricRollbackInput(runtime, false);
        }
        private RollbackClientRuntime.Held sample() { return input.sample(keyboard, 90, 5, keyboard.playerInput.sprint()); }
    }
}
