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

public abstract class PredictionClientState {
    protected static final ThreadLocal<Boolean> INPUT_SNEAK_OVERRIDE = new ThreadLocal<>();
    protected static final ThreadLocal<Integer> INPUT_SLOT_OVERRIDE = new ThreadLocal<>();
    protected static final int CAPABILITIES = 1 | 2 | 4 | 8;
    protected static final int RUNTIME_RETRY_TICKS = 20;
    protected static final int WORLD_TRANSITION_HISTORY_LIMIT = 24;
    protected static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("projectkorra.prediction.debug", "false"));
    protected static boolean initialized;

    protected UUID sessionId;
    protected boolean active;
    protected boolean readySent;
    protected boolean disableHandshakePending;
    protected long clientTick;
    protected long nextSequence;
    protected boolean previousSneaking;
    protected long rightClickBlockUntilTick = -1;
    protected boolean droppedItem;
    protected long lastHelloTick = -1_000;
    protected long lastRuntimeStartAttemptTick = Long.MIN_VALUE / 2;
    protected int consecutiveRuntimeStartFailures;
    protected long serverTimeOffsetMillis;
    protected long estimatedOneWayLatencyMillis = 0;
    protected long lastAuthorityTick = -1;
    protected int maxRewindTicks;
    protected double airBlastDecay;
    protected boolean chiBlocked;
    protected PredictionPayloads.PlayerCosmetics cosmetics = PredictionPayloads.PlayerCosmetics.empty();
    protected RegionProtectionAuthority.Snapshot regionProtection =
            RegionProtectionAuthority.Snapshot.empty();
    protected ServerPose serverPose;
    protected boolean serverSneaking;
    protected double previousClientEyeHeight = Double.NaN;
    /**
     * Shift flag already applied by Paper, with its entity pose still waiting
     * for the world/player tick that follows Bukkit's scheduler heartbeat.
     */
    protected Boolean pendingSneakPose;
    protected int serverSelectedSlot = -1;
    protected ClientWorld runtimeWorld;
    protected ClientPlayerEntity runtimePlayer;
    protected boolean previousSpectator;
    protected long firstSoftRespawnEffectRepairTick = -1;
    protected long finalSoftRespawnEffectRepairTick = -1;
    /** A destination-world TempBlock ledger is still owed by the authority. */
    protected boolean worldTempBlockResyncPending;
    /** The owed ledger request is in flight; it is not completion evidence. */
    protected boolean worldTempBlockRequestSent;
    protected boolean clientWorldBoundaryAwaitingIdentity;
    protected String serverWorldIdentity;
    protected long serverWorldGeneration = -1L;
    protected final List<String> worldTransitionHistory = new ArrayList<>();
    protected final Map<String, PredictionPayloads.ConfigEntry> config = new LinkedHashMap<>();
    protected final Map<Integer, List<PredictionPayloads.ConfigEntry>> configChunks = new TreeMap<>();
    protected UUID chunkSession;
    protected long chunkEpoch;
    protected int chunkCount;
    protected final Map<Integer, String> binds = new LinkedHashMap<>();
    protected final Map<String, Long> cooldowns = new LinkedHashMap<>();
    /** Local start times used only to estimate receipt latency. */
    protected final Map<Long, Long> actionStartedAtMillis = new LinkedHashMap<>();
    protected final List<PendingHitClaim> pendingHitClaims = new ArrayList<>();
    protected Packet<?> currentNativeInputPacket;
    protected Packet<?> pendingTaggedPacket;
    protected PendingActionTag pendingActionTag;
    protected List<String> elements = List.of();
    protected List<String> subElements = List.of();
    protected List<String> permissions = List.of();

    protected PredictionClientState() { }


    protected static String worldKey(ClientWorld world) {
        return world == null ? "null" : world.getRegistryKey().getValue().toString();
    }

    protected static String worldRef(final ClientWorld world) {
        return world == null ? "null" : worldKey(world) + "@"
                + Integer.toHexString(System.identityHashCode(world));
    }

    protected static String playerRef(final ClientPlayerEntity player) {
        if (player == null) return "null";
        return player.getUuid() + "@" + Integer.toHexString(System.identityHashCode(player))
                + "/" + player.getEntityWorld().getRegistryKey().getValue();
    }

    protected void recordWorldTransition(final String event) {
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

    protected void updateServerClock(long serverNowMillis) {
        if (serverNowMillis > 0) serverTimeOffsetMillis = System.currentTimeMillis() - serverNowMillis;
    }

    protected void updateLatencyEstimate(long sequence) {
        Long startedAt = actionStartedAtMillis.remove(sequence);
        if (startedAt == null) return;
        long rtt = Math.max(0L, System.currentTimeMillis() - startedAt);
        long sample = Math.min(750L, rtt / 2L);
        estimatedOneWayLatencyMillis = estimatedOneWayLatencyMillis <= 0
                ? sample : (estimatedOneWayLatencyMillis * 3L + sample) / 4L;
    }

    protected Map<String, Long> convertCooldowns(Map<String, Long> serverCooldowns) {
        Map<String, Long> converted = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        serverCooldowns.forEach((ability, until) -> {
            long clientUntil = convertCooldown(until);
            if (clientUntil > now) converted.put(ability, clientUntil);
        });
        return converted;
    }

    protected void rememberAuthoritativeCooldowns(Map<String, Long> authoritative) {
        cooldowns.clear();
        if (authoritative != null) cooldowns.putAll(authoritative);
    }

    protected long convertCooldown(long serverCooldownUntil) {
        if (serverCooldownUntil <= 0) return 0;
        // serverTimeOffsetMillis is measured when the packet arrives and thus
        // includes its estimated one-way transit time. Remove that transport
        // component once when translating the absolute server expiry. The old
        // activation path removed it a second time via a prediction lead,
        // which made the client usable before the server cooldown had ended.
        return serverCooldownUntil + serverTimeOffsetMillis - estimatedOneWayLatencyMillis;
    }

    protected static boolean finite(final double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    protected static void debug(String message) {
        if (DEBUG) System.out.println("[ProjectKorraPrediction] " + message);
    }

    protected record PendingActionTag(long clientActionSequence, PredictionPayloads.InputKind kind,
                                    int selectedSlot, String ability) {
    }

    protected record PendingHitClaim(long clientActionSequence, long serverActionSequence,
                                   long clientTick, UUID targetUuid, int targetEntityId,
                                   String ability, double contactX, double contactY,
                                   double contactZ) {
    }

    public record ServerPose(double x, double y, double z, float yaw, float pitch, double eyeHeight) {
        public Vec3d eyePos() {
            return new Vec3d(x, y + eyeHeight, z);
        }
    }

    protected abstract void capture(MinecraftClient client, PredictionPayloads.InputKind kind);
    protected abstract void reset(MinecraftClient client);
    protected abstract boolean startRuntime(MinecraftClient client, String reason);
    protected abstract void sendReady();
    protected abstract void restartForWorldChange(MinecraftClient client);
    protected abstract void requestWorldTempBlockSnapshot();
}
