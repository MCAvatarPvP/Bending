package com.projectkorra.projectkorra.fabric;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.fabric.prediction.PredictionServer;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.listener.CommonPlayerListenerCore;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ClickType;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import com.projectkorra.projectkorra.fabric.prediction.PredictionPayloads;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Converts Fabric gameplay input into the loader-neutral activation API. */
public class FabricGameplayBridge {
    private static FabricGameplayBridge active;
    private final Map<UUID, Boolean> sneaking = new HashMap<>();
    private final Map<UUID, Long> rightClickBlockUntilTick = new HashMap<>();
    private final Map<UUID, Long> leftClickUntilTick = new HashMap<>();
    private final Set<UUID> droppedItem = new HashSet<>();
    private final Map<UUID, Map<PredictionPayloads.InputKind, PredictedVanillaEcho>> predictedVanillaUntil = new HashMap<>();
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("projectkorra.prediction.debug", "false"));
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
        leftClickUntilTick.entrySet().removeIf(entry -> entry.getValue() < inputTick);
        predictedVanillaUntil.values().forEach(pending -> pending.entrySet().removeIf(entry -> entry.getValue().expiresAt < inputTick || entry.getValue().remaining <= 0));
        predictedVanillaUntil.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    void stop() {
        CoreAbility.removeAll();
        sneaking.clear();
        rightClickBlockUntilTick.clear();
        leftClickUntilTick.clear();
        droppedItem.clear();
        predictedVanillaUntil.clear();
        active = null;
    }

    private void registerCallbacks() {
        ServerPlayerEvents.JOIN.register(this::join);
        ServerPlayerEvents.LEAVE.register(this::leave);
    }

    public static boolean onMainHandSwing(ServerPlayerEntity player) {
        FabricGameplayBridge bridge = active;
        if (bridge == null) return false;
        if (bridge.consumePredictedVanilla(player.getUuid(), PredictionPayloads.InputKind.LEFT_CLICK)) return true;
        if (PredictionServer.shouldSuppressVanillaInput(player, PredictionPayloads.InputKind.LEFT_CLICK)) return true;
        return bridge.onSwing(player);
    }

    public static boolean onBlockAttack(ServerPlayerEntity player) {
        FabricGameplayBridge bridge = active;
        if (bridge == null) return false;
        if (bridge.consumePredictedVanilla(player.getUuid(), PredictionPayloads.InputKind.LEFT_CLICK)) return true;
        if (PredictionServer.shouldSuppressVanillaInput(player, PredictionPayloads.InputKind.LEFT_CLICK)) return true;
        return bridge.onSwing(player);
    }

    public static boolean onRightClickBlock(ServerPlayerEntity player, Hand hand) {
        FabricGameplayBridge bridge = active;
        if (bridge == null || hand != Hand.MAIN_HAND) return false;
        bridge.rightClickBlockUntilTick.put(player.getUuid(), bridge.inputTick + 2);
        if (bridge.consumePredictedVanilla(player.getUuid(), PredictionPayloads.InputKind.RIGHT_CLICK_BLOCK)) return true;
        if (PredictionServer.shouldSuppressVanillaInput(player, PredictionPayloads.InputKind.RIGHT_CLICK_BLOCK)) return true;
        return bridge.onRightClick(player, ClickType.RIGHT_CLICK_BLOCK);
    }

    public static boolean onRightClickItem(ServerPlayerEntity player, Hand hand) {
        FabricGameplayBridge bridge = active;
        if (bridge == null || hand != Hand.MAIN_HAND) return false;
        if (bridge.rightClickBlockUntilTick.getOrDefault(player.getUuid(), -1L) >= bridge.inputTick) return false;
        if (bridge.consumeAnyPredictedRightClick(player.getUuid())) return true;
        if (PredictionServer.shouldSuppressVanillaInput(player, PredictionPayloads.InputKind.RIGHT_CLICK)) return true;
        return bridge.onRightClick(player, ClickType.RIGHT_CLICK);
    }

    public static boolean onRightClickEntity(ServerPlayerEntity player, Hand hand) {
        FabricGameplayBridge bridge = active;
        if (bridge == null || hand != Hand.MAIN_HAND) return false;
        if (bridge.consumePredictedVanilla(player.getUuid(), PredictionPayloads.InputKind.RIGHT_CLICK_ENTITY)) return true;
        if (PredictionServer.shouldSuppressVanillaInput(player, PredictionPayloads.InputKind.RIGHT_CLICK_ENTITY)) return true;
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
            case START_DESTROY_BLOCK -> onBlockAttack(player);
            default -> false;
        };
    }

    public static void onClientSneakCommand(ServerPlayerEntity player, boolean sneakingNow) {
        onPlayerInput(player, sneakingNow);
    }

    public static void onPlayerInput(ServerPlayerEntity player, boolean sneakingNow) {
        FabricGameplayBridge bridge = active;
        PredictionPayloads.InputKind kind = sneakingNow ? PredictionPayloads.InputKind.SNEAK_START : PredictionPayloads.InputKind.SNEAK_STOP;
        if (bridge != null && !bridge.consumePredictedVanilla(player.getUuid(), kind)
                && !PredictionServer.shouldSuppressVanillaInput(player, kind)) {
            bridge.onSneakPacket(player, sneakingNow);
        }
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

    public boolean applyPredictedSlot(ServerPlayerEntity nativePlayer, int selectedSlot) {
        if (selectedSlot < 0 || selectedSlot > 8) return false;
        final boolean[] accepted = {false};
        FabricMC.withSelectedSlotPacketSuppressed(nativePlayer, () -> accepted[0] = onSelectedSlot(nativePlayer, selectedSlot));
        if (!accepted[0]) return false;
        nativePlayer.getInventory().setSelectedSlot(selectedSlot);
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
        leftClickUntilTick.remove(nativePlayer.getUuid());
        droppedItem.remove(nativePlayer.getUuid());
        predictedVanillaUntil.remove(nativePlayer.getUuid());
        FabricMC.clearPlayerState(nativePlayer);
    }

    /** Dispatches an early custom input and marks the following vanilla packet as a duplicate. */
    public boolean handlePredictedInput(ServerPlayerEntity nativePlayer, PredictionPayloads.InputKind kind) {
        Player player = FabricMC.player(nativePlayer);
        boolean dispatched = switch (kind) {
            case LEFT_CLICK -> {
                if (!beginLeftClick(nativePlayer.getUuid())) yield false;
                CommonInputHandler.handleSwing(player, rightClickBlockUntilTick.keySet(), droppedItem);
                yield true;
            }
            case RIGHT_CLICK -> {
                CommonInputHandler.handleRightClick(player, ClickType.RIGHT_CLICK);
                yield true;
            }
            case RIGHT_CLICK_BLOCK -> {
                CommonInputHandler.handleRightClick(player, ClickType.RIGHT_CLICK_BLOCK);
                yield true;
            }
            case RIGHT_CLICK_ENTITY -> {
                CommonInputHandler.handleRightClickEntity(player);
                yield true;
            }
            case SNEAK_START -> {
                onSneakPacket(nativePlayer, true);
                yield true;
            }
            case SNEAK_STOP -> {
                onSneakPacket(nativePlayer, false);
                yield true;
            }
        };
        if (dispatched) {
            predictedVanillaUntil.computeIfAbsent(nativePlayer.getUuid(), ignored -> new EnumMap<>(PredictionPayloads.InputKind.class))
                    .put(kind, new PredictedVanillaEcho(inputTick + 4, vanillaEchoBudget(kind)));
            debug("predicted-dispatch player=" + nativePlayer.getName().getString() + " kind=" + kind + " tick=" + inputTick);
        }
        return dispatched;
    }

    public void suppressPredictedVanillaInput(ServerPlayerEntity nativePlayer, PredictionPayloads.InputKind kind) {
        predictedVanillaUntil.computeIfAbsent(nativePlayer.getUuid(), ignored -> new EnumMap<>(PredictionPayloads.InputKind.class))
                .put(kind, new PredictedVanillaEcho(inputTick + 4, vanillaEchoBudget(kind)));
        debug("predicted-vanilla-suppressed-without-dispatch player=" + nativePlayer.getName().getString()
                + " kind=" + kind + " tick=" + inputTick);
    }

    private boolean consumePredictedVanilla(UUID uuid, PredictionPayloads.InputKind kind) {
        Map<PredictionPayloads.InputKind, PredictedVanillaEcho> pending = predictedVanillaUntil.get(uuid);
        if (pending == null) return false;
        PredictedVanillaEcho echo = pending.get(kind);
        if (echo == null || echo.expiresAt < inputTick || echo.remaining <= 0) return false;
        echo.remaining--;
        if (echo.remaining <= 0) pending.remove(kind);
        if (pending.isEmpty()) predictedVanillaUntil.remove(uuid);
        debug("predicted-vanilla-consumed uuid=" + uuid + " kind=" + kind + " tick=" + inputTick);
        return true;
    }

    private boolean consumeAnyPredictedRightClick(UUID uuid) {
        return consumePredictedVanilla(uuid, PredictionPayloads.InputKind.RIGHT_CLICK)
                || consumePredictedVanilla(uuid, PredictionPayloads.InputKind.RIGHT_CLICK_BLOCK)
                || consumePredictedVanilla(uuid, PredictionPayloads.InputKind.RIGHT_CLICK_ENTITY);
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
        if (droppedItem.remove(nativePlayer.getUuid())) return;
        CommonInputHandler.handleSneak(player, wasSneaking);
    }

    protected boolean onSwing(ServerPlayerEntity nativePlayer) {
        Player player = FabricMC.player(nativePlayer);
        if (rightClickBlockUntilTick.getOrDefault(nativePlayer.getUuid(), -1L) >= inputTick) return false;
        if (!beginLeftClick(nativePlayer.getUuid())) return false;
        return CommonInputHandler.handleSwing(player, rightClickBlockUntilTick.keySet(), droppedItem).cancelEvent();
    }

    private boolean beginLeftClick(UUID uuid) {
        if (leftClickUntilTick.getOrDefault(uuid, -1L) >= inputTick) {
            debug("left-click-deduped uuid=" + uuid + " tick=" + inputTick);
            return false;
        }
        leftClickUntilTick.put(uuid, inputTick + 1);
        return true;
    }

    private static void debug(String message) {
        if (DEBUG) System.out.println("[ProjectKorraPrediction] [FabricGameplay] " + message);
    }

    private static int vanillaEchoBudget(PredictionPayloads.InputKind kind) {
        return switch (kind) {
            case LEFT_CLICK -> 2;
            case RIGHT_CLICK_BLOCK -> 2;
            default -> 1;
        };
    }

    private static final class PredictedVanillaEcho {
        final long expiresAt;
        int remaining;
        private PredictedVanillaEcho(long expiresAt, int remaining) {
            this.expiresAt = expiresAt;
            this.remaining = remaining;
        }
    }

}
