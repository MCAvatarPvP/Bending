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

public abstract class PredictionClientLifecycle extends PredictionClientInput {
    protected void reset(MinecraftClient client) {
        debug("reset active=" + active + " session=" + sessionId);
        ExactPredictionRuntime.stop(client);
        active = false; sessionId = null; nextSequence = 0;
        readySent = false;
        disableHandshakePending = false;
        lastHelloTick = clientTick - 1_000;
        lastRuntimeStartAttemptTick = Long.MIN_VALUE / 2;
        consecutiveRuntimeStartFailures = 0;
        config.clear(); binds.clear(); cooldowns.clear();
        actionStartedAtMillis.clear();
        clearChunks();
        elements = List.of(); subElements = List.of(); permissions = List.of();
        serverPose = null;
        pendingSneakPose = null;
        previousClientEyeHeight = client.player == null
                ? Double.NaN : client.player.getEyeY() - client.player.getY();
        serverTimeOffsetMillis = 0;
        estimatedOneWayLatencyMillis = 0;
        lastAuthorityTick = -1;
        maxRewindTicks = 0;
        airBlastDecay = 0.0;
        chiBlocked = false;
        cosmetics = PredictionPayloads.PlayerCosmetics.empty();
        regionProtection = RegionProtectionAuthority.Snapshot.empty();
        pendingHitClaims.clear();
        currentNativeInputPacket = null;
        pendingTaggedPacket = null;
        pendingActionTag = null;
        rightClickBlockUntilTick = -1;
        droppedItem = false;
        previousSneaking = client.player != null && client.player.isSneaking();
        previousSpectator = client.player != null && client.player.isSpectator();
        firstSoftRespawnEffectRepairTick = -1;
        finalSoftRespawnEffectRepairTick = -1;
        worldTempBlockResyncPending = false;
        worldTempBlockRequestSent = false;
        clientWorldBoundaryAwaitingIdentity = false;
        serverWorldIdentity = null;
        serverWorldGeneration = -1L;
        serverSneaking = previousSneaking;
        serverSelectedSlot = client.player == null ? -1 : client.player.getInventory().getSelectedSlot();
        runtimeWorld = null;
        runtimePlayer = null;
    }

    protected void restartForWorldChange(MinecraftClient client) {
        ClientWorld previousWorld = runtimeWorld;
        boolean playerReplaced = runtimePlayer != null && runtimePlayer != client.player;
        debug("client world changed old=" + worldKey(previousWorld) + " new=" + worldKey(client.world)
                + " playerReplaced=" + playerReplaced + "; restarting local prediction runtime");
        ExactPredictionRuntime.stop(client);
        active = false;

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || player.getEntityWorld() != client.world) {
            runtimeWorld = null;
            runtimePlayer = null;
            debug("client world runtime restart deferred until destination player is installed"
                    + " clientWorld=" + worldKey(client.world)
                    + " playerWorld=" + (player == null ? "none"
                    : player.getEntityWorld().getRegistryKey().getValue()));
            recordWorldTransition("runtime start deferred for destination player");
            return;
        }
        if (playerReplaced) rebuildStatusEffectAttributes(player);
        serverPose = new ServerPose(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(),
                player.getEyeY() - player.getY());
        previousSneaking = player.isSneaking();
        previousSpectator = player.isSpectator();
        serverSneaking = previousSneaking;
        pendingSneakPose = null;
        previousClientEyeHeight = player.getEyeY() - player.getY();
        serverSelectedSlot = player.getInventory().getSelectedSlot();
        rightClickBlockUntilTick = -1;
        droppedItem = false;

        startRuntime(client, "world-change");
        debug("client world runtime restarted active=" + active + " session=" + sessionId);
        recordWorldTransition("world runtime restart completed active=" + active);
    }

    protected boolean startRuntime(final MinecraftClient client, final String reason) {
        lastRuntimeStartAttemptTick = clientTick;
        if (!ClientBendingConfig.isEnabled() || client == null || client.world == null || client.player == null
                || client.player.getEntityWorld() != client.world) {
            active = false;
            runtimeWorld = null;
            runtimePlayer = null;
            worldTempBlockResyncPending = sessionId != null;
            worldTempBlockRequestSent = false;
            recordWorldTransition("runtime start rejected reason=" + reason
                    + " because destination player is not installed");
            return false;
        }
        active = ExactPredictionRuntime.start(client, List.copyOf(config.values()), binds, cooldowns,
                elements, subElements, permissions, airBlastDecay, chiBlocked, cosmetics, regionProtection);
        if (active) consecutiveRuntimeStartFailures = 0;
        else consecutiveRuntimeStartFailures++;
        rememberRuntimeIdentity(client);
        debug("runtime start reason=" + reason + " active=" + active
                + " failure=" + ExactPredictionRuntime.lastStartFailure());
        return active;
    }

    /**
     * Respawn copies the active-effect map onto a new client player object,
     * but an external spectator/survival transition can leave that object's
     * transient attribute modifiers out of sync with the copied map. Reapply
     * exact copies so vanilla rebuilds modifiers such as Speed while keeping
     * every visible effect property unchanged.
     */
    protected static void rebuildStatusEffectAttributes(ClientPlayerEntity player) {
        if (player == null || player.getActiveStatusEffects().isEmpty()) return;
        List<StatusEffectInstance> effects = player.getActiveStatusEffects().values().stream()
                .map(StatusEffectInstance::new)
                .toList();
        for (StatusEffectInstance effect : effects) {
            player.removeStatusEffect(effect.getEffectType());
        }
        for (StatusEffectInstance effect : effects) {
            player.addStatusEffect(effect);
        }
        debug("rebuilt respawn status-effect attributes count=" + effects.size());
    }

    protected void rememberRuntimeIdentity(MinecraftClient client) {
        if (!active || client == null) {
            runtimeWorld = null;
            runtimePlayer = null;
            return;
        }
        runtimeWorld = client.world;
        runtimePlayer = client.player;
    }
}
