package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import com.projectkorra.projectkorra.util.ClickType;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Ordered, one-shot bending input edges using the same handlers as the live loaders. */
public final class RollbackInputActions {
    private RollbackInputActions() { }

    public enum Kind { SWING, RIGHT_CLICK, RIGHT_CLICK_BLOCK, RIGHT_CLICK_ENTITY, SNEAK_START, SNEAK_STOP, SWAP_HANDS, SLOT_CHANGE, FLIGHT_START, FLIGHT_STOP, GLIDE_START, OFF_HAND_SWING }

    /** Slot is present only for SLOT_CHANGE. Seeds and sequences are assigned/validated by the session. */
    public record Action(long sequence, long seed, Kind kind, int slot) {
        public Action {
            Objects.requireNonNull(kind, "kind");
            if (sequence <= 0 || seed <= 0) throw new IllegalArgumentException("Action identity");
            if (kind == Kind.SLOT_CHANGE ? slot < 0 || slot > 8 : slot != -1) throw new IllegalArgumentException("Action slot");
        }
    }

    /**
     * The adapter installs each action's logical look first. Sneak and selected slot
     * retain their pre-edge values until this dispatcher accepts the transition.
     * This handles bending cancellation and sneak/slot transitions (through the supplied native slot adapter when present), not native item use,
     * hand swapping or melee damage. Those must honor the returned cancellation in
     * the native adapter. Predicted missing frames must omit these one-shot actions.
     */
    public static CommonInputHandler.InputResult dispatch(RollbackPlayer player, Action action) {
        return dispatch(player, action, (slot, cancelled) -> { if (cancelled) return false; player.getInventory().setHeldItemSlot(slot); return true; });
    }

    @FunctionalInterface public interface SlotTransition { boolean select(int slot, boolean cancelled); }

    /** Native execution supplies the accepted slot transition, before any inventory write. */
    public static CommonInputHandler.InputResult dispatch(RollbackPlayer player, Action action, SlotTransition selectSlot) {
        Objects.requireNonNull(selectSlot, "slot transition");
        if (!RollbackDomain.active() || !RollbackClock.active()) throw new IllegalStateException("No combat replay tick");
        Objects.requireNonNull(action, "action");
        RollbackEntityBody.logicalBody(Objects.requireNonNull(player, "player"));
        var result = new CommonInputHandler.InputResult[1];
        PredictionDeterminism.run(action.sequence(), action.seed(), () -> result[0] = switch (action.kind()) {
            case SWING, OFF_HAND_SWING -> CommonInputHandler.handleSwing(player, Set.of(), new HashSet<>());
            case RIGHT_CLICK -> CommonInputHandler.handleRightClick(player, ClickType.RIGHT_CLICK);
            case RIGHT_CLICK_BLOCK -> CommonInputHandler.handleRightClick(player, ClickType.RIGHT_CLICK_BLOCK);
            case RIGHT_CLICK_ENTITY -> CommonInputHandler.handleRightClickEntity(player);
            case SNEAK_START, SNEAK_STOP -> sneak(player, action.kind() == Kind.SNEAK_START);
            case SWAP_HANDS -> CommonInputHandler.handleSwapHands(player,
                    empty(player.getInventory().getItemInMainHand()), empty(player.getInventory().getItemInOffHand()));
            case SLOT_CHANGE -> slot(player, action.slot(), selectSlot);
            case FLIGHT_START, FLIGHT_STOP -> flight(player, action.kind() == Kind.FLIGHT_START);
            case GLIDE_START -> new CommonInputHandler.InputResult(!player.state().requestGlide());
        });
        return result[0];
    }

    private static CommonInputHandler.InputResult sneak(RollbackPlayer player, boolean sneaking) {
        boolean previous = player.isSneaking();
        if (previous == sneaking) return CommonInputHandler.InputResult.pass();
        var result = CommonInputHandler.handleSneak(player, previous);
        if (!result.cancelEvent()) player.setSneaking(sneaking);
        return result;
    }

    private static CommonInputHandler.InputResult slot(RollbackPlayer player, int slot, SlotTransition selectSlot) {
        if (player.getInventory().getHeldItemSlot() == slot) return CommonInputHandler.InputResult.pass();
        boolean cancelled = !CommonInputHandler.handleSlotChange(player, slot).accepted();
        return selectSlot.select(slot, cancelled) ? CommonInputHandler.InputResult.pass() : CommonInputHandler.InputResult.cancel();
    }

    /** Also used at the native post-movement landing boundary, without inventing a key press. */
    static CommonInputHandler.InputResult flight(RollbackPlayer player, boolean flying) {
        if (!player.getAllowFlight()) return CommonInputHandler.InputResult.cancel();
        if (player.isFlying() == flying) return CommonInputHandler.InputResult.pass();
        return new CommonInputHandler.InputResult(!player.state().requestFlight(flying, false));
    }

    private static boolean empty(ItemStack item) { return item == null || item.getType() == Material.AIR; }
}
