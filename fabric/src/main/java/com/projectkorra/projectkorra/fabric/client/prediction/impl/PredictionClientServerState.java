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

public abstract class PredictionClientServerState extends PredictionClientNativeInput {
    protected void onJoin(PacketSender sender, MinecraftClient client) {
        reset(client);
        if (!ClientBendingConfig.isEnabled()) return;
        debug("join: canSendHello=" + ClientPlayNetworking.canSend(PredictionPayloads.ClientHello.ID));
        if (ClientPlayNetworking.canSend(PredictionPayloads.ClientHello.ID)) {
            sender.sendPacket(new PredictionPayloads.ClientHello(PredictionPayloads.PROTOCOL_VERSION, clientTick, CAPABILITIES));
            lastHelloTick = clientTick;
            debug("sent hello on join tick=" + clientTick + " capabilities=" + CAPABILITIES);
        }
    }

    protected void onSnapshot(MinecraftClient client, PredictionPayloads.ServerSnapshot snapshot) {
        if (!ClientBendingConfig.isEnabled()) return;
        debug("snapshot received protocol=" + snapshot.protocolVersion() + " config=" + snapshot.config().size()
                + " binds=" + snapshot.binds().size() + " chunksPending=" + configChunks.size());
        if (snapshot.protocolVersion() != PredictionPayloads.PROTOCOL_VERSION) {
            debug("snapshot ignored: protocol mismatch expected=" + PredictionPayloads.PROTOCOL_VERSION);
            return;
        }
        if (snapshot.config().isEmpty() && chunkSession != null
                && chunkSession.equals(snapshot.sessionId()) && chunkEpoch == snapshot.configEpoch()) {
            if (chunkCount <= 0 || configChunks.size() != chunkCount) {
                debug("snapshot waiting for config chunks have=" + configChunks.size() + " need=" + chunkCount);
                return;
            }
            config.clear();
            configChunks.values().forEach(entries -> entries.forEach(this::mergeConfig));
        } else {
            config.clear();
            snapshot.config().forEach(this::mergeConfig);
        }
        final boolean sessionChanged = sessionId != null && !snapshot.sessionId().equals(sessionId);
        if (!snapshot.sessionId().equals(sessionId)) {
            if (sessionChanged) {
                // A proxy/backend replacement can preserve both the vanilla
                // ClientWorld and its registry key. Session-local action,
                // layer, and revision ids are nevertheless unrelated, so the
                // old common runtime must be retired before importing them.
                ExactPredictionRuntime.stop(client);
                active = false;
                runtimeWorld = null;
                runtimePlayer = null;
                worldTempBlockResyncPending = true;
                worldTempBlockRequestSent = false;
            }
            nextSequence = 0L;
            readySent = false;
            actionStartedAtMillis.clear();
            pendingHitClaims.clear();
            currentNativeInputPacket = null;
            pendingTaggedPacket = null;
            pendingActionTag = null;
            serverWorldIdentity = null;
            serverWorldGeneration = -1L;
            clientWorldBoundaryAwaitingIdentity = false;
        }
        sessionId = snapshot.sessionId();
        updateServerClock(snapshot.serverNowMillis());
        lastAuthorityTick = snapshot.serverTick();
        maxRewindTicks = Math.max(0, snapshot.maxRewindTicks());
        binds.clear(); binds.putAll(snapshot.binds());
        // Prediction still starts every new cooldown immediately. Importing an
        // already-active Paper cooldown here only closes reconnect/world-change
        // gaps where no local start event exists to predict.
        rememberAuthoritativeCooldowns(convertCooldowns(snapshot.cooldowns()));
        elements = snapshot.elements();
        subElements = snapshot.subElements();
        permissions = snapshot.permissions();
        airBlastDecay = snapshot.airBlastDecay();
        chiBlocked = snapshot.chiBlocked();
        cosmetics = snapshot.cosmetics();
        regionProtection = snapshot.regionProtection();
        startRuntime(client, "snapshot");
        sendReady();
        debug("snapshot applied active=" + active + " config=" + config.size() + " binds=" + binds
                + " elements=" + elements + " subElements=" + subElements);
        clearChunks();
    }

    protected void onConfigChunk(PredictionPayloads.ConfigChunk chunk) {
        if (chunk.chunkCount() <= 0 || chunk.chunkCount() > PredictionPayloads.MAX_CONFIG_ENTRIES
                || chunk.chunkIndex() < 0 || chunk.chunkIndex() >= chunk.chunkCount()) return;
        if (!chunk.sessionId().equals(chunkSession) || chunk.configEpoch() != chunkEpoch || chunk.chunkCount() != chunkCount) {
            configChunks.clear(); chunkSession = chunk.sessionId(); chunkEpoch = chunk.configEpoch(); chunkCount = chunk.chunkCount();
            debug("config chunk session started count=" + chunkCount + " epoch=" + chunkEpoch);
        }
        configChunks.putIfAbsent(chunk.chunkIndex(), chunk.config());
        debug("config chunk received index=" + chunk.chunkIndex() + " have=" + configChunks.size() + "/" + chunkCount
                + " entries=" + chunk.config().size());
    }

    protected void mergeConfig(PredictionPayloads.ConfigEntry entry) {
        config.merge(entry.path(), entry, (first, second) -> {
            if (first.type() != PredictionPayloads.ValueType.STRING_LIST || second.type() != first.type()) return second;
            ArrayList<String> merged = new ArrayList<>(first.values());
            merged.addAll(second.values());
            return new PredictionPayloads.ConfigEntry(first.path(), first.type(), List.copyOf(merged));
        });
    }

    protected void clearChunks() {
        configChunks.clear(); chunkSession = null; chunkEpoch = 0; chunkCount = 0;
    }

    protected void onPlayerState(PredictionPayloads.PlayerState state) {
        if (!state.sessionId().equals(sessionId)) {
            debug("player state ignored active=" + active + " sessionMatches=false");
            return;
        }
        if (state.serverTick() < lastAuthorityTick) {
            debug("player state ignored stale tick=" + state.serverTick() + " lastAuthorityTick=" + lastAuthorityTick);
            return;
        }
        updateServerClock(state.serverNowMillis());
        lastAuthorityTick = state.serverTick();
        binds.clear(); binds.putAll(state.binds());
        elements = state.elements();
        subElements = state.subElements();
        permissions = state.permissions();
        airBlastDecay = state.airBlastDecay();
        chiBlocked = state.chiBlocked();
        cosmetics = state.cosmetics();
        regionProtection = state.regionProtection();
        if (!active) {
            MinecraftClient client = MinecraftClient.getInstance();
            startRuntime(client, "player-state");
            debug("player state retried runtime active=" + active + " config=" + config.size()
                    + " binds=" + binds + " elements=" + elements + " subElements=" + subElements);
            if (!active) return;
        }
        sendReady();
        final Map<String, Long> authoritativeCooldowns = convertCooldowns(state.cooldowns());
        rememberAuthoritativeCooldowns(authoritativeCooldowns);
        ExactPredictionRuntime.updatePlayerState(binds, authoritativeCooldowns, elements, subElements,
                permissions, airBlastDecay, chiBlocked, cosmetics, regionProtection);
        ExactPredictionRuntime.reconcileActiveFlightAbilities(state.activeFlightAbilities(), state.acknowledgedSequence());
        debug("player state applied binds=" + binds + " cooldowns=" + authoritativeCooldowns.keySet()
                + " elements=" + elements + " subElements=" + subElements);
    }

    protected void onStateDirective(PredictionPayloads.StateDirective directive) {
        if (!active || sessionId == null || !sessionId.equals(directive.sessionId())) return;
        updateServerClock(directive.serverNowMillis());
        if (!directive.removedCooldown().isBlank()) {
            cooldowns.remove(directive.removedCooldown());
            ExactPredictionRuntime.removeLocalCooldown(directive.removedCooldown());
        }
        if (!directive.addedCooldown().isBlank() && directive.cooldownUntil() > 0L) {
            long clientUntil = convertCooldown(directive.cooldownUntil());
            if (clientUntil > System.currentTimeMillis()) {
                cooldowns.merge(directive.addedCooldown(), clientUntil, Math::max);
            }
            ExactPredictionRuntime.enforceLocalCooldown(directive.addedCooldown(), clientUntil);
        }
        if (directive.resetAirBlast()) ExactPredictionRuntime.resetLocalAirBlast();
        if (Double.isFinite(directive.airBlastDecay())) {
            ExactPredictionRuntime.setLocalAirBlastDecay(directive.airBlastDecay());
        }
        debug("state directive removedCooldown=" + directive.removedCooldown()
                + " addedCooldown=" + directive.addedCooldown()
                + " cooldownUntil=" + directive.cooldownUntil()
                + " resetAirBlast=" + directive.resetAirBlast()
                + " airBlastDecay=" + directive.airBlastDecay());
    }

    protected void onCooldownState(final PredictionPayloads.CooldownState state) {
        if (!active || sessionId == null || !sessionId.equals(state.sessionId())) return;
        updateServerClock(state.serverNowMillis());
        final Map<String, Long> authoritative = convertCooldowns(state.cooldowns());
        rememberAuthoritativeCooldowns(authoritative);
        ExactPredictionRuntime.synchronizeCooldowns(authoritative);
        debug("authoritative cooldown synchronization applied cooldowns=" + authoritative.keySet());
    }

    protected void onReconcile(PredictionPayloads.Reconcile reconcile) {
        if (!active || !reconcile.sessionId().equals(sessionId)) {
            debug("reconcile ignored active=" + active + " ability=" + reconcile.ability());
            return;
        }
        updateServerClock(reconcile.serverNowMillis());
        lastAuthorityTick = Math.max(lastAuthorityTick, reconcile.serverTick());
        final long clientCooldownUntil = convertCooldown(reconcile.cooldownUntil());
        if (clientCooldownUntil > System.currentTimeMillis() && reconcile.ability() != null
                && !reconcile.ability().isBlank()) {
            cooldowns.merge(reconcile.ability(), clientCooldownUntil, Math::max);
        }
        ExactPredictionRuntime.reconcile(reconcile.sequence(),
                new Vec3d(reconcile.originX(), reconcile.originY(), reconcile.originZ()),
                reconcile.ability(), clientCooldownUntil, reconcile.inputHandled(),
                reconcile.comboRecorded(), reconcile.createdAbilities());
        debug("reconcile sequence=" + reconcile.sequence() + " accepted=" + reconcile.accepted()
                + " ability=" + reconcile.ability() + " cooldownUntil=" + reconcile.cooldownUntil()
                + " handled=" + reconcile.inputHandled()
                + " comboRecorded=" + reconcile.comboRecorded()
                + " created=" + reconcile.createdAbilities()
                + " localCooldownSource=exact-runtime"
                + " clockOffsetMs=" + serverTimeOffsetMillis
                + " oneWayMs=" + estimatedOneWayLatencyMillis);
    }

    protected void onNativeAction(PredictionPayloads.NativeAction action) {
        if (!active || sessionId == null || action == null || !sessionId.equals(action.sessionId())) return;
        lastAuthorityTick = Math.max(lastAuthorityTick, action.serverTick());
        final boolean confirmed = ExactPredictionRuntime.noteNativeAction(action);
        final long localSequence = ExactPredictionRuntime.correlatedLocalActionSequence(action.actionSequence());
        if (confirmed && localSequence > 0L) updateLatencyEstimate(localSequence);
        debug("native action sequence=" + action.actionSequence() + " kind=" + action.kind()
                + " ability=" + action.ability() + " predictable=" + action.predictable()
                + " taggedLocalSequence=" + action.clientActionSequence()
                + " confirmed=" + confirmed + " localSequence=" + localSequence);
    }

    protected void onServerWorldState(final MinecraftClient client,
                                    final PredictionPayloads.ServerWorldState state) {
        if (state == null) return;
        acceptServerWorldState(client, state.sessionId(), state.worldGeneration(),
                state.worldIdentity(), true, false);
    }

    /**
     * Accepts an ordered physical-world boundary from either the early marker
     * or the TempBlock batch itself. The batch path makes the boundary atomic:
     * an old backend/session or earlier visit can never mutate the destination
     * ClientWorld merely because both use {@code minecraft:overworld}.
     */
    protected boolean acceptServerWorldState(final MinecraftClient client, final UUID incomingSession,
                                           final long incomingGeneration, final String incomingIdentity,
                                           final boolean requestLedger, final boolean snapshotBoundary) {
        if (client == null || sessionId == null || incomingSession == null
                || !sessionId.equals(incomingSession) || incomingGeneration <= 0L
                || incomingIdentity == null || incomingIdentity.isBlank()) return false;
        if (incomingGeneration < this.serverWorldGeneration) {
            debug("ignored stale world scope generation=" + incomingGeneration
                    + " current=" + this.serverWorldGeneration + " identity=" + incomingIdentity);
            return false;
        }
        if (incomingGeneration == this.serverWorldGeneration) {
            final boolean matches = incomingIdentity.equals(this.serverWorldIdentity);
            if (!matches) return false;
            if (this.clientWorldBoundaryAwaitingIdentity) {
                // A marker or incremental packet from the outgoing world can
                // race the Fabric ClientWorld replacement. Only a complete
                // equal-generation snapshot can prove a same-world respawn is
                // ready; a newer generation remains sufficient on its own.
                if (!snapshotBoundary) {
                    recordWorldTransition("rejected equal-generation packet before destination snapshot"
                            + " generation=" + incomingGeneration);
                    return false;
                }
            }
            return true;
        }

        final long previousGeneration = this.serverWorldGeneration;
        final String previousIdentity = this.serverWorldIdentity;
        this.serverWorldGeneration = incomingGeneration;
        this.serverWorldIdentity = incomingIdentity;
        final boolean changed = previousGeneration >= 0L;
        final boolean clientBoundaryAlreadyRestarted = this.clientWorldBoundaryAwaitingIdentity;
        // A first snapshot fragment proves the world identity but not the
        // ledger. Keep the boundary closed until the authority commits its
        // final fragment. A dedicated WORLD_STATE marker may open it now.
        this.clientWorldBoundaryAwaitingIdentity =
                clientBoundaryAlreadyRestarted && snapshotBoundary;
        debug("authoritative world scope generation=" + previousGeneration + "->" + incomingGeneration
                + " identity=" + previousIdentity + "->" + incomingIdentity
                + " changed=" + changed + " clientBoundary=" + clientBoundaryAlreadyRestarted);
        recordWorldTransition("accepted authoritative scope " + previousGeneration + "->" + incomingGeneration
                + " identity=" + incomingIdentity + " clientBoundary=" + clientBoundaryAlreadyRestarted);
        if (!changed) return true;

        if (requestLedger) {
            this.worldTempBlockResyncPending = true;
            this.worldTempBlockRequestSent = false;
        }
        if (!clientBoundaryAlreadyRestarted && active) restartForWorldChange(client);
        if (requestLedger) requestWorldTempBlockSnapshot();
        return true;
    }
}
