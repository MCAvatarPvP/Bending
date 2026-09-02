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

public abstract class PredictionClientWorldState extends PredictionClientServerState {
    protected void sendReady() {
        if (readySent || !active || sessionId == null
                || !ClientPlayNetworking.canSend(PredictionPayloads.ClientReady.ID)) return;
        ClientPlayNetworking.send(new PredictionPayloads.ClientReady(
                sessionId, ExactPredictionRuntime.supportedAbilities()));
        readySent = true;
        nextSequence = 0L;
        debug("prediction-ready sent abilities=" + ExactPredictionRuntime.supportedAbilities().size());
    }

    /**
     * Fabric fires this while processing the respawn/dimension packet, before
     * the following destination chunks are rendered. Rebuild here instead of
     * waiting for END_CLIENT_TICK: a TempBlock snapshot received in that gap
     * would otherwise be staged into the new runtime and then erased by the
     * delayed restart.
     */
    protected void onClientWorldChange(final MinecraftClient client, final ClientWorld world) {
        if (client == null || world == null || sessionId == null || !readySent) return;
        if (active && runtimeWorld == world && runtimePlayer == client.player) return;

        recordWorldTransition("client world boundary target=" + worldRef(world));
        worldTempBlockResyncPending = true;
        worldTempBlockRequestSent = false;
        if (active) {
            clientWorldBoundaryAwaitingIdentity = true;
            restartForWorldChange(client);
        }
        requestWorldTempBlockSnapshot();
    }

    /**
     * ClientReady is deliberately idempotent once the session is ready. A
     * repeated message requests the complete ledger for the player's current
     * world; unlike the initial handshake it must not reset native input
     * ordinals or the client's next action sequence.
     */
    protected void requestWorldTempBlockSnapshot() {
        if (!worldTempBlockResyncPending || worldTempBlockRequestSent || !active || sessionId == null
                || !ClientPlayNetworking.canSend(PredictionPayloads.ClientReady.ID)) return;
        ClientPlayNetworking.send(new PredictionPayloads.ClientReady(
                sessionId, ExactPredictionRuntime.supportedAbilities()));
        worldTempBlockRequestSent = true;
        debug("requested destination-world TempBlock ledger world=" + worldKey(runtimeWorld));
        recordWorldTransition("requested destination TempBlock ledger");
    }

    protected void onTempBlocks(MinecraftClient client, PredictionPayloads.TempBlockBatch batch) {
        if (client == null || batch == null) return;
        onClientWorldChange(client, client.world);
        if (!active || !ExactPredictionRuntime.isReady()) {
            // ClientWorldEvents fires before vanilla installs the replacement
            // ClientPlayerEntity. A snapshot in that gap cannot be consumed by
            // the stopped runtime; retain the request so the next successful
            // start obtains a fresh complete ledger instead of losing it.
            worldTempBlockResyncPending = true;
            worldTempBlockRequestSent = false;
            debug("deferred TempBlock batch until destination runtime is ready"
                    + " snapshot=" + batch.snapshot() + " generation=" + batch.worldGeneration());
            recordWorldTransition("deferred TempBlock batch snapshot=" + batch.snapshot()
                    + " generation=" + batch.worldGeneration());
            return;
        }
        if (!acceptServerWorldState(client, batch.sessionId(), batch.worldGeneration(),
                batch.worldIdentity(), !batch.snapshot(), batch.snapshot())) return;
        final boolean completingDestinationLedger = batch.snapshot() && worldTempBlockResyncPending;
        if (client.world != null) {
            final ClientTempBlockAuthority.BatchResult result =
                    ExactPredictionRuntime.applyTempBlockBatch(client.world, batch);
            if (result == ClientTempBlockAuthority.BatchResult.RESYNC_REQUIRED) {
                worldTempBlockResyncPending = true;
                worldTempBlockRequestSent = false;
                debug("TempBlock stream gap; requested authoritative snapshot sequence="
                        + batch.streamSequence());
                requestWorldTempBlockSnapshot();
                return;
            }
            if (batch.snapshot()
                    && result == ClientTempBlockAuthority.BatchResult.APPLIED) {
                clientWorldBoundaryAwaitingIdentity = false;
                worldTempBlockResyncPending = false;
                worldTempBlockRequestSent = false;
                if (completingDestinationLedger) {
                    recordWorldTransition("applied destination TempBlock snapshot generation="
                            + batch.worldGeneration() + " snapshot=" + batch.snapshotId()
                            + " parts=" + batch.snapshotParts());
                }
            }
        }
    }

    protected void onVelocityOwner(MinecraftClient client, PredictionPayloads.VelocityOwner owner) {
        if (client.player != null) ExactPredictionRuntime.noteVelocityOwner(client.player, owner);
    }

    protected void onVelocityOwner(MinecraftClient client, PredictionPayloads.VelocityOwnerV2 owner) {
        if (client.player != null) ExactPredictionRuntime.noteVelocityOwner(client.player, owner);
    }

    protected void onAbilityStateOwner(MinecraftClient client, PredictionPayloads.AbilityStateOwner owner) {
        if (client.player != null) ExactPredictionRuntime.noteAbilityStateOwner(client.player, owner);
    }

    protected void onTempFallingBlock(MinecraftClient client,
                                    PredictionPayloads.TempFallingBlockReceipt receipt) {
        if (client.player != null) ExactPredictionRuntime.noteTempFallingBlock(client.player, receipt);
    }

    protected void onTempFallingBlockPrepare(MinecraftClient client,
                                           PredictionPayloads.TempFallingBlockPrepare prepare) {
        if (client.player != null) ExactPredictionRuntime.noteTempFallingBlockPrepare(client.player, prepare);
    }

    protected void onDirectBlock(MinecraftClient client,
                               PredictionPayloads.DirectBlockReceipt receipt) {
        if (client.player != null) ExactPredictionRuntime.noteDirectBlock(client.player, receipt);
    }

    protected void onAbilityRemoved(MinecraftClient client, PredictionPayloads.AbilityRemoved removed) {
        if (removed.predictionRejected()) cooldowns.remove(removed.ability());
        if (client.player != null) ExactPredictionRuntime.removeAuthoritativeAbility(client.player, removed);
    }

    protected void onAbilityTransfer(MinecraftClient client, PredictionPayloads.AbilityTransfer transfer) {
        if (client.player != null) ExactPredictionRuntime.transferAuthoritativeAbility(client.player, transfer);
    }

    protected void onGlidingStateOwner(final MinecraftClient client,
                                     final PredictionPayloads.GlidingStateOwner owner) {
        if (client.player != null) ExactPredictionRuntime.noteGlidingStateOwner(client.player, owner);
    }

    protected void onAirGliderState(final MinecraftClient client,
                                  final PredictionPayloads.AirGliderState state) {
        if (client.player != null) ExactPredictionRuntime.applyAirGliderState(client.player, state);
    }
}
