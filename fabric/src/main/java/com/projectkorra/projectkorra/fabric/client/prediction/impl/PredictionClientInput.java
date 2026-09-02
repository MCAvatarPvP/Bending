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

public abstract class PredictionClientInput extends PredictionClientTick {
    protected void captureLeftClick(MinecraftClient client) {
        // Fabric/Paper expires a marker whose deadline equals the current
        // tick before processing that tick's input. Treat the deadline as an
        // exclusive bound here as well; keeping it inclusive drops a valid
        // swing locally while Paper accepts and runs it (notably AirBlast).
        final boolean suppress = droppedItem || rightClickBlockUntilTick > clientTick;
        droppedItem = false;
        capture(client, PredictionPayloads.InputKind.LEFT_CLICK, suppress);
    }

    protected void capture(MinecraftClient client, PredictionPayloads.InputKind kind) {
        capture(client, kind, false);
    }

    protected void capture(MinecraftClient client, PredictionPayloads.InputKind kind, boolean suppressInput) {
        if (!active || !readySent || sessionId == null || client.player == null || client.world == null) {
            debug("capture skipped kind=" + kind + " active=" + active + " session=" + sessionId
                    + " player=" + (client.player != null) + " world=" + (client.world != null)
                    + " ready=" + readySent);
            return;
        }
        // This ordinal counts native vanilla/Paper events, including inputs
        // with no predictable bound ability. The server advances the same
        // counter from its native callback. The vanilla packet remains the
        // only event which schedules gameplay.
        long sequence = ++nextSequence;
        int selectedSlot = serverVisibleSelectedSlot(client);
        int localSlot = client.player.getInventory().getSelectedSlot();
        String ability = ExactPredictionRuntime.inputAbilityName(selectedSlot, binds.get(selectedSlot + 1), kind);
        if (ability == null || ability.isBlank()) {
            debug("capture native-only sequence=" + sequence + " kind=" + kind + " slot=" + (selectedSlot + 1)
                    + " localSlot=" + (localSlot + 1) + " reason=no-bound-ability binds=" + binds);
            return;
        }
        // A server-only third-party addon falls back to its normal vanilla
        // input path. We never hide server effects for code the client lacks.
        if (!ExactPredictionRuntime.supports(ability)) {
            debug("capture native-only sequence=" + sequence + " kind=" + kind + " ability=" + ability
                    + " reason=unsupported ready=" + ExactPredictionRuntime.isReady());
            return;
        }
        // Legacy Bukkit evaluates an ability input from the movement/look state
        // already processed before the swing packet. The local camera can move
        // again before this hook runs, so use the last pose actually sent to
        // the server for the input boundary.
        ServerPose localPose = new ServerPose(client.player.getX(), client.player.getY(), client.player.getZ(),
                client.player.getYaw(), client.player.getPitch(), client.player.getEyeY() - client.player.getY());
        ServerPose pose = poseForInput(serverPose, localPose);
        Vec3d origin = pose.eyePos();
        // This identity is emitted from ClientConnection's accepted pre-send
        // boundary. It therefore precedes the exact vanilla packet without
        // surviving a cancellation by an earlier networking mixin.
        pendingTaggedPacket = currentNativeInputPacket;
        pendingActionTag = new PendingActionTag(sequence, kind, selectedSlot, ability);
        final boolean cooldownActiveAtInput = ExactPredictionRuntime.isInputCooldownActive(ability, kind);
        if (suppressInput) {
            // Preserve the semantic action without executing it. Paper's
            // post-input receipt decides whether its legacy suppression gate
            // agreed. If Paper accepted the swing, reconciliation replays this
            // exact packet-time input through the common runtime client-side.
            ExactPredictionRuntime.recordNativeOnlyInput(sequence, kind, selectedSlot, pose, ability,
                    cooldownActiveAtInput);
            if (cooldownActiveAtInput && ClientPlayNetworking.canSend(PredictionPayloads.InputVeto.ID)) {
                ClientPlayNetworking.send(new PredictionPayloads.InputVeto(sessionId, sequence, kind, ability));
            }
            actionStartedAtMillis.put(sequence, System.currentTimeMillis());
            actionStartedAtMillis.entrySet().removeIf(entry -> nextSequence - entry.getKey() > 128);
            debug("capture native-only sequence=" + sequence + " kind=" + kind + " ability=" + ability
                    + " slot=" + (selectedSlot + 1) + " localSlot=" + (localSlot + 1)
                    + " reason=legacy-swing-suppression");
            return;
        }
        // Execute first in the same client frame. Networking is independent of
        // the local simulation and never gates its particles or movement.
        boolean locallyPredicted = ExactPredictionRuntime.shouldPredictInput(ability, kind)
                && ExactPredictionRuntime.input(sequence, kind, selectedSlot, pose, cooldownActiveAtInput);
        // A vanilla input carries no client timestamp. Without this narrow
        // negative gate, an input rejected locally at t=0 can arrive after a
        // short cooldown expires and be replayed by Paper at t=RTT/2. Send the
        // veto from this sendPacket hook, before the outer vanilla packet, so
        // both travel in the same ordered connection. A matching veto always
        // preserves the client's strict cooldown decision; only a tagged input
        // that was ready client-side may receive Paper's narrow 100 ms grace.
        final boolean cooldownVeto = cooldownActiveAtInput;
        if (cooldownVeto && ClientPlayNetworking.canSend(PredictionPayloads.InputVeto.ID)) {
            ClientPlayNetworking.send(new PredictionPayloads.InputVeto(sessionId, sequence, kind, ability));
        }
        actionStartedAtMillis.put(sequence, System.currentTimeMillis());
        actionStartedAtMillis.entrySet().removeIf(entry -> nextSequence - entry.getKey() > 128);
        debug("capture native sequence=" + sequence + " kind=" + kind + " ability=" + ability
                + " slot=" + (selectedSlot + 1) + " localSlot=" + (localSlot + 1)
                + " locallyPredicted=" + locallyPredicted
                + " cooldownVeto=" + cooldownVeto
                + " yaw=" + pose.yaw() + " pitch=" + pose.pitch() + " origin=" + origin);
    }

    public static ServerPose poseForInput(ServerPose serverVisible, ServerPose latestLocal) {
        return serverVisible != null ? serverVisible : latestLocal;
    }

    protected void recordServerVisibleSelectedSlot(MinecraftClient client, int slot) {
        if (slot < 0 || slot > 8) return;
        final int previousSlot = serverSelectedSlot;
        if (!ExactPredictionRuntime.notePredictedSelectedSlot(slot)) {
            // MultiAbilityManager rejected this edge locally just as Paper's
            // PlayerItemHeldEvent will. Keep later casts on the last accepted
            // server slot while the already-sent vanilla packet receives its
            // normal S2C correction.
            if (client != null && client.player != null && previousSlot >= 0 && previousSlot <= 8) {
                client.player.getInventory().setSelectedSlot(previousSlot);
            }
            debug("rejected predicted selected-slot slot=" + (slot + 1)
                    + " retained=" + (previousSlot + 1));
            return;
        }
        serverSelectedSlot = slot;
        if (DEBUG && active && client != null && client.player != null) {
            int localSlot = client.player.getInventory().getSelectedSlot();
            debug("server-visible selected-slot slot=" + (slot + 1) + " localSlot=" + (localSlot + 1));
        }
    }
}
