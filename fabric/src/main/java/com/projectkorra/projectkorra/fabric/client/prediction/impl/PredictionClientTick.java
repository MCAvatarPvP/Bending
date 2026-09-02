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

public abstract class PredictionClientTick extends PredictionClientWorldState {
    protected void tick(MinecraftClient client) {
        clientTick++;
        if (!ClientBendingConfig.isEnabled()) {
            if (active || sessionId != null || ExactPredictionRuntime.isReady()) reset(client);
            sendPredictionDisabled(client);
            return;
        }
        // Paper advertises Bukkit plugin channels during play setup. Retry the
        // hello until that registration has reached the Fabric client.
        if (!active && sessionId == null && client.getNetworkHandler() != null
                && clientTick - lastHelloTick >= 20
                && ClientPlayNetworking.canSend(PredictionPayloads.ClientHello.ID)) {
            ClientPlayNetworking.send(new PredictionPayloads.ClientHello(PredictionPayloads.PROTOCOL_VERSION, clientTick, CAPABILITIES));
            lastHelloTick = clientTick;
            debug("sent hello retry tick=" + clientTick);
        }

        if (client.player == null || client.world == null) return;
        final int runtimeRetryDelay = consecutiveRuntimeStartFailures <= 1
                ? 1 : RUNTIME_RETRY_TICKS;
        if (!active && sessionId != null && !config.isEmpty()
                && clientTick - lastRuntimeStartAttemptTick >= runtimeRetryDelay) {
            startRuntime(client, "cached-state-recovery");
            if (active) {
                sendReady();
                requestWorldTempBlockSnapshot();
            }
        }
        if (active) {
            if (runtimeWorld != client.world || runtimePlayer != client.player) {
                onClientWorldChange(client, client.world);
                if (!active) return;
            }
            sendReady();
            requestWorldTempBlockSnapshot();
            boolean spectator = client.player.isSpectator();
            if (previousSpectator && !spectator) {
                // Neptune performs a soft respawn on the same player entity:
                // survival is sent, potion effects are cleared, then PK
                // restores AirAgility on a later tick. Rebuild once after the
                // immediate packet burst and once after passive registration.
                firstSoftRespawnEffectRepairTick = clientTick + 2;
                finalSoftRespawnEffectRepairTick = clientTick + 10;
                debug("scheduled soft-respawn status-effect attribute repair");
            }
            previousSpectator = spectator;
            if (clientTick == firstSoftRespawnEffectRepairTick) {
                PredictionClient.rebuildStatusEffectAttributes(client.player);
                firstSoftRespawnEffectRepairTick = -1;
            }
            if (clientTick == finalSoftRespawnEffectRepairTick) {
                PredictionClient.rebuildStatusEffectAttributes(client.player);
                finalSoftRespawnEffectRepairTick = -1;
            }
            if (rightClickBlockUntilTick < clientTick - 4) rightClickBlockUntilTick = -1;
            cooldowns.entrySet().removeIf(entry -> entry.getValue() <= System.currentTimeMillis());
            ExactPredictionRuntime.tick(client);
        }
        // Paper's Bukkit scheduler has now completed the first progress pass
        // after the input packet. Its following player tick is where the native
        // pose/eye height catches up to input flags. Copy the actual vanilla
        // pose rather than assuming only standing/crouching: swimming, gliding,
        // and collision-constrained crouching follow this same boundary.
        commitServerVisibleEntityPose(client);
    }

    /** Replaces a ready Paper session with an explicitly non-predicting one. */
    protected void sendPredictionDisabled(final MinecraftClient client) {
        if (!disableHandshakePending || client == null || client.getNetworkHandler() == null) return;
        if (ClientPlayNetworking.canSend(PredictionPayloads.ClientDisabled.ID)) {
            ClientPlayNetworking.send(new PredictionPayloads.ClientDisabled(
                    PredictionPayloads.PROTOCOL_VERSION));
            disableHandshakePending = false;
            debug("sent prediction-disabled packet tick=" + clientTick);
            return;
        }
        // Older matching endpoints do not expose the disable channel. A
        // zero-capability hello at least prevents them from hiding predicted
        // effects, although only the explicit packet removes all S2C traffic.
        if (!ClientPlayNetworking.canSend(PredictionPayloads.ClientHello.ID)) return;
        ClientPlayNetworking.send(new PredictionPayloads.ClientHello(
                PredictionPayloads.PROTOCOL_VERSION, clientTick, 0));
        disableHandshakePending = false;
        debug("sent legacy prediction-disabled hello tick=" + clientTick);
    }
}
