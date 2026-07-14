package com.projectkorra.projectkorra.fabric.client;

import com.projectkorra.projectkorra.fabric.prediction.PredictionPayloads;
import java.util.ArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.network.packet.Packet;
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
public final class PredictionClient {
    private static final int CAPABILITIES = 1 | 2 | 4 | 8;
    private static final PredictionClient INSTANCE = new PredictionClient();
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("projectkorra.prediction.debug", "true"));
    private static boolean initialized;

    private UUID sessionId;
    private boolean active;
    private long clientTick;
    private long nextSequence;
    private boolean previousSneaking;
    private long rightClickBlockUntilTick = -1;
    private long leftClickUntilTick = -1;
    private long lastHelloTick = -1_000;
    private long serverTimeOffsetMillis;
    private long estimatedOneWayLatencyMillis = 0;
    private long lastAuthorityTick = -1;
    private double airBlastDecay;
    private ServerPose serverPose;
    private boolean serverSneaking;
    private int serverSelectedSlot = -1;
    private ClientWorld runtimeWorld;
    private ClientPlayerEntity runtimePlayer;
    private boolean previousSpectator;
    private long firstSoftRespawnEffectRepairTick = -1;
    private long finalSoftRespawnEffectRepairTick = -1;
    private final Map<String, PredictionPayloads.ConfigEntry> config = new LinkedHashMap<>();
    private final Map<Integer, List<PredictionPayloads.ConfigEntry>> configChunks = new TreeMap<>();
    private UUID chunkSession;
    private long chunkEpoch;
    private int chunkCount;
    private final Map<Integer, String> binds = new LinkedHashMap<>();
    private final Map<String, Long> cooldowns = new LinkedHashMap<>();
    private final Map<Long, Long> inputSentAtMillis = new LinkedHashMap<>();
    private List<String> elements = List.of();
    private List<String> subElements = List.of();

    private PredictionClient() { }

    static void requestAuthorityHandoff(long sequence) {
        if (sequence <= 0 || !INSTANCE.active || INSTANCE.sessionId == null) return;
        ClientPlayNetworking.send(new PredictionPayloads.AuthorityHandoff(INSTANCE.sessionId, sequence));
        debug("authority handoff sent sequence=" + sequence);
    }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        debug("client prediction networking initialized");
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.ServerSnapshot.ID,
                (payload, context) -> INSTANCE.onSnapshot(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.PlayerState.ID,
                (payload, context) -> INSTANCE.onPlayerState(payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.StateDirective.ID,
                (payload, context) -> INSTANCE.onStateDirective(payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.ConfigChunk.ID,
                (payload, context) -> INSTANCE.onConfigChunk(payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.Reconcile.ID,
                (payload, context) -> INSTANCE.onReconcile(payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.TempBlockBatch.ID,
                (payload, context) -> INSTANCE.onTempBlocks(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.VelocityOwner.ID,
                (payload, context) -> INSTANCE.onVelocityOwner(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.VelocityOwnerV2.ID,
                (payload, context) -> INSTANCE.onVelocityOwner(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.AbilityRemoved.ID,
                (payload, context) -> INSTANCE.onAbilityRemoved(context.client(), payload));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> INSTANCE.onJoin(sender, client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> INSTANCE.reset(client));
        ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::tick);
    }

    public static void recordMovementPacket(MinecraftClient client, PlayerMoveC2SPacket packet) {
        if (client == null || client.player == null || packet == null) return;
        ClientPlayerEntity player = client.player;
        ServerPose previous = serverVisiblePose(client);
        if (previous == null) {
            previous = new ServerPose(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(),
                    player.getEyeY() - player.getY());
        }
        INSTANCE.serverPose = new ServerPose(
                packet.getX(previous.x()),
                packet.getY(previous.y()),
                packet.getZ(previous.z()),
                packet.getYaw(previous.yaw()),
                packet.getPitch(previous.pitch()),
                previous.eyeHeight());
        if (DEBUG && INSTANCE.active && (Math.abs(previous.yaw() - INSTANCE.serverPose.yaw()) > 0.001F
                || Math.abs(previous.pitch() - INSTANCE.serverPose.pitch()) > 0.001F
                || Math.abs(previous.x() - INSTANCE.serverPose.x()) > 1.0E-5
                || Math.abs(previous.y() - INSTANCE.serverPose.y()) > 1.0E-5
                || Math.abs(previous.z() - INSTANCE.serverPose.z()) > 1.0E-5)) {
            debug("server-pose movement yaw=" + INSTANCE.serverPose.yaw()
                    + " pitch=" + INSTANCE.serverPose.pitch()
                    + " eyeY=" + INSTANCE.serverPose.eyePos().y
                    + " pos=(" + INSTANCE.serverPose.x() + ", " + INSTANCE.serverPose.y() + ", " + INSTANCE.serverPose.z() + ")");
        }
        ExactPredictionRuntime.predictMovement(client, previous, INSTANCE.serverPose);
    }

    public static void beforeVanillaPacket(MinecraftClient client, Packet<?> packet) {
        PredictionClient owner = INSTANCE;
        if (packet instanceof UpdateSelectedSlotC2SPacket selectedSlot) {
            owner.recordServerVisibleSelectedSlot(client, selectedSlot.getSelectedSlot());
            return;
        }
        if (packet instanceof HandSwingC2SPacket swing) {
            if (swing.getHand() == Hand.MAIN_HAND) owner.captureLeftClick(client);
            return;
        }
        if (packet instanceof PlayerActionC2SPacket action) {
            if (action.getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) owner.captureLeftClick(client);
            return;
        }
        if (packet instanceof PlayerInteractBlockC2SPacket block) {
            if (block.getHand() == Hand.MAIN_HAND) {
                owner.rightClickBlockUntilTick = owner.clientTick + 2;
                owner.capture(client, PredictionPayloads.InputKind.RIGHT_CLICK_BLOCK);
            }
            return;
        }
        if (packet instanceof PlayerInteractItemC2SPacket item) {
            if (item.getHand() == Hand.MAIN_HAND && owner.rightClickBlockUntilTick < owner.clientTick) {
                owner.capture(client, PredictionPayloads.InputKind.RIGHT_CLICK);
            }
            return;
        }
        if (packet instanceof PlayerInteractEntityC2SPacket entity) {
            entity.handle(new PlayerInteractEntityC2SPacket.Handler() {
                @Override
                public void interact(Hand hand) {
                    if (hand == Hand.MAIN_HAND) owner.capture(client, PredictionPayloads.InputKind.RIGHT_CLICK_ENTITY);
                }

                @Override
                public void interactAt(Hand hand, Vec3d pos) {
                    if (hand == Hand.MAIN_HAND) owner.capture(client, PredictionPayloads.InputKind.RIGHT_CLICK_ENTITY);
                }

                @Override
                public void attack() {
                }
            });
            return;
        }
        if (packet instanceof PlayerInputC2SPacket input) {
            boolean sneaking = input.input().sneak();
            if (sneaking != owner.previousSneaking) {
                // Source selection for sneak transitions must use the pose the
                // server will apply for this packet. Capturing first used the
                // old eye height and produced the exact 0.35-block correction
                // visible in EarthBlast/WaterManipulation logs.
                owner.recordServerVisibleSneakState(client, sneaking);
                owner.capture(client, sneaking ? PredictionPayloads.InputKind.SNEAK_START : PredictionPayloads.InputKind.SNEAK_STOP);
                owner.previousSneaking = sneaking;
            } else {
                owner.recordServerVisibleSneakState(client, sneaking);
            }
        }
    }

    private void recordServerVisibleSneakState(MinecraftClient client, boolean sneaking) {
        serverSneaking = sneaking;
        if (client == null || client.player == null || serverPose == null) return;
        ClientPlayerEntity player = client.player;
        serverPose = new ServerPose(serverPose.x(), serverPose.y(), serverPose.z(), serverPose.yaw(),
                serverPose.pitch(), player.getEyeHeight(sneaking ? EntityPose.CROUCHING : EntityPose.STANDING));
        if (DEBUG && active) {
            debug("server-pose sneak sneaking=" + sneaking
                    + " yaw=" + serverPose.yaw() + " pitch=" + serverPose.pitch()
                    + " eyeY=" + serverPose.eyePos().y);
        }
    }

    public static ServerPose serverVisiblePose(MinecraftClient client) {
        ServerPose pose = INSTANCE.serverPose;
        if (pose != null || client == null || client.player == null) return pose;
        ClientPlayerEntity player = client.player;
        return new ServerPose(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(),
                player.getEyeY() - player.getY());
    }

    public static boolean serverVisibleSneaking(MinecraftClient client) {
        if (client == null || client.player == null) return INSTANCE.serverSneaking;
        return INSTANCE.serverPose == null ? client.player.isSneaking() : INSTANCE.serverSneaking;
    }

    public static int serverVisibleSelectedSlot(MinecraftClient client) {
        int slot = INSTANCE.serverSelectedSlot;
        if (slot >= 0 && slot < 9) return slot;
        return client != null && client.player != null ? client.player.getInventory().getSelectedSlot() : 0;
    }

    public static Map<String, PredictionPayloads.ConfigEntry> publicConfig() { return Map.copyOf(INSTANCE.config); }

    static void sendExactHitClaim(long actionSequence, Entity target, Vec3d contact) {
        PredictionClient owner = INSTANCE;
        if (!owner.active || owner.sessionId == null || !ClientPlayNetworking.canSend(PredictionPayloads.HitClaim.ID)) return;
        ClientPlayNetworking.send(new PredictionPayloads.HitClaim(owner.sessionId, actionSequence, owner.clientTick,
                target.getId(), contact.x, contact.y, contact.z));
    }

    private void onJoin(PacketSender sender, MinecraftClient client) {
        reset(client);
        debug("join: canSendHello=" + ClientPlayNetworking.canSend(PredictionPayloads.ClientHello.ID));
        if (ClientPlayNetworking.canSend(PredictionPayloads.ClientHello.ID)) {
            sender.sendPacket(new PredictionPayloads.ClientHello(PredictionPayloads.PROTOCOL_VERSION, clientTick, CAPABILITIES));
            lastHelloTick = clientTick;
            debug("sent hello on join tick=" + clientTick + " capabilities=" + CAPABILITIES);
        }
    }

    private void onSnapshot(MinecraftClient client, PredictionPayloads.ServerSnapshot snapshot) {
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
        sessionId = snapshot.sessionId();
        updateServerClock(snapshot.serverNowMillis());
        lastAuthorityTick = snapshot.serverTick();
        binds.clear(); binds.putAll(snapshot.binds());
        // Cooldowns are simulated by the local PK runtime. Server expiry
        // timestamps are authority for rejection only, never client state.
        cooldowns.clear();
        elements = snapshot.elements();
        subElements = snapshot.subElements();
        airBlastDecay = snapshot.airBlastDecay();
        active = ExactPredictionRuntime.start(client, List.copyOf(config.values()), binds, cooldowns, elements, subElements,
                airBlastDecay);
        rememberRuntimeIdentity(client);
        debug("snapshot applied active=" + active + " config=" + config.size() + " binds=" + binds
                + " elements=" + elements + " subElements=" + subElements);
        clearChunks();
    }

    private void onConfigChunk(PredictionPayloads.ConfigChunk chunk) {
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

    private void mergeConfig(PredictionPayloads.ConfigEntry entry) {
        config.merge(entry.path(), entry, (first, second) -> {
            if (first.type() != PredictionPayloads.ValueType.STRING_LIST || second.type() != first.type()) return second;
            ArrayList<String> merged = new ArrayList<>(first.values());
            merged.addAll(second.values());
            return new PredictionPayloads.ConfigEntry(first.path(), first.type(), List.copyOf(merged));
        });
    }

    private void clearChunks() {
        configChunks.clear(); chunkSession = null; chunkEpoch = 0; chunkCount = 0;
    }

    private void onPlayerState(PredictionPayloads.PlayerState state) {
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
        airBlastDecay = state.airBlastDecay();
        if (!active) {
            MinecraftClient client = MinecraftClient.getInstance();
            active = ExactPredictionRuntime.start(client, List.copyOf(config.values()), binds, cooldowns, elements, subElements,
                    airBlastDecay);
            rememberRuntimeIdentity(client);
            debug("player state retried runtime active=" + active + " config=" + config.size()
                    + " binds=" + binds + " elements=" + elements + " subElements=" + subElements);
            if (!active) return;
        }
        final Map<String, Long> authoritativeCooldowns = convertCooldowns(state.cooldowns());
        ExactPredictionRuntime.updatePlayerState(binds, authoritativeCooldowns, elements, subElements, airBlastDecay);
        ExactPredictionRuntime.reconcileActiveFlightAbilities(state.activeFlightAbilities());
        debug("player state applied binds=" + binds + " cooldowns=" + authoritativeCooldowns.keySet()
                + " elements=" + elements + " subElements=" + subElements);
    }

    private void onStateDirective(PredictionPayloads.StateDirective directive) {
        if (!active || sessionId == null || !sessionId.equals(directive.sessionId())) return;
        updateServerClock(directive.serverNowMillis());
        if (!directive.removedCooldown().isBlank()) {
            ExactPredictionRuntime.removeLocalCooldown(directive.removedCooldown());
        }
        if (!directive.addedCooldown().isBlank() && directive.cooldownUntil() > 0L) {
            long clientUntil = convertCooldown(directive.cooldownUntil());
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

    private void onReconcile(PredictionPayloads.Reconcile reconcile) {
        if (!active || !reconcile.sessionId().equals(sessionId)) {
            debug("reconcile ignored active=" + active + " ability=" + reconcile.ability());
            return;
        }
        updateLatencyEstimate(reconcile.sequence());
        updateServerClock(reconcile.serverNowMillis());
        lastAuthorityTick = Math.max(lastAuthorityTick, reconcile.serverTick());
        ExactPredictionRuntime.reconcile(reconcile.sequence(), reconcile.accepted(),
                new Vec3d(reconcile.originX(), reconcile.originY(), reconcile.originZ()),
                reconcile.ability(), 0L);
        debug("reconcile sequence=" + reconcile.sequence() + " accepted=" + reconcile.accepted()
                + " ability=" + reconcile.ability() + " cooldownUntil=" + reconcile.cooldownUntil()
                + " localCooldownSource=exact-runtime"
                + " clockOffsetMs=" + serverTimeOffsetMillis
                + " oneWayMs=" + estimatedOneWayLatencyMillis);
    }

    private void onTempBlocks(MinecraftClient client, PredictionPayloads.TempBlockBatch batch) {
        if (client.world != null) ExactPredictionRuntime.applyTempBlockBatch(client.world, batch);
    }

    private void onVelocityOwner(MinecraftClient client, PredictionPayloads.VelocityOwner owner) {
        if (client.player != null) ExactPredictionRuntime.noteVelocityOwner(client.player, owner);
    }

    private void onVelocityOwner(MinecraftClient client, PredictionPayloads.VelocityOwnerV2 owner) {
        if (client.player != null) ExactPredictionRuntime.noteVelocityOwner(client.player, owner);
    }

    private void onAbilityRemoved(MinecraftClient client, PredictionPayloads.AbilityRemoved removed) {
        if (client.player != null) ExactPredictionRuntime.removeAuthoritativeAbility(client.player, removed);
    }

    private void tick(MinecraftClient client) {
        clientTick++;
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
        if (active) {
            if (runtimeWorld != client.world || runtimePlayer != client.player) {
                restartForWorldChange(client);
                if (!active) return;
            }
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
                rebuildStatusEffectAttributes(client.player);
                firstSoftRespawnEffectRepairTick = -1;
            }
            if (clientTick == finalSoftRespawnEffectRepairTick) {
                rebuildStatusEffectAttributes(client.player);
                finalSoftRespawnEffectRepairTick = -1;
            }
            if (rightClickBlockUntilTick < clientTick - 4) rightClickBlockUntilTick = -1;
            if (leftClickUntilTick < clientTick - 4) leftClickUntilTick = -1;
            cooldowns.entrySet().removeIf(entry -> entry.getValue() <= System.currentTimeMillis());
            ExactPredictionRuntime.tick(client);
        }
    }

    private void captureLeftClick(MinecraftClient client) {
        if (leftClickUntilTick >= clientTick) return;
        leftClickUntilTick = clientTick + 1;
        capture(client, PredictionPayloads.InputKind.LEFT_CLICK);
    }

    private void capture(MinecraftClient client, PredictionPayloads.InputKind kind) {
        if (!active || sessionId == null || client.player == null || client.world == null
                || !ClientPlayNetworking.canSend(PredictionPayloads.InputFrame.ID)) {
            debug("capture skipped kind=" + kind + " active=" + active + " session=" + sessionId
                    + " player=" + (client.player != null) + " world=" + (client.world != null)
                    + " canSendInput=" + ClientPlayNetworking.canSend(PredictionPayloads.InputFrame.ID));
            return;
        }
        int selectedSlot = serverVisibleSelectedSlot(client);
        int localSlot = client.player.getInventory().getSelectedSlot();
        String ability = ExactPredictionRuntime.inputAbilityName(selectedSlot, binds.get(selectedSlot + 1), kind);
        if (ability == null || ability.isBlank()) {
            debug("capture skipped kind=" + kind + " slot=" + (selectedSlot + 1)
                    + " localSlot=" + (localSlot + 1) + " reason=no-bound-ability binds=" + binds);
            return;
        }
        // A server-only third-party addon falls back to its normal vanilla
        // input path. We never hide server effects for code the client lacks.
        if (!ExactPredictionRuntime.supports(ability)) {
            debug("capture skipped kind=" + kind + " ability=" + ability + " reason=unsupported ready=" + ExactPredictionRuntime.isReady());
            return;
        }
        long sequence = ++nextSequence;
        ServerPose pose = serverVisiblePose(client);
        if (pose == null) {
            pose = new ServerPose(client.player.getX(), client.player.getY(), client.player.getZ(),
                    client.player.getYaw(), client.player.getPitch(), client.player.getEyeY() - client.player.getY());
        }
        Vec3d origin = pose.eyePos();
        boolean locallyBlockedByCooldown = ExactPredictionRuntime.isOnLocalCooldown(ability);
        if (ClientPlayNetworking.canSend(PredictionPayloads.ActionPrepare.ID)) {
            ClientPlayNetworking.send(new PredictionPayloads.ActionPrepare(sessionId, sequence, clientTick, kind,
                    selectedSlot, pose.yaw(), pose.pitch(), origin.x, origin.y, origin.z));
        }
        // Execute first in the same client frame. Networking is independent of
        // the local simulation and never gates its particles or movement.
        boolean locallyPredicted = ExactPredictionRuntime.shouldPredictInput(ability, kind)
                && ExactPredictionRuntime.input(sequence, kind, pose);
        inputSentAtMillis.put(sequence, System.currentTimeMillis());
        inputSentAtMillis.entrySet().removeIf(entry -> nextSequence - entry.getKey() > 128);
        ClientPlayNetworking.send(new PredictionPayloads.InputFrame(sessionId, sequence, clientTick, kind,
                selectedSlot, pose.yaw(), pose.pitch(), origin.x, origin.y, origin.z,
                locallyPredicted, locallyBlockedByCooldown));
        debug("capture sent sequence=" + sequence + " kind=" + kind + " ability=" + ability
                + " slot=" + (selectedSlot + 1) + " localSlot=" + (localSlot + 1)
                + " locallyPredicted=" + locallyPredicted
                + " locallyBlockedByCooldown=" + locallyBlockedByCooldown
                + " yaw=" + pose.yaw() + " pitch=" + pose.pitch() + " origin=" + origin);
    }

    private void recordServerVisibleSelectedSlot(MinecraftClient client, int slot) {
        if (slot < 0 || slot > 8) return;
        serverSelectedSlot = slot;
        ExactPredictionRuntime.notePredictedSelectedSlot(slot);
        if (DEBUG && active && client != null && client.player != null) {
            int localSlot = client.player.getInventory().getSelectedSlot();
            debug("server-visible selected-slot slot=" + (slot + 1) + " localSlot=" + (localSlot + 1));
        }
    }

    private void reset(MinecraftClient client) {
        debug("reset active=" + active + " session=" + sessionId);
        ExactPredictionRuntime.stop(client);
        active = false; sessionId = null; nextSequence = 0;
        lastHelloTick = clientTick - 1_000;
        config.clear(); binds.clear(); cooldowns.clear();
        inputSentAtMillis.clear();
        clearChunks();
        elements = List.of(); subElements = List.of();
        serverPose = null;
        serverTimeOffsetMillis = 0;
        estimatedOneWayLatencyMillis = 0;
        lastAuthorityTick = -1;
        airBlastDecay = 0.0;
        rightClickBlockUntilTick = -1;
        leftClickUntilTick = -1;
        previousSneaking = client.player != null && client.player.isSneaking();
        previousSpectator = client.player != null && client.player.isSpectator();
        firstSoftRespawnEffectRepairTick = -1;
        finalSoftRespawnEffectRepairTick = -1;
        serverSneaking = previousSneaking;
        serverSelectedSlot = client.player == null ? -1 : client.player.getInventory().getSelectedSlot();
        runtimeWorld = null;
        runtimePlayer = null;
    }

    private void restartForWorldChange(MinecraftClient client) {
        ClientWorld previousWorld = runtimeWorld;
        boolean playerReplaced = runtimePlayer != null && runtimePlayer != client.player;
        debug("client world changed old=" + worldKey(previousWorld) + " new=" + worldKey(client.world)
                + " playerReplaced=" + playerReplaced + "; restarting local prediction runtime");
        ExactPredictionRuntime.stop(client);
        active = false;

        ClientPlayerEntity player = client.player;
        if (playerReplaced) rebuildStatusEffectAttributes(player);
        serverPose = new ServerPose(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(),
                player.getEyeY() - player.getY());
        previousSneaking = player.isSneaking();
        previousSpectator = player.isSpectator();
        serverSneaking = previousSneaking;
        serverSelectedSlot = player.getInventory().getSelectedSlot();
        rightClickBlockUntilTick = -1;
        leftClickUntilTick = -1;

        active = ExactPredictionRuntime.start(client, List.copyOf(config.values()), binds, cooldowns,
                elements, subElements, airBlastDecay);
        rememberRuntimeIdentity(client);
        debug("client world runtime restarted active=" + active + " session=" + sessionId);
    }

    /**
     * Respawn copies the active-effect map onto a new client player object,
     * but an external spectator/survival transition can leave that object's
     * transient attribute modifiers out of sync with the copied map. Reapply
     * exact copies so vanilla rebuilds modifiers such as Speed while keeping
     * every visible effect property unchanged.
     */
    private static void rebuildStatusEffectAttributes(ClientPlayerEntity player) {
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

    private void rememberRuntimeIdentity(MinecraftClient client) {
        if (!active || client == null) {
            runtimeWorld = null;
            runtimePlayer = null;
            return;
        }
        runtimeWorld = client.world;
        runtimePlayer = client.player;
    }

    private static String worldKey(ClientWorld world) {
        return world == null ? "null" : world.getRegistryKey().getValue().toString();
    }

    private void updateServerClock(long serverNowMillis) {
        if (serverNowMillis > 0) serverTimeOffsetMillis = System.currentTimeMillis() - serverNowMillis;
    }

    private void updateLatencyEstimate(long sequence) {
        Long sentAt = inputSentAtMillis.remove(sequence);
        if (sentAt == null) return;
        long rtt = Math.max(0L, System.currentTimeMillis() - sentAt);
        long sample = Math.min(750L, rtt / 2L);
        estimatedOneWayLatencyMillis = estimatedOneWayLatencyMillis <= 0
                ? sample : (estimatedOneWayLatencyMillis * 3L + sample) / 4L;
    }

    private Map<String, Long> convertCooldowns(Map<String, Long> serverCooldowns) {
        Map<String, Long> converted = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        serverCooldowns.forEach((ability, until) -> {
            long clientUntil = convertCooldown(until);
            if (clientUntil > now) converted.put(ability, clientUntil);
        });
        return converted;
    }

    private void mergeAcceptedCooldowns(Map<String, Long> authoritative) {
        long now = System.currentTimeMillis();
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        // State packets describe accepted server progress and can arrive one
        // RTT after the exact client started the same cooldown. Never extend
        // that earlier local expiry merely because the server began later.
        authoritative.forEach((ability, until) -> cooldowns.merge(ability, until, Math::min));
    }

    private long convertCooldown(long serverCooldownUntil) {
        if (serverCooldownUntil <= 0) return 0;
        // serverTimeOffsetMillis is measured when the packet arrives and thus
        // includes its estimated one-way transit time. Remove that transport
        // component once when translating the absolute server expiry. The old
        // activation path removed it a second time via a prediction lead,
        // which made the client usable before the server cooldown had ended.
        return serverCooldownUntil + serverTimeOffsetMillis - estimatedOneWayLatencyMillis;
    }

    private static void debug(String message) {
        if (DEBUG) System.out.println("[ProjectKorraPrediction] " + message);
    }

    public record ServerPose(double x, double y, double z, float yaw, float pitch, double eyeHeight) {
        public Vec3d eyePos() {
            return new Vec3d(x, y + eyeHeight, z);
        }
    }
}
