package com.projectkorra.projectkorra.listener;

import com.jedk1.jedcore.ability.firebending.FirePunch;
import com.jedk1.jedcore.ability.firebending.FireShots;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.activation.ActivationContext;
import com.projectkorra.projectkorra.ability.activation.AddonSlotActivation;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.airbending.AirBlast;
import com.projectkorra.projectkorra.airbending.AirScooter;
import com.projectkorra.projectkorra.airbending.Suffocate;
import com.projectkorra.projectkorra.board.BendingBoardManager;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.earthbending.EarthBlast;
import com.projectkorra.projectkorra.earthbending.EarthGrab;
import com.projectkorra.projectkorra.earthbending.passive.FerroControl;
import com.projectkorra.projectkorra.event.PlayerSwingEvent;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.GameMode;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.ChatUtil;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.MovementHandler;
import com.projectkorra.projectkorra.waterbending.Torrent;
import com.projectkorra.projectkorra.waterbending.blood.Bloodbending;
import com.projectkorra.projectkorra.waterbending.multiabilities.WaterArms;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;

import java.util.Set;
import java.util.UUID;

public final class CommonInputHandler {
    private CommonInputHandler() {
    }

    public static InputResult handleRightClick(final Player player, final ClickType clickType) {
        if (player == null || player.getGameMode() == GameMode.SPECTATOR) {
            return InputResult.pass();
        }
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (clickType == ClickType.RIGHT_CLICK_BLOCK && bPlayer != null && bPlayer.canCurrentlyBendWithWeapons()) {
            ComboManager.scheduleComboAbility(player, ClickType.RIGHT_CLICK_BLOCK);
        }

        boolean cancelled = false;
        if (clickType == ClickType.RIGHT_CLICK_BLOCK || clickType == ClickType.RIGHT_CLICK) {
            final ActivationContext context = new ActivationContext(player, bPlayer, clickType);
            AbilityActivationManager.dispatch(context);
            cancelled = context.shouldCancelEvent();
        }

        if (MovementHandler.isStopped(player) || Bloodbending.isBloodbent(player) || Suffocate.isBreathbent(player)) {
            cancelled = true;
        }

        finishRightClickCombo(player);
        return new InputResult(cancelled);
    }

    public static InputResult handleRightClickEntity(final Player player) {
        if (player == null || player.getGameMode() == GameMode.SPECTATOR) {
            return InputResult.pass();
        }
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer != null && bPlayer.canCurrentlyBendWithWeapons()) {
            ComboManager.scheduleComboAbility(player, ClickType.RIGHT_CLICK_ENTITY);
        }

        boolean cancelled = MovementHandler.isStopped(player) || Bloodbending.isBloodbent(player) || Suffocate.isBreathbent(player);
        if (!cancelled) {
            final ActivationContext context = new ActivationContext(player, bPlayer, ClickType.RIGHT_CLICK_ENTITY);
            AbilityActivationManager.dispatch(context);
            cancelled = context.shouldCancelEvent();
        }

        ComboManager.addComboAbilityIfValid(player, ClickType.RIGHT_CLICK_ENTITY);
        return new InputResult(cancelled);
    }

    public static InputResult handleSneak(final Player player, final boolean wasSneaking) {
        if (player == null || player.getGameMode() == GameMode.SPECTATOR) {
            return InputResult.pass();
        }
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null) {
            return InputResult.pass();
        }

        final ClickType type = wasSneaking ? ClickType.SHIFT_UP : ClickType.SHIFT_DOWN;
        if (bPlayer.canCurrentlyBendWithWeapons()) {
            ComboManager.scheduleComboAbility(player, type);
        }

        final String abilityName = bPlayer.getBoundAbilityName();
        if (Suffocate.isBreathbent(player)) {
            if (!(abilityName.equalsIgnoreCase("AirSwipe") || abilityName.equalsIgnoreCase("FireBlast")
                    || abilityName.equalsIgnoreCase("EarthBlast") || abilityName.equalsIgnoreCase("WaterManipulation"))) {
                if (!wasSneaking) {
                    finishSneakCombo(player);
                    return InputResult.cancel();
                }
            }
        }

        if (MovementHandler.isStopped(player) || Bloodbending.isBloodbent(player)) {
            if (!wasSneaking) {
                finishSneakCombo(player);
                return InputResult.cancel();
            }
        }

        if (bPlayer.isChiBlocked()) {
            finishSneakCombo(player);
            return InputResult.cancel();
        }

        if (!wasSneaking) {
            BlockSource.update(player, ClickType.SHIFT_DOWN);
        }

        AirScooter.check(player);

        final CoreAbility coreAbility = bPlayer.getBoundAbility();
        if (coreAbility == null || !coreAbility.isSneakAbility()) {
            if (PassiveManager.hasPassive(player, CoreAbility.getAbility(FerroControl.class))) {
                new FerroControl(player);
            }
            if (!wasSneaking && PassiveManager.hasPassive(player, CoreAbility.getAbility(FastSwim.class))) {
                // This method represents an input edge. Explicitly identify
                // shift-down so Fabric, Paper prediction, and Bukkit events do
                // not depend on whether Player#isSneaking exposes the old or
                // new packet state while the constructor runs.
                new FastSwim(player, true);
            }
        }

        if (coreAbility != null && !wasSneaking) {
            final ActivationContext context = new ActivationContext(player, bPlayer, ClickType.SHIFT_DOWN);
            AbilityActivationManager.dispatch(context);
            finishSneakCombo(player);
            return new InputResult(context.shouldCancelEvent());
        }

        finishSneakCombo(player);
        return InputResult.pass();
    }

    public static SlotResult handleSlotChange(final Player player, final int selectedSlotZeroBased) {
        if (!MultiAbilityManager.canChangeSlot(player, selectedSlotZeroBased)) {
            return SlotResult.reject();
        }

        final int slot = selectedSlotZeroBased + 1;
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer != null) {
            bPlayer.setCurrentSlot(selectedSlotZeroBased);
        }
        ChatUtil.displayMovePreview(player, slot);
        BendingBoardManager.changeActiveSlot(player, slot);
        if (player.getGameMode() != GameMode.SPECTATOR) {
            AddonSlotActivation.handleSlotChange(player, slot);
        }

        if (ConfigManager.defaultConfig.get().getBoolean("Abilities.Water.WaterArms.DisplayBoundMsg")) {
            final WaterArms waterArms = CoreAbility.getAbility(player, WaterArms.class);
            if (waterArms != null) {
                waterArms.displayBoundMsg(slot);
            }
        }

        return SlotResult.accept();
    }

    public static InputResult handleSwapHands(final Player player, final boolean emptyMainHand, final boolean emptyOffHand) {
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null) {
            return InputResult.pass();
        }
        if (emptyMainHand && emptyOffHand) {
            ComboManager.scheduleComboAbility(player, ClickType.OFFHAND_TRIGGER);
        }
        ComboManager.addComboAbilityIfValid(player, ClickType.OFFHAND_TRIGGER);
        return InputResult.pass();
    }

    public static InputResult handleSwing(final Player player, final Set<UUID> rightClickInteract, final Set<UUID> droppedItems) {
        if (player == null || player.getGameMode() == GameMode.SPECTATOR) {
            return InputResult.pass();
        }
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (droppedItems.remove(player.getUniqueId())) {
            return InputResult.pass();
        }
        if (bPlayer == null) {
            return InputResult.pass();
        }
        if (rightClickInteract.contains(player.getUniqueId())) {
            return InputResult.pass();
        }

        bPlayer.registerClick();

        final ClickType comboClick = getLeftClickComboType(player);
        if (bPlayer.canCurrentlyBendWithWeapons()) {
            ComboManager.scheduleComboAbility(player, comboClick);
        }

        if (Suffocate.isBreathbent(player)) {
            finishSwingCombo(player);
            return InputResult.cancel();
        } else if (Bloodbending.isBloodbent(player) && !bPlayer.getBoundAbilityName().equalsIgnoreCase("AvatarState")) {
            finishSwingCombo(player);
            return InputResult.cancel();
        } else if (MovementHandler.isStopped(player)) {
            if (player.hasMetadata("movement:stop")) {
                final Object ability = player.getMetadata("movement:stop").get(0).value();
                if (!(ability instanceof EarthGrab)) {
                    finishSwingCombo(player);
                    return InputResult.cancel();
                }
            }
        } else if (bPlayer.isChiBlocked()) {
            finishSwingCombo(player);
            return InputResult.cancel();
        }

        final PlayerSwingEvent swingEvent = new PlayerSwingEvent(player);
        Platform.events().call(swingEvent);
        if (swingEvent.isCancelled()) {
            finishSwingCombo(player);
            return InputResult.cancel();
        }

        BlockSource.update(player, ClickType.LEFT_CLICK);
        final InputResult stagedResult = handleStagedLeftClick(player, bPlayer);
        if (stagedResult.cancelEvent()) {
            finishSwingCombo(player);
            return stagedResult;
        }
        final boolean canRideScooter = !AirScooter.check(player);

        final ActivationContext context = new ActivationContext(player, bPlayer, ClickType.LEFT_CLICK);
        context.put("canRideScooter", canRideScooter);

        AbilityActivationManager.dispatchGlobal(context);
        if (context.shouldStopProcessing()) {
            finishSwingCombo(player);
            return new InputResult(context.shouldCancelEvent());
        }

        final CoreAbility coreAbility = bPlayer.getBoundAbility();
        if (coreAbility == null && !MultiAbilityManager.hasMultiAbilityBound(player)) {
            finishSwingCombo(player);
            return new InputResult(context.shouldCancelEvent());
        }

        AbilityActivationManager.dispatchBoundAbility(context);
        if (!context.shouldCancelEvent() && !context.shouldStopProcessing()) {
            AbilityActivationManager.dispatchMultiAbility(context);
        }

        finishSwingCombo(player);
        return new InputResult(context.shouldCancelEvent());
    }

    private static InputResult handleStagedLeftClick(final Player player, final BendingPlayer bPlayer) {
        final String abilityName = bPlayer.getBoundAbilityName();
        if ("AirBlast".equalsIgnoreCase(abilityName)) {
            for (final AirBlast blast : CoreAbility.getAbilities(player, AirBlast.class)) {
                if (blast.getSource() == null && !blast.isProgressing()) {
                    blast.setFromOtherOrigin(true);
                    blast.shoot();
                    AbilityActivationManager.markHandled(blast);
                    return InputResult.cancel();
                }
            }
        }
        if ("EarthBlast".equalsIgnoreCase(abilityName)) {
            for (final EarthBlast blast : CoreAbility.getAbilities(player, EarthBlast.class)) {
                if (!blast.isProgressing()) {
                    EarthBlast.throwEarth(player);
                    AbilityActivationManager.markHandled();
                    return InputResult.cancel();
                }
            }
        }
        if ("Torrent".equalsIgnoreCase(abilityName) && CoreAbility.hasAbility(player, Torrent.class)) {
            new Torrent(player);
            AbilityActivationManager.markHandled();
            return InputResult.cancel();
        }
        if ("FireShots".equalsIgnoreCase(abilityName) && CoreAbility.hasAbility(player, FireShots.class)) {
            FireShots.fireShot(player);
            AbilityActivationManager.markHandled();
            return InputResult.cancel();
        }
        return InputResult.pass();
    }

    public static boolean shouldTrackDrop(final Player player) {
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        return bPlayer != null && bPlayer.getBoundAbility() != null;
    }

    public static boolean handleEntityLeftClick(final Player player, final LivingEntity target) {
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null || target == null || player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        if (!JedCoreConfig.getConfig(bPlayer).getBoolean("Abilities.Fire.FirePunch.ActivationOnPunch")) {
            return false;
        }
        // Match the legacy listener: an already prepared punch is consumed
        // directly. Re-running generic activation checks here could reject the
        // very instance selected on the preceding slot-change event.
        final FirePunch prepared = CoreAbility.getAbility(player, FirePunch.class);
        if (prepared != null) {
            if (!"FirePunch".equalsIgnoreCase(bPlayer.getBoundAbilityName())) {
                prepared.remove();
                return false;
            }
            prepared.punch(target);
            return true;
        }
        final CoreAbility firePunch = CoreAbility.getAbility(FirePunch.class);
        final String bound = bPlayer.getBoundAbilityName();
        if (firePunch != null && "FirePunch".equalsIgnoreCase(bound)
                && bPlayer.canBendIgnoreBinds(firePunch)) {
            new FirePunch(player, target);
            return true;
        }
        return false;
    }

    private static ClickType getLeftClickComboType(final Player player) {
        final Entity target = GeneralMethods.getTargetedEntity(player, 3);
        if (target instanceof LivingEntity && !target.equals(player)) {
            return ClickType.LEFT_CLICK_ENTITY;
        }
        return ClickType.LEFT_CLICK;
    }

    private static void finishRightClickCombo(final Player player) {
        ComboManager.addComboAbilityIfValid(player, ClickType.RIGHT_CLICK_BLOCK);
        ComboManager.addComboAbilityIfValid(player, ClickType.RIGHT_CLICK);
    }

    private static void finishSneakCombo(final Player player) {
        ComboManager.addComboAbilityIfValid(player, ClickType.SHIFT_DOWN);
        ComboManager.addComboAbilityIfValid(player, ClickType.SHIFT_UP);
    }

    private static void finishSwingCombo(final Player player) {
        ComboManager.addComboAbilityIfValid(player, ClickType.LEFT_CLICK_ENTITY);
        ComboManager.addComboAbilityIfValid(player, ClickType.LEFT_CLICK);
    }

    public record InputResult(boolean cancelEvent) {
        public static InputResult pass() {
            return new InputResult(false);
        }

        public static InputResult cancel() {
            return new InputResult(true);
        }
    }

    public record SlotResult(boolean accepted) {
        public static SlotResult accept() {
            return new SlotResult(true);
        }

        public static SlotResult reject() {
            return new SlotResult(false);
        }
    }
}
