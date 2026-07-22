package com.projectkorra.projectkorra.fabric.client;

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
public final class PredictionClient {
    private static final ThreadLocal<Boolean> INPUT_SNEAK_OVERRIDE = new ThreadLocal<>();
    private static final ThreadLocal<Integer> INPUT_SLOT_OVERRIDE = new ThreadLocal<>();
    private static final int CAPABILITIES = 1 | 2 | 4 | 8;
    private static final int RUNTIME_RETRY_TICKS = 20;
    private static final int WORLD_TRANSITION_HISTORY_LIMIT = 24;
    private static final PredictionClient INSTANCE = new PredictionClient();
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("projectkorra.prediction.debug", "false"));
    private static boolean initialized;

    private UUID sessionId;
    private boolean active;
    private boolean readySent;
    private long clientTick;
    private long nextSequence;
    private boolean previousSneaking;
    private long rightClickBlockUntilTick = -1;
    private boolean droppedItem;
    private long lastHelloTick = -1_000;
    private long lastRuntimeStartAttemptTick = Long.MIN_VALUE / 2;
    private int consecutiveRuntimeStartFailures;
    private long serverTimeOffsetMillis;
    private long estimatedOneWayLatencyMillis = 0;
    private long lastAuthorityTick = -1;
    private int maxRewindTicks;
    private double airBlastDecay;
    private boolean chiBlocked;
    private RegionProtectionAuthority.Snapshot regionProtection =
            RegionProtectionAuthority.Snapshot.empty();
    private ServerPose serverPose;
    private boolean serverSneaking;
    private double previousClientEyeHeight = Double.NaN;
    /**
     * Shift flag already applied by Paper, with its entity pose still waiting
     * for the world/player tick that follows Bukkit's scheduler heartbeat.
     */
    private Boolean pendingSneakPose;
    private int serverSelectedSlot = -1;
    private ClientWorld runtimeWorld;
    private ClientPlayerEntity runtimePlayer;
    private boolean previousSpectator;
    private long firstSoftRespawnEffectRepairTick = -1;
    private long finalSoftRespawnEffectRepairTick = -1;
    /** A destination-world TempBlock ledger is still owed by the authority. */
    private boolean worldTempBlockResyncPending;
    /** The owed ledger request is in flight; it is not completion evidence. */
    private boolean worldTempBlockRequestSent;
    private boolean clientWorldBoundaryAwaitingIdentity;
    private String serverWorldIdentity;
    private long serverWorldGeneration = -1L;
    private final List<String> worldTransitionHistory = new ArrayList<>();
    private final Map<String, PredictionPayloads.ConfigEntry> config = new LinkedHashMap<>();
    private final Map<Integer, List<PredictionPayloads.ConfigEntry>> configChunks = new TreeMap<>();
    private UUID chunkSession;
    private long chunkEpoch;
    private int chunkCount;
    private final Map<Integer, String> binds = new LinkedHashMap<>();
    private final Map<String, Long> cooldowns = new LinkedHashMap<>();
    /** Local start times used only to estimate receipt latency. */
    private final Map<Long, Long> actionStartedAtMillis = new LinkedHashMap<>();
    private final List<PendingHitClaim> pendingHitClaims = new ArrayList<>();
    private Packet<?> currentNativeInputPacket;
    private Packet<?> pendingTaggedPacket;
    private PendingActionTag pendingActionTag;
    private List<String> elements = List.of();
    private List<String> subElements = List.of();
    private List<String> permissions = List.of();

    private PredictionClient() { }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        debug("client prediction networking initialized");
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.ServerSnapshot.ID,
                (payload, context) -> INSTANCE.onSnapshot(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.ServerWorldState.ID,
                (payload, context) -> INSTANCE.onServerWorldState(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.NativeAction.ID,
                (payload, context) -> INSTANCE.onNativeAction(payload));
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
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.AbilityStateOwner.ID,
                (payload, context) -> INSTANCE.onAbilityStateOwner(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.TempFallingBlockReceipt.ID,
                (payload, context) -> INSTANCE.onTempFallingBlock(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.TempFallingBlockPrepare.ID,
                (payload, context) -> INSTANCE.onTempFallingBlockPrepare(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.DirectBlockReceipt.ID,
                (payload, context) -> INSTANCE.onDirectBlock(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.AbilityRemoved.ID,
                (payload, context) -> INSTANCE.onAbilityRemoved(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(PredictionPayloads.AbilityTransfer.ID,
                (payload, context) -> INSTANCE.onAbilityTransfer(context.client(), payload));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> INSTANCE.onJoin(sender, client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> INSTANCE.reset(client));
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register(INSTANCE::onClientWorldChange);
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

    /**
     * Commits only movement/look packets after ClientConnection accepted its
     * send method. Native input itself remains on ClientCommonNetworkHandler's
     * render-thread boundary; moving that work into the connection internals
     * caused valid swings to reach Paper without a local prediction action.
     */
    public static void acceptedMovementPacket(MinecraftClient client, Packet<?> packet) {
        if (packet instanceof PlayerMoveC2SPacket movement) recordMovementPacket(client, movement);
    }

    /**
     * Sends the exact local action identity after ClientConnection accepted
     * the outer packet, but immediately before that vanilla input is written.
     */
    public static void prepareAcceptedNativeInputPacket(MinecraftClient client, Packet<?> packet) {
        INSTANCE.prepareAcceptedNativeInputPacket0(packet);
    }

    /** Runs after the outer vanilla input packet has entered the connection. */
    public static void acceptedNativeInputPacket(MinecraftClient client, Packet<?> packet) {
        INSTANCE.acceptedNativeInputPacket0(packet);
    }

    public static void beforeVanillaPacket(MinecraftClient client, Packet<?> packet) {
        PredictionClient owner = INSTANCE;
        if (isNativeAbilityInputPacket(packet)) {
            if (owner.currentNativeInputPacket != null && owner.currentNativeInputPacket != packet) {
                // The preceding outer packet never reached sendImmediately
                // (for example, another networking mixin cancelled it). Its
                // metadata cannot be attached to this later vanilla action.
                owner.pendingHitClaims.clear();
                owner.pendingTaggedPacket = null;
                owner.pendingActionTag = null;
            }
            owner.currentNativeInputPacket = packet;
        }
        if (packet instanceof UpdateSelectedSlotC2SPacket selectedSlot) {
            owner.recordServerVisibleSelectedSlot(client, selectedSlot.getSelectedSlot());
            return;
        }
        if (packet instanceof HandSwingC2SPacket) {
            // PKListener deliberately does not filter PlayerAnimationEvent by
            // hand (the legacy check is commented out), so every vanilla arm
            // animation advances the Paper input order.
            owner.captureLeftClick(client);
            return;
        }
        if (packet instanceof PlayerActionC2SPacket action) {
            if ((action.getAction() == PlayerActionC2SPacket.Action.DROP_ITEM
                    || action.getAction() == PlayerActionC2SPacket.Action.DROP_ALL_ITEMS)
                    && ExactPredictionRuntime.shouldTrackDrop()) {
                owner.droppedItem = true;
            } else if (action.getAction() == PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
                owner.capture(client, PredictionPayloads.InputKind.SWAP_HANDS);
            }
            return;
        }
        if (packet instanceof ClientCommandC2SPacket command) {
            final String mode = command.getMode().name();
            final Boolean sneaking = switch (mode) {
                case "PRESS_SHIFT_KEY", "START_SNEAKING" -> Boolean.TRUE;
                case "RELEASE_SHIFT_KEY", "STOP_SNEAKING" -> Boolean.FALSE;
                default -> null;
            };
            // Retain this as a version-compatible fallback. Minecraft 1.21.11
            // reports the actual shift edge through PlayerInput below.
            if (sneaking != null) owner.captureSneakState(client, sneaking);
            return;
        }
        if (packet instanceof PlayerInputC2SPacket input) {
            owner.captureSneakState(client, input.input().sneak());
            return;
        }
        if (packet instanceof PlayerInteractBlockC2SPacket block) {
            // Legacy PKListener installs the swing-suppression marker for a
            // RIGHT_CLICK_BLOCK event before it checks which hand caused it.
            owner.rightClickBlockUntilTick = owner.clientTick + 2;
            if (block.getHand() == Hand.MAIN_HAND) {
                owner.capture(client, PredictionPayloads.InputKind.RIGHT_CLICK_BLOCK);
            }
            return;
        }
        if (packet instanceof PlayerInteractItemC2SPacket item) {
            if (item.getHand() == Hand.MAIN_HAND && owner.rightClickBlockUntilTick <= owner.clientTick) {
                owner.capture(client, PredictionPayloads.InputKind.RIGHT_CLICK);
            }
            return;
        }
        if (packet instanceof PlayerInteractEntityC2SPacket entity) {
            entity.handle(new PlayerInteractEntityC2SPacket.Handler() {
                @Override
                public void interact(Hand hand) {
                    // PK's legacy listener is PlayerInteractAtEntityEvent, not
                    // the broader PlayerInteractEntityEvent. An INTERACT
                    // packet therefore is not a bending input on Paper.
                }

                @Override
                public void interactAt(Hand hand, Vec3d pos) {
                    if (hand == Hand.MAIN_HAND) {
                        owner.capture(client, PredictionPayloads.InputKind.RIGHT_CLICK_ENTITY);
                    } else {
                        ExactPredictionRuntime.prepareOffHandRightClickEntity();
                    }
                }

                @Override
                public void attack() {
                }
            });
            return;
        }
    }

    static void queueExactHitClaim(final long clientActionSequence,
                                   final long serverActionSequence,
                                   final String ability,
                                   final UUID targetUuid,
                                   final int targetEntityId,
                                   final double contactX,
                                   final double contactY,
                                   final double contactZ) {
        final PredictionClient owner = INSTANCE;
        if (!owner.active || owner.sessionId == null || clientActionSequence <= 0L
                || targetUuid == null || ability == null || ability.isBlank()
                || !finite(contactX, contactY, contactZ)) return;
        final PendingHitClaim claim = new PendingHitClaim(clientActionSequence,
                Math.max(0L, serverActionSequence), owner.clientTick, targetUuid,
                targetEntityId, ability, contactX, contactY, contactZ);
        if (owner.currentNativeInputPacket != null) {
            owner.pendingHitClaims.add(claim);
        } else {
            owner.sendHitClaim(claim);
        }
    }

    private void prepareAcceptedNativeInputPacket0(final Packet<?> packet) {
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

    private void acceptedNativeInputPacket0(final Packet<?> packet) {
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

    private void flushPendingHitClaims() {
        if (pendingHitClaims.isEmpty()) return;
        final int retention = Math.max(1, maxRewindTicks + 2);
        final List<PendingHitClaim> claims = List.copyOf(pendingHitClaims);
        pendingHitClaims.clear();
        for (PendingHitClaim claim : claims) {
            if (clientTick - claim.clientTick() <= retention) sendHitClaim(claim);
        }
    }

    private void sendHitClaim(final PendingHitClaim claim) {
        if (claim == null || !active || sessionId == null
                || !ClientPlayNetworking.canSend(PredictionPayloads.HitClaim.ID)) return;
        ClientPlayNetworking.send(new PredictionPayloads.HitClaim(sessionId,
                claim.clientActionSequence(), claim.serverActionSequence(), claim.clientTick(),
                claim.targetUuid(), claim.targetEntityId(), claim.ability(),
                claim.contactX(), claim.contactY(), claim.contactZ()));
    }

    private static boolean isNativeAbilityInputPacket(final Packet<?> packet) {
        return packet instanceof HandSwingC2SPacket
                || packet instanceof PlayerActionC2SPacket
                || packet instanceof ClientCommandC2SPacket
                || packet instanceof PlayerInputC2SPacket
                || packet instanceof PlayerInteractBlockC2SPacket
                || packet instanceof PlayerInteractItemC2SPacket
                || packet instanceof PlayerInteractEntityC2SPacket;
    }

    private void captureSneakState(MinecraftClient client, boolean sneaking) {
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

    private void queueServerVisibleSneakPose(boolean sneaking) {
        serverSneaking = sneaking;
        pendingSneakPose = sneaking;
        if (DEBUG && active) {
            debug("server-pose sneak queued sneaking=" + sneaking
                    + " eyeY=" + (serverPose == null ? "unknown" : serverPose.eyePos().y));
        }
    }

    private void commitServerVisibleEntityPose(MinecraftClient client) {
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
        ServerPose pose = INSTANCE.serverPose;
        if (pose != null || client == null || client.player == null) return pose;
        ClientPlayerEntity player = client.player;
        return new ServerPose(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(),
                player.getEyeY() - player.getY());
    }

    public static boolean serverVisibleSneaking(MinecraftClient client) {
        final Boolean inputOverride = INPUT_SNEAK_OVERRIDE.get();
        if (inputOverride != null) return inputOverride;
        if (client == null || client.player == null) return INSTANCE.serverSneaking;
        return INSTANCE.serverPose == null ? client.player.isSneaking() : INSTANCE.serverSneaking;
    }

    public static void acceptAuthoritativeSelectedSlot(int slot) {
        if (slot < 0 || slot > 8) return;
        INSTANCE.serverSelectedSlot = slot;
        debug("accepted authoritative selected-slot correction slot=" + (slot + 1));
    }

    static void withInputSneaking(final boolean sneaking, final Runnable action) {
        final Boolean previous = INPUT_SNEAK_OVERRIDE.get();
        INPUT_SNEAK_OVERRIDE.set(sneaking);
        try {
            action.run();
        } finally {
            if (previous == null) INPUT_SNEAK_OVERRIDE.remove();
            else INPUT_SNEAK_OVERRIDE.set(previous);
        }
    }

    static void withInputSelectedSlot(final int selectedSlot, final Runnable action) {
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
        int slot = INSTANCE.serverSelectedSlot;
        if (slot >= 0 && slot < 9) return slot;
        return client != null && client.player != null ? client.player.getInventory().getSelectedSlot() : 0;
    }

    public static Map<String, PredictionPayloads.ConfigEntry> publicConfig() { return Map.copyOf(INSTANCE.config); }

    static String diagnosticStatus() {
        final MinecraftClient client = MinecraftClient.getInstance();
        final String failure = ExactPredictionRuntime.lastStartFailure();
        return "session=" + (INSTANCE.sessionId != null)
                + " active=" + INSTANCE.active
                + " runtime=" + ExactPredictionRuntime.isReady()
                + " ready=" + INSTANCE.readySent
                + " abilities=" + ExactPredictionRuntime.supportedAbilities().size()
                + " binds=" + INSTANCE.binds.size()
                + " retries=" + INSTANCE.consecutiveRuntimeStartFailures
                + " world=" + worldRef(client == null ? null : client.world)
                + " player=" + playerRef(client == null ? null : client.player)
                + " runtimeWorld=" + worldRef(INSTANCE.runtimeWorld)
                + " runtimePlayer=" + playerRef(INSTANCE.runtimePlayer)
                + " serverWorld=" + (INSTANCE.serverWorldIdentity == null ? "unknown" : INSTANCE.serverWorldIdentity)
                + " generation=" + INSTANCE.serverWorldGeneration
                + " ledgerPending=" + INSTANCE.worldTempBlockResyncPending
                + " ledgerRequestSent=" + INSTANCE.worldTempBlockRequestSent
                + " boundaryAwaiting=" + INSTANCE.clientWorldBoundaryAwaitingIdentity
                + (failure == null || failure.isBlank() ? "" : " failure=" + failure);
    }

    static List<String> worldTransitionReport() {
        final List<String> report = new ArrayList<>();
        report.add("World transition state: " + diagnosticStatus());
        if (INSTANCE.worldTransitionHistory.isEmpty()) {
            report.add("World transition history: no boundary recorded this session");
        } else {
            report.add("World transitions (oldest to newest):");
            report.addAll(INSTANCE.worldTransitionHistory);
        }
        return List.copyOf(report);
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
        regionProtection = snapshot.regionProtection();
        startRuntime(client, "snapshot");
        sendReady();
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
        permissions = state.permissions();
        airBlastDecay = state.airBlastDecay();
        chiBlocked = state.chiBlocked();
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
                permissions, airBlastDecay, chiBlocked, regionProtection);
        ExactPredictionRuntime.reconcileActiveFlightAbilities(state.activeFlightAbilities(), state.acknowledgedSequence());
        debug("player state applied binds=" + binds + " cooldowns=" + authoritativeCooldowns.keySet()
                + " elements=" + elements + " subElements=" + subElements);
    }

    private void onStateDirective(PredictionPayloads.StateDirective directive) {
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

    private void onReconcile(PredictionPayloads.Reconcile reconcile) {
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

    private void onNativeAction(PredictionPayloads.NativeAction action) {
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

    private void onServerWorldState(final MinecraftClient client,
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
    private boolean acceptServerWorldState(final MinecraftClient client, final UUID incomingSession,
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
                this.clientWorldBoundaryAwaitingIdentity = false;
            }
            return true;
        }

        final long previousGeneration = this.serverWorldGeneration;
        final String previousIdentity = this.serverWorldIdentity;
        this.serverWorldGeneration = incomingGeneration;
        this.serverWorldIdentity = incomingIdentity;
        final boolean changed = previousGeneration >= 0L;
        final boolean clientBoundaryAlreadyRestarted = this.clientWorldBoundaryAwaitingIdentity;
        this.clientWorldBoundaryAwaitingIdentity = false;
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

    private void sendReady() {
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
     * would otherwise be installed into the new ClientWorld and then erased by
     * the delayed runtime restart.
     */
    private void onClientWorldChange(final MinecraftClient client, final ClientWorld world) {
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
    private void requestWorldTempBlockSnapshot() {
        if (!worldTempBlockResyncPending || worldTempBlockRequestSent || !active || sessionId == null
                || !ClientPlayNetworking.canSend(PredictionPayloads.ClientReady.ID)) return;
        ClientPlayNetworking.send(new PredictionPayloads.ClientReady(
                sessionId, ExactPredictionRuntime.supportedAbilities()));
        worldTempBlockRequestSent = true;
        debug("requested destination-world TempBlock ledger world=" + worldKey(runtimeWorld));
        recordWorldTransition("requested destination TempBlock ledger");
    }

    private void onTempBlocks(MinecraftClient client, PredictionPayloads.TempBlockBatch batch) {
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
            ExactPredictionRuntime.applyTempBlockBatch(client.world, batch);
            if (batch.snapshot()) {
                worldTempBlockResyncPending = false;
                worldTempBlockRequestSent = false;
                if (completingDestinationLedger) {
                    recordWorldTransition("applied destination TempBlock snapshot generation="
                            + batch.worldGeneration() + " ops=" + batch.operations().size());
                }
            }
        }
    }

    private void onVelocityOwner(MinecraftClient client, PredictionPayloads.VelocityOwner owner) {
        if (client.player != null) ExactPredictionRuntime.noteVelocityOwner(client.player, owner);
    }

    private void onVelocityOwner(MinecraftClient client, PredictionPayloads.VelocityOwnerV2 owner) {
        if (client.player != null) ExactPredictionRuntime.noteVelocityOwner(client.player, owner);
    }

    private void onAbilityStateOwner(MinecraftClient client, PredictionPayloads.AbilityStateOwner owner) {
        if (client.player != null) ExactPredictionRuntime.noteAbilityStateOwner(client.player, owner);
    }

    private void onTempFallingBlock(MinecraftClient client,
                                    PredictionPayloads.TempFallingBlockReceipt receipt) {
        if (client.player != null) ExactPredictionRuntime.noteTempFallingBlock(client.player, receipt);
    }

    private void onTempFallingBlockPrepare(MinecraftClient client,
                                           PredictionPayloads.TempFallingBlockPrepare prepare) {
        if (client.player != null) ExactPredictionRuntime.noteTempFallingBlockPrepare(client.player, prepare);
    }

    private void onDirectBlock(MinecraftClient client,
                               PredictionPayloads.DirectBlockReceipt receipt) {
        if (client.player != null) ExactPredictionRuntime.noteDirectBlock(client.player, receipt);
    }

    private void onAbilityRemoved(MinecraftClient client, PredictionPayloads.AbilityRemoved removed) {
        if (removed.predictionRejected()) cooldowns.remove(removed.ability());
        if (client.player != null) ExactPredictionRuntime.removeAuthoritativeAbility(client.player, removed);
    }

    private void onAbilityTransfer(MinecraftClient client, PredictionPayloads.AbilityTransfer transfer) {
        if (client.player != null) ExactPredictionRuntime.transferAuthoritativeAbility(client.player, transfer);
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
                rebuildStatusEffectAttributes(client.player);
                firstSoftRespawnEffectRepairTick = -1;
            }
            if (clientTick == finalSoftRespawnEffectRepairTick) {
                rebuildStatusEffectAttributes(client.player);
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

    private void captureLeftClick(MinecraftClient client) {
        // Fabric/Paper expires a marker whose deadline equals the current
        // tick before processing that tick's input. Treat the deadline as an
        // exclusive bound here as well; keeping it inclusive drops a valid
        // swing locally while Paper accepts and runs it (notably AirBlast).
        final boolean suppress = droppedItem || rightClickBlockUntilTick > clientTick;
        droppedItem = false;
        capture(client, PredictionPayloads.InputKind.LEFT_CLICK, suppress);
    }

    private void capture(MinecraftClient client, PredictionPayloads.InputKind kind) {
        capture(client, kind, false);
    }

    private void capture(MinecraftClient client, PredictionPayloads.InputKind kind, boolean suppressInput) {
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
        if (suppressInput) {
            // Preserve the semantic action without executing it. Paper's
            // post-input receipt decides whether its legacy suppression gate
            // agreed. If Paper accepted the swing, reconciliation replays this
            // exact packet-time input through the common runtime client-side.
            ExactPredictionRuntime.recordNativeOnlyInput(sequence, kind, selectedSlot, pose, ability);
            actionStartedAtMillis.put(sequence, System.currentTimeMillis());
            actionStartedAtMillis.entrySet().removeIf(entry -> nextSequence - entry.getKey() > 128);
            debug("capture native-only sequence=" + sequence + " kind=" + kind + " ability=" + ability
                    + " slot=" + (selectedSlot + 1) + " localSlot=" + (localSlot + 1)
                    + " reason=legacy-swing-suppression");
            return;
        }
        // Execute first in the same client frame. Networking is independent of
        // the local simulation and never gates its particles or movement.
        final boolean cooldownActiveAtInput = ExactPredictionRuntime.isInputCooldownActive(ability, kind);
        boolean locallyPredicted = ExactPredictionRuntime.shouldPredictInput(ability, kind)
                && ExactPredictionRuntime.input(sequence, kind, selectedSlot, pose);
        // A vanilla input carries no client timestamp. Without this narrow
        // negative gate, an input rejected locally at t=0 can arrive after a
        // short cooldown expires and be replayed by Paper at t=RTT/2. Send the
        // veto from this sendPacket hook, before the outer vanilla packet, so
        // both travel in the same ordered connection. It never authorizes a
        // cast: absence of a veto simply leaves normal server validation intact.
        final boolean cooldownVeto = cooldownActiveAtInput && !locallyPredicted;
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

    static ServerPose poseForInput(ServerPose serverVisible, ServerPose latestLocal) {
        return serverVisible != null ? serverVisible : latestLocal;
    }

    private void recordServerVisibleSelectedSlot(MinecraftClient client, int slot) {
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

    private void reset(MinecraftClient client) {
        debug("reset active=" + active + " session=" + sessionId);
        ExactPredictionRuntime.stop(client);
        active = false; sessionId = null; nextSequence = 0;
        readySent = false;
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

    private void restartForWorldChange(MinecraftClient client) {
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

    private boolean startRuntime(final MinecraftClient client, final String reason) {
        lastRuntimeStartAttemptTick = clientTick;
        if (client == null || client.world == null || client.player == null
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
                elements, subElements, permissions, airBlastDecay, chiBlocked, regionProtection);
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

    private static String worldRef(final ClientWorld world) {
        return world == null ? "null" : worldKey(world) + "@"
                + Integer.toHexString(System.identityHashCode(world));
    }

    private static String playerRef(final ClientPlayerEntity player) {
        if (player == null) return "null";
        return player.getUuid() + "@" + Integer.toHexString(System.identityHashCode(player))
                + "/" + player.getEntityWorld().getRegistryKey().getValue();
    }

    private void recordWorldTransition(final String event) {
        final MinecraftClient client = MinecraftClient.getInstance();
        final String line = "tick=" + clientTick + " event=" + event
                + " active=" + active + " runtimeReady=" + ExactPredictionRuntime.isReady()
                + " client=" + worldRef(client == null ? null : client.world)
                + " player=" + playerRef(client == null ? null : client.player)
                + " runtime=" + worldRef(runtimeWorld)
                + " generation=" + serverWorldGeneration
                + " pending=" + worldTempBlockResyncPending
                + " requestSent=" + worldTempBlockRequestSent
                + " awaiting=" + clientWorldBoundaryAwaitingIdentity;
        worldTransitionHistory.add(line);
        while (worldTransitionHistory.size() > WORLD_TRANSITION_HISTORY_LIMIT) {
            worldTransitionHistory.remove(0);
        }
        debug(line);
    }

    private void updateServerClock(long serverNowMillis) {
        if (serverNowMillis > 0) serverTimeOffsetMillis = System.currentTimeMillis() - serverNowMillis;
    }

    private void updateLatencyEstimate(long sequence) {
        Long startedAt = actionStartedAtMillis.remove(sequence);
        if (startedAt == null) return;
        long rtt = Math.max(0L, System.currentTimeMillis() - startedAt);
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

    private void rememberAuthoritativeCooldowns(Map<String, Long> authoritative) {
        cooldowns.clear();
        if (authoritative != null) cooldowns.putAll(authoritative);
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

    private static boolean finite(final double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static void debug(String message) {
        if (DEBUG) System.out.println("[ProjectKorraPrediction] " + message);
    }

    private record PendingActionTag(long clientActionSequence, PredictionPayloads.InputKind kind,
                                    int selectedSlot, String ability) {
    }

    private record PendingHitClaim(long clientActionSequence, long serverActionSequence,
                                   long clientTick, UUID targetUuid, int targetEntityId,
                                   String ability, double contactX, double contactY,
                                   double contactZ) {
    }

    public record ServerPose(double x, double y, double z, float yaw, float pitch, double eyeHeight) {
        public Vec3d eyePos() {
            return new Vec3d(x, y + eyeHeight, z);
        }
    }

}
