package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackClientRuntime;
import com.projectkorra.projectkorra.prediction.rollback.RollbackInputActions;
import com.projectkorra.projectkorra.prediction.rollback.RollbackMovementInput;
import net.minecraft.client.input.Input;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

/** Converts native bending controls to generic rollback intent, without looking up any ability name. */
public final class FabricRollbackInput {
    private final RollbackClientRuntime<?, ?> runtime;
    private boolean sneaking;
    private int selectedSlot;
    private long blockUntil = -1;

    public FabricRollbackInput(RollbackClientRuntime<?, ?> runtime, boolean initialSneaking) {
        this(runtime, initialSneaking, -1);
    }
    public FabricRollbackInput(RollbackClientRuntime<?, ?> runtime, boolean initialSneaking, int initialSlot) {
        if (initialSlot < -1 || initialSlot > 8) throw new IllegalArgumentException("Initial input slot");
        this.runtime = Objects.requireNonNull(runtime); sneaking = initialSneaking; selectedSlot = initialSlot;
    }

    /** Packets that must not mutate the normal gameplay path while the private session owns input. */
    public static boolean gameplay(Packet<?> packet) {
        return packet instanceof PlayerMoveC2SPacket || packet instanceof PlayerInputC2SPacket
                || packet instanceof HandSwingC2SPacket || packet instanceof UpdateSelectedSlotC2SPacket
                || packet instanceof PlayerActionC2SPacket || packet instanceof ClientCommandC2SPacket
                || packet instanceof PlayerInteractItemC2SPacket || packet instanceof PlayerInteractBlockC2SPacket
                || packet instanceof PlayerInteractEntityC2SPacket || packet instanceof UpdatePlayerAbilitiesC2SPacket
                || packet instanceof VehicleMoveC2SPacket || packet instanceof BoatPaddleStateC2SPacket
                || packet instanceof ClickSlotC2SPacket || packet instanceof CreativeInventoryActionC2SPacket
                || packet instanceof ButtonClickC2SPacket || packet instanceof CraftRequestC2SPacket;
    }

    public void packet(Packet<?> packet, long tick, float yaw, float pitch) {
        if (!gameplay(packet)) throw new IllegalArgumentException("Not a gameplay packet");
        if (!runtime.running()) return; // Preparation/failed cleanup cannot schedule an action.
        if (packet instanceof PlayerMoveC2SPacket) return; // Native positions are never authoritative inputs.
        if (packet instanceof UpdatePlayerAbilitiesC2SPacket abilities) { flight(abilities.isFlying(), yaw, pitch); return; }
        if (packet instanceof PlayerInputC2SPacket input) { sneak(input.input().sneak(), yaw, pitch); return; }
        if (packet instanceof UpdateSelectedSlotC2SPacket slot) {
            if (slot.getSelectedSlot() < 0 || slot.getSelectedSlot() > 8) throw new IllegalArgumentException("Input slot");
            if (slot.getSelectedSlot() != selectedSlot && runtime.action(RollbackInputActions.Kind.SLOT_CHANGE, slot.getSelectedSlot(), yaw, pitch)) selectedSlot = slot.getSelectedSlot();
            return;
        }
        if (packet instanceof HandSwingC2SPacket) {
            if (blockUntil <= tick) action(RollbackInputActions.Kind.SWING, -1, yaw, pitch);
            return;
        }
        if (packet instanceof PlayerActionC2SPacket action && action.getAction() == PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
            action(RollbackInputActions.Kind.SWAP_HANDS, -1, yaw, pitch); return;
        }
        if (packet instanceof ClientCommandC2SPacket command
                && (command.getMode() == ClientCommandC2SPacket.Mode.START_SPRINTING || command.getMode() == ClientCommandC2SPacket.Mode.STOP_SPRINTING)) return;
        if (packet instanceof ClientCommandC2SPacket command && command.getMode() == ClientCommandC2SPacket.Mode.START_FALL_FLYING) {
            glide(yaw, pitch); return;
        }
        if (packet instanceof PlayerInteractBlockC2SPacket block) {
            blockUntil = Math.addExact(tick, 2);
            if (block.getHand() == Hand.MAIN_HAND) action(RollbackInputActions.Kind.RIGHT_CLICK_BLOCK, -1, yaw, pitch);
            else throw unsupported(packet);
            return;
        }
        if (packet instanceof PlayerInteractItemC2SPacket item) {
            if (item.getHand() != Hand.MAIN_HAND) throw unsupported(packet);
            if (blockUntil <= tick) action(RollbackInputActions.Kind.RIGHT_CLICK, -1, item.getYaw(), item.getPitch());
            return;
        }
        if (packet instanceof PlayerInteractEntityC2SPacket entity) {
            entity.handle(new PlayerInteractEntityC2SPacket.Handler() {
                @Override public void interact(Hand hand) { throw unsupported(packet); }
                @Override public void interactAt(Hand hand, Vec3d position) {
                    if (hand != Hand.MAIN_HAND) throw unsupported(packet);
                    action(RollbackInputActions.Kind.RIGHT_CLICK_ENTITY, -1, yaw, pitch);
                }
                @Override public void attack() { throw unsupported(packet); }
            });
            return;
        }
        // These native interactions need their own replayable intent/services before live activation.
        // Do not leak them into vanilla or pretend an item/drop/attack is merely a bending click.
        throw unsupported(packet);
    }

    public RollbackClientRuntime.Held sample(Input input, float yaw, float pitch, boolean sprinting) {
        sneak(input.playerInput.sneak(), yaw, pitch);
        var movement = input.getMovementInput();
        return new RollbackClientRuntime.Held(new RollbackMovementInput(movement.x, movement.y, input.playerInput.jump(), yaw, pitch), sprinting);
    }
    public void flight(boolean flying, float yaw, float pitch) {
        action(flying ? RollbackInputActions.Kind.FLIGHT_START : RollbackInputActions.Kind.FLIGHT_STOP, -1, yaw, pitch);
    }
    public void glide(float yaw, float pitch) { action(RollbackInputActions.Kind.GLIDE_START, -1, yaw, pitch); }
    private void sneak(boolean next, float yaw, float pitch) {
        if (next != sneaking && runtime.action(next ? RollbackInputActions.Kind.SNEAK_START : RollbackInputActions.Kind.SNEAK_STOP, -1, yaw, pitch)) sneaking = next;
    }
    private void action(RollbackInputActions.Kind kind, int slot, float yaw, float pitch) { runtime.action(kind, slot, yaw, pitch); }
    private static UnsupportedOperationException unsupported(Packet<?> packet) {
        return new UnsupportedOperationException("Private native input service is not bound for " + packet.getClass().getSimpleName());
    }
}
