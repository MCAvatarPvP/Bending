package com.projectkorra.projectkorra.fabric;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.listener.CommonPlayerListenerCore;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ClickType;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Converts Fabric gameplay input into the loader-neutral activation API. */
public class FabricGameplayBridge {
    private static FabricGameplayBridge active;
    private final Map<UUID, Boolean> sneaking = new HashMap<>();
    private final Map<UUID, Long> rightClickBlockUntilTick = new HashMap<>();
    private final Set<UUID> droppedItem = new HashSet<>();
    private long inputTick;
    private boolean registered;

    void start(MinecraftServer server) {
        active = this;
        if (!registered) {
            registerCallbacks();
            registered = true;
        }
        try {
            GeneralMethods.reloadPlugin(new CommandSender() {
                @Override public void sendMessage(String message) { ProjectKorra.log.info(message); }
            });
        } catch (RuntimeException exception) {
            ProjectKorra.log.severe("Unable to start the common runtime on Fabric: " + exception.getMessage());
            throw exception;
        }
        server.getPlayerManager().getPlayerList().forEach(this::join);
    }

    void tick(MinecraftServer server) {
        inputTick++;
        rightClickBlockUntilTick.entrySet().removeIf(entry -> entry.getValue() <= inputTick);
    }

    void stop() {
        CoreAbility.removeAll();
        sneaking.clear();
        rightClickBlockUntilTick.clear();
        droppedItem.clear();
        active = null;
    }

    private void registerCallbacks() {
        ServerPlayerEvents.JOIN.register(this::join);
        ServerPlayerEvents.LEAVE.register(this::leave);
    }

    public static boolean onArmSwing(ServerPlayerEntity player) {
        FabricGameplayBridge bridge = active;
        if (bridge == null) return false;
        return bridge.onSwing(player);
    }

    public static boolean onRightClickBlock(ServerPlayerEntity player, Hand hand) {
        FabricGameplayBridge bridge = active;
        if (bridge == null) return false;
        // Paper sets RIGHT_CLICK_INTERACT before its EquipmentSlot.HAND check,
        // so even an off-hand block interaction suppresses the following arm
        // swing without becoming a bending action of its own.
        bridge.rightClickBlockUntilTick.put(player.getUuid(), bridge.inputTick + 2);
        if (hand != Hand.MAIN_HAND) return false;
        return bridge.onRightClick(player, ClickType.RIGHT_CLICK_BLOCK);
    }

    public static boolean onRightClickItem(ServerPlayerEntity player, Hand hand) {
        FabricGameplayBridge bridge = active;
        if (bridge == null || hand != Hand.MAIN_HAND) return false;
        if (bridge.rightClickBlockUntilTick.getOrDefault(player.getUuid(), -1L) >= bridge.inputTick) return false;
        return bridge.onRightClick(player, ClickType.RIGHT_CLICK);
    }

    public static boolean onRightClickEntity(ServerPlayerEntity player, Hand hand) {
        FabricGameplayBridge bridge = active;
        if (bridge == null) return false;
        if (hand != Hand.MAIN_HAND) {
            CommonInputHandler.prepareRightClickEntity(FabricMC.player(player));
            return false;
        }
        return bridge.onRightClickEntity(player);
    }

    public static boolean onPlayerAction(ServerPlayerEntity player, PlayerActionC2SPacket.Action action) {
        FabricGameplayBridge bridge = active;
        if (bridge == null) return false;
        return switch (action) {
            case DROP_ITEM, DROP_ALL_ITEMS -> {
                onDropItem(player);
                yield false;
            }
            case SWAP_ITEM_WITH_OFFHAND -> {
                onSwapHands(player);
                yield false;
            }
            // Legacy Bukkit bends from PlayerAnimationEvent (arm swing), not
            // the earlier block-destroy packet.
            case START_DESTROY_BLOCK -> false;
            default -> false;
        };
    }

    public static void onClientSneakCommand(ServerPlayerEntity player, boolean sneakingNow) {
        onPlayerInput(player, sneakingNow);
    }

    public static void onPlayerInput(ServerPlayerEntity player, boolean sneakingNow) {
        FabricGameplayBridge bridge = active;
        if (bridge == null) return;
        final Boolean previous = bridge.sneaking.get(player.getUuid());
        if (previous != null && previous == sneakingNow) return;
        bridge.onSneakPacket(player, sneakingNow);
    }

    public static void onDropItem(ServerPlayerEntity nativePlayer) {
        FabricGameplayBridge bridge = active;
        if (bridge == null) return;
        if (CommonInputHandler.shouldTrackDrop(FabricMC.player(nativePlayer))) {
            bridge.droppedItem.add(nativePlayer.getUuid());
        }
    }

    public static void onSwapHands(ServerPlayerEntity nativePlayer) {
        FabricGameplayBridge bridge = active;
        if (bridge == null) return;
        Player player = FabricMC.player(nativePlayer);
        CommonInputHandler.handleSwapHands(player, nativePlayer.getMainHandStack().isEmpty(), nativePlayer.getOffHandStack().isEmpty());
    }

    public static boolean onSelectedSlot(ServerPlayerEntity nativePlayer, int selectedSlot) {
        FabricGameplayBridge bridge = active;
        if (bridge == null) return true;
        if (selectedSlot < 0 || selectedSlot > 8) return false;
        BendingPlayer bendingPlayer = BendingPlayer.getBendingPlayer(FabricMC.player(nativePlayer));
        if (nativePlayer.getInventory().getSelectedSlot() == selectedSlot
                && (bendingPlayer == null || bendingPlayer.getCurrentSlot() == selectedSlot)) {
            return true;
        }
        Player player = FabricMC.player(nativePlayer);
        CommonInputHandler.SlotResult result = CommonInputHandler.handleSlotChange(player, selectedSlot);
        if (!result.accepted()) {
            nativePlayer.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(
                    nativePlayer.getInventory().getSelectedSlot()));
            return false;
        }
        return true;
    }

    private void join(ServerPlayerEntity nativePlayer) {
        Player player = FabricMC.player(nativePlayer);
        sneaking.put(nativePlayer.getUuid(), nativePlayer.isSneaking());
        CommonPlayerListenerCore.handleJoin(player);
    }

    private void leave(ServerPlayerEntity nativePlayer) {
        Player player = FabricMC.player(nativePlayer);
        CommonPlayerListenerCore.handleQuit(player);
        sneaking.remove(nativePlayer.getUuid());
        rightClickBlockUntilTick.remove(nativePlayer.getUuid());
        droppedItem.remove(nativePlayer.getUuid());
        FabricMC.clearPlayerState(nativePlayer);
    }

    private boolean onRightClick(ServerPlayerEntity nativePlayer, ClickType type) {
        Player player = FabricMC.player(nativePlayer);
        return CommonInputHandler.handleRightClick(player, type).cancelEvent();
    }

    private boolean onRightClickEntity(ServerPlayerEntity nativePlayer) {
        Player player = FabricMC.player(nativePlayer);
        return CommonInputHandler.handleRightClickEntity(player).cancelEvent();
    }

    protected void onSneakPacket(ServerPlayerEntity nativePlayer, boolean sneakingNow) {
        Boolean previous = sneaking.put(nativePlayer.getUuid(), sneakingNow);
        if (previous == null || previous != sneakingNow) {
            boolean wasSneaking = previous != null ? previous : !sneakingNow;
            dispatchSneak(nativePlayer, wasSneaking);
        }
    }

    private void dispatchSneak(ServerPlayerEntity nativePlayer, boolean wasSneaking) {
        FabricMC.withSneakingOverride(nativePlayer, wasSneaking, () -> onSneak(nativePlayer, wasSneaking));
    }

    protected void onSneak(ServerPlayerEntity nativePlayer, boolean wasSneaking) {
        Player player = FabricMC.player(nativePlayer);
        CommonInputHandler.handleSneak(player, wasSneaking);
    }

    protected boolean onSwing(ServerPlayerEntity nativePlayer) {
        Player player = FabricMC.player(nativePlayer);
        if (rightClickBlockUntilTick.getOrDefault(nativePlayer.getUuid(), -1L) >= inputTick) return false;
        return CommonInputHandler.handleSwing(player, rightClickBlockUntilTick.keySet(), droppedItem).cancelEvent();
    }

}
