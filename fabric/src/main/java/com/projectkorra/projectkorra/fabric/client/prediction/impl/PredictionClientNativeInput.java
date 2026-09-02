package com.projectkorra.projectkorra.fabric.client.prediction.impl;

import com.projectkorra.projectkorra.fabric.client.config.ClientBendingConfig;
import com.projectkorra.projectkorra.fabric.client.prediction.block.ClientTempBlockAuthority;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads;
import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority;
import java.util.ArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Network/session owner for exact ProjectKorra client prediction. */
import com.projectkorra.projectkorra.fabric.client.PredictionClient;
import com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime;

public abstract class PredictionClientNativeInput extends PredictionClientState {
    protected void prepareAcceptedNativeInputPacket0(final Packet<?> packet) {
        if (packet == null || packet != currentNativeInputPacket) return;
        if (packet == pendingTaggedPacket) {
            final PendingActionTag tag = pendingActionTag;
            pendingTaggedPacket = null;
            pendingActionTag = null;
            if (tag != null && active && sessionId != null
                    && ClientPlayNetworking.canSend(PredictionPayloads.ActionTag.ID)) {
                ClientPlayNetworking.send(new PredictionPayloads.ActionTag(sessionId,
                        tag.clientActionSequence(), tag.kind(), tag.selectedSlot(), tag.ability()));
            }
        }
    }

    protected void acceptedNativeInputPacket0(final Packet<?> packet) {
        if (packet == null || packet != currentNativeInputPacket) return;
        currentNativeInputPacket = null;
        // The pre-send hook normally consumed this pair. Clear it defensively
        // when a networking implementation reaches the after-send callback
        // without supporting the custom payload.
        if (packet == pendingTaggedPacket) {
            pendingTaggedPacket = null;
            pendingActionTag = null;
        }
        flushPendingHitClaims();
    }

    protected void flushPendingHitClaims() {
        if (pendingHitClaims.isEmpty()) return;
        final int retention = Math.max(1, maxRewindTicks + 2);
        final List<PendingHitClaim> claims = List.copyOf(pendingHitClaims);
        pendingHitClaims.clear();
        for (PendingHitClaim claim : claims) {
            if (clientTick - claim.clientTick() <= retention) sendHitClaim(claim);
        }
    }

    protected void sendHitClaim(final PendingHitClaim claim) {
        if (claim == null || !active || sessionId == null
                || !ClientPlayNetworking.canSend(PredictionPayloads.HitClaim.ID)) return;
        ClientPlayNetworking.send(new PredictionPayloads.HitClaim(sessionId,
                claim.clientActionSequence(), claim.serverActionSequence(), claim.clientTick(),
                claim.targetUuid(), claim.targetEntityId(), claim.ability(),
                claim.contactX(), claim.contactY(), claim.contactZ()));
    }

    protected static boolean isNativeAbilityInputPacket(final Packet<?> packet) {
        return packet instanceof HandSwingC2SPacket
                || packet instanceof PlayerActionC2SPacket
                || packet instanceof ClientCommandC2SPacket
                || packet instanceof PlayerInputC2SPacket
                || packet instanceof PlayerInteractBlockC2SPacket
                || packet instanceof PlayerInteractItemC2SPacket
                || packet instanceof PlayerInteractEntityC2SPacket;
    }

    protected void captureSneakState(MinecraftClient client, boolean sneaking) {
        if (serverPose == null && client != null && client.player != null) {
            // The first input packet can be a sneak edge before any movement
            // packet has initialized the server-visible pose. By the time the
            // packet-send hook runs, the vanilla client may already expose the
            // new crouching eye height. Paper's toggle event still sees the old
            // pose, so seed the missing snapshot from the old tracked state.
            final ClientPlayerEntity player = client.player;
            serverSneaking = previousSneaking;
            final double oldEyeHeight = Double.isFinite(previousClientEyeHeight)
                    ? previousClientEyeHeight
                    : player.getEyeHeight(previousSneaking ? EntityPose.CROUCHING : EntityPose.STANDING);
            serverPose = new ServerPose(player.getX(), player.getY(), player.getZ(),
                    player.getYaw(), player.getPitch(), oldEyeHeight);
        }
        if (sneaking != previousSneaking) {
            // Paper fires PlayerToggleSneakEvent before it calls
            // ServerPlayer#setShiftKeyDown. Paper then drains Bukkit's scheduler
            // before the world/player tick calls updatePose(). Consequently the
            // event sees the old flag and eye pose, while this tick's ability
            // progress sees the new flag with that same old eye pose.
            capture(client, sneaking ? PredictionPayloads.InputKind.SNEAK_START
                    : PredictionPayloads.InputKind.SNEAK_STOP);
            queueServerVisibleSneakPose(sneaking);
            previousSneaking = sneaking;
        } else {
            // A second representation of the same vanilla edge (or a repeated
            // PlayerInput packet) must not advance the eye pose before the one
            // matching ProjectKorra progress pass has run.
            serverSneaking = sneaking;
        }
    }

    protected void queueServerVisibleSneakPose(boolean sneaking) {
        serverSneaking = sneaking;
        pendingSneakPose = sneaking;
        if (DEBUG && active) {
            debug("server-pose sneak queued sneaking=" + sneaking
                    + " eyeY=" + (serverPose == null ? "unknown" : serverPose.eyePos().y));
        }
    }

    protected void commitServerVisibleEntityPose(MinecraftClient client) {
        final Boolean sneakEdge = pendingSneakPose;
        pendingSneakPose = null;
        if (client == null || client.player == null) return;
        ClientPlayerEntity player = client.player;
        final double eyeHeight = player.getEyeY() - player.getY();
        previousClientEyeHeight = eyeHeight;
        if (serverPose == null) return;
        final double oldEyeHeight = serverPose.eyeHeight();
        serverPose = new ServerPose(serverPose.x(), serverPose.y(), serverPose.z(), serverPose.yaw(),
                serverPose.pitch(), eyeHeight);
        if (DEBUG && active && (sneakEdge != null || Math.abs(oldEyeHeight - eyeHeight) > 1.0E-6)) {
            debug("server-pose entity committed sneaking=" + serverSneaking
                    + " edge=" + sneakEdge
                    + " yaw=" + serverPose.yaw() + " pitch=" + serverPose.pitch()
                    + " eyeY=" + serverPose.eyePos().y);
        }
    }

    public static ServerPose serverVisiblePose(MinecraftClient client) {
        ServerPose pose = PredictionClient.instance().serverPose;
        if (pose != null || client == null || client.player == null) return pose;
        ClientPlayerEntity player = client.player;
        return new ServerPose(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(),
                player.getEyeY() - player.getY());
    }

    public static boolean serverVisibleSneaking(MinecraftClient client) {
        final Boolean inputOverride = INPUT_SNEAK_OVERRIDE.get();
        if (inputOverride != null) return inputOverride;
        if (client == null || client.player == null) return PredictionClient.instance().serverSneaking;
        return PredictionClient.instance().serverPose == null ? client.player.isSneaking() : PredictionClient.instance().serverSneaking;
    }

    public static void acceptAuthoritativeSelectedSlot(int slot) {
        if (slot < 0 || slot > 8) return;
        PredictionClient.instance().serverSelectedSlot = slot;
        debug("accepted authoritative selected-slot correction slot=" + (slot + 1));
    }

    public static void withInputSneaking(final boolean sneaking, final Runnable action) {
        final Boolean previous = INPUT_SNEAK_OVERRIDE.get();
        INPUT_SNEAK_OVERRIDE.set(sneaking);
        try {
            action.run();
        } finally {
            if (previous == null) INPUT_SNEAK_OVERRIDE.remove();
            else INPUT_SNEAK_OVERRIDE.set(previous);
        }
    }

    public static void withInputSelectedSlot(final int selectedSlot, final Runnable action) {
        final Integer previous = INPUT_SLOT_OVERRIDE.get();
        INPUT_SLOT_OVERRIDE.set(selectedSlot);
        try {
            action.run();
        } finally {
            if (previous == null) INPUT_SLOT_OVERRIDE.remove();
            else INPUT_SLOT_OVERRIDE.set(previous);
        }
    }

    public static int serverVisibleSelectedSlot(MinecraftClient client) {
        final Integer inputSlot = INPUT_SLOT_OVERRIDE.get();
        if (inputSlot != null && inputSlot >= 0 && inputSlot < 9) return inputSlot;
        int slot = PredictionClient.instance().serverSelectedSlot;
        if (slot >= 0 && slot < 9) return slot;
        return client != null && client.player != null ? client.player.getInventory().getSelectedSlot() : 0;
    }

    public static Map<String, PredictionPayloads.ConfigEntry> publicConfig() { return Map.copyOf(PredictionClient.instance().config); }

    /** Applies a Mod Menu setting change without requiring a reconnect. */
    public static void onClientSideBendingSettingChanged(final boolean enabled) {
        if (!initialized) return;
        final MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled) {
            PredictionClient.instance().reset(client);
            PredictionClient.instance().disableHandshakePending = client.getNetworkHandler() != null;
            PredictionClient.instance().sendPredictionDisabled(client);
            return;
        }
        PredictionClient.instance().disableHandshakePending = false;
        // Retry the normal authenticated handshake on the next client tick.
        PredictionClient.instance().lastHelloTick = PredictionClient.instance().clientTick - 20L;
    }

    public static String diagnosticStatus() {
        final MinecraftClient client = MinecraftClient.getInstance();
        final String failure = ExactPredictionRuntime.lastStartFailure();
        return "session=" + (PredictionClient.instance().sessionId != null)
                + " active=" + PredictionClient.instance().active
                + " runtime=" + ExactPredictionRuntime.isReady()
                + " ready=" + PredictionClient.instance().readySent
                + " abilities=" + ExactPredictionRuntime.supportedAbilities().size()
                + " binds=" + PredictionClient.instance().binds.size()
                + " retries=" + PredictionClient.instance().consecutiveRuntimeStartFailures
                + " world=" + worldRef(client == null ? null : client.world)
                + " player=" + playerRef(client == null ? null : client.player)
                + " runtimeWorld=" + worldRef(PredictionClient.instance().runtimeWorld)
                + " runtimePlayer=" + playerRef(PredictionClient.instance().runtimePlayer)
                + " serverWorld=" + (PredictionClient.instance().serverWorldIdentity == null ? "unknown" : PredictionClient.instance().serverWorldIdentity)
                + " generation=" + PredictionClient.instance().serverWorldGeneration
                + " ledgerPending=" + PredictionClient.instance().worldTempBlockResyncPending
                + " ledgerRequestSent=" + PredictionClient.instance().worldTempBlockRequestSent
                + " boundaryAwaiting=" + PredictionClient.instance().clientWorldBoundaryAwaitingIdentity
                + (failure == null || failure.isBlank() ? "" : " failure=" + failure);
    }

    public static List<String> worldTransitionReport() {
        final List<String> report = new ArrayList<>();
        report.add("World transition state: " + diagnosticStatus());
        if (PredictionClient.instance().worldTransitionHistory.isEmpty()) {
            report.add("World transition history: no boundary recorded this session");
        } else {
            report.add("World transitions (oldest to newest):");
            report.addAll(PredictionClient.instance().worldTransitionHistory);
        }
        return List.copyOf(report);
    }
}
