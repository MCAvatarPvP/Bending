package com.projectkorra.projectkorra.prediction.server.impl;

import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol;
import com.projectkorra.projectkorra.prediction.snapshot.PaperPredictionSnapshot;
import com.projectkorra.projectkorra.prediction.snapshot.PaperRegionProtectionSnapshot;

import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.action.NativeActionTagStream;
import com.projectkorra.projectkorra.prediction.action.PredictionActionSeed;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.authority.PredictionVisibility;
import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority;
import com.projectkorra.projectkorra.prediction.block.DirectBlockSync;
import com.projectkorra.projectkorra.prediction.block.TempBlockDeliveryTracker;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;
import com.projectkorra.projectkorra.prediction.block.TempFallingBlockSync;
import com.projectkorra.projectkorra.prediction.hit.ConfirmedHitEffects;
import com.projectkorra.projectkorra.prediction.hit.HitRewind;
import com.projectkorra.projectkorra.prediction.hit.HitRegistrationPolicy;
import com.projectkorra.projectkorra.prediction.movement.VelocitySync;
import com.projectkorra.projectkorra.prediction.state.AbilityCheckpointSync;
import com.projectkorra.projectkorra.prediction.state.AbilityStateSync;
import com.projectkorra.projectkorra.prediction.state.GlidingStateSync;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import com.projectkorra.projectkorra.prediction.state.PlayerStatusSync;

import com.jedk1.jedcore.ability.passive.WallRun;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.firebending.FireBlastCharged;
import com.projectkorra.projectkorra.airbending.AirBurst;
import com.projectkorra.projectkorra.airbending.AirGlider;
import com.projectkorra.projectkorra.earthbending.EarthSmash;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;

public abstract class PaperPredictionState implements PluginMessageListener, Runnable, TempBlockSync.Listener,
        TempFallingBlockSync.Listener, CooldownSync.Listener, VelocitySync.Listener,
        AbilityRemovalSync.Listener, DirectBlockSync.Listener,
        AbilityStateSync.Listener, GlidingStateSync.Listener,
        AbilityCheckpointSync.Listener, PlayerStatusSync.Listener {
    protected static final int CAPABILITY_EXACT = 8;
    protected static final int MAX_REWIND_TICKS = PaperPredictionServer.MAX_REWIND_TICKS;
    protected static final int MAX_TEMP_BLOCK_OPS_PER_PACKET = 256;
    protected static final int TEMP_BLOCK_PACKET_HEADROOM_BYTES = 1_024;
    protected static final long TEMP_BLOCK_SNAPSHOT_SAFETY_TICKS = 1_200L;
    protected static final int MAX_PREDICTION_PERMISSIONS = 512;
    protected static final int CLAIMS_PER_SECOND = 48;
    protected static final double CLAIM_CONTACT_TOLERANCE = 0.75;
    protected static final double CLAIM_QUERY_TOLERANCE = 1.0;
    protected static final double MAX_CLAIM_DISTANCE_SQUARED = 160.0 * 160.0;
    protected static final ThreadLocal<UUID> EFFECT_OWNER = new ThreadLocal<>();
    protected static final ThreadLocal<Boolean> EFFECT_PREDICTED = new ThreadLocal<>();
    protected static final ThreadLocal<Long> INPUT_SEQUENCE = new ThreadLocal<>();

    protected final JavaPlugin plugin;
    protected final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    protected final Map<UUID, Deque<EntityFrame>> playerHistory = new HashMap<>();
    protected final Map<CoreAbility, Action> abilityActions = Collections.synchronizedMap(new IdentityHashMap<>());
    protected final Map<CoreAbility, Action> abilityCreationActions = Collections.synchronizedMap(new IdentityHashMap<>());
    protected final Set<CoreAbility> predictedOwnershipTransfers = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));
    protected final Map<Long, Action> tempLayerActions = new HashMap<>();
    protected final Map<Long, TempEffectIdentity> tempLayerEffects = new HashMap<>();
    protected final Set<Long> serverOwnedTempLayers = new HashSet<>();
    protected final List<PendingTempBlock> pendingTempBlocks = new ArrayList<>();
    protected final List<PendingAbilityRemoval> pendingAbilityRemovals = new ArrayList<>();
    protected final Map<UUID, Integer> uncorrelatedExternalVelocityOrdinals = new HashMap<>();
    protected final AtomicBoolean snapshotBuildRunning = new AtomicBoolean();
    protected volatile List<PaperPredictionProtocol.ConfigEntry> publicConfig = List.of();
    protected volatile List<PaperPredictionProtocol.AbilityProfile> profiles = List.of();
    protected List<String> permissionCandidates = List.of();
    protected long permissionCandidateGeneration;
    protected volatile long configEpoch;
    protected volatile boolean snapshotReady;
    protected long tick;
    protected BukkitTask task;

    protected PaperPredictionState(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    protected abstract void sendState(Player player, Session session, boolean force);
    protected abstract Action actionForEffect(CoreAbility ability);
    protected abstract Action currentInputAction(UUID ownerId);
    protected abstract void send(Player player, String channel, byte[] payload);
    protected abstract void onHello(Player player, PaperPredictionProtocol.Hello hello);
    protected abstract void onClientDisabled(Player player, PaperPredictionProtocol.ClientDisabled disabled);
    protected abstract void onReady(Player player, PaperPredictionProtocol.Ready ready);
    protected abstract void onInputVeto(Player player, PaperPredictionProtocol.InputVeto veto);
    protected abstract void onActionTag(Player player, PaperPredictionProtocol.ActionTag tag);
    protected abstract void onHitClaim(Player player, PaperPredictionProtocol.HitClaim hit);
    protected abstract void recordPlayerHistory();
    protected abstract void flushTempBlocks();
    protected abstract void flushAbilityRemovals();
    protected abstract void syncState();
    protected abstract void sendWorldState(Player player, Session session);
    protected abstract boolean shouldSendPeriodicTempBlockSnapshot(Player player, Session session);
    protected abstract void sendTempBlockSnapshot(Player player, Session session);
    protected abstract void sendAirGliderState(Player player, AirGlider glider, Action action);
    protected abstract void rebuildPermissionCandidates();
    protected abstract void requestSnapshotRebuild(boolean broadcastChanges);
    protected abstract CommonInputHandler.InputResult processInput(
            Player player, Session session, PaperPredictionProtocol.InputKind kind,
            Supplier<CommonInputHandler.InputResult> nativeInput);
    protected abstract void sendSnapshot(Session session);
    protected abstract Session valid(Player player, UUID session);
    protected abstract void reconcile(
            Player player, Session session, long sequence, boolean accepted, String reason,
            String ability, Location origin, long cooldown, boolean inputHandled,
            boolean comboRecorded, List<String> createdAbilities);
    protected abstract WorldScope refreshWorldScope(Player player, Session session);
    protected abstract List<String> predictionPermissions(Player player, Session session);

    protected record OutboundPayload(String channel, byte[] payload) {
    }

    protected record PendingTempBlock(String worldIdentity, PaperPredictionProtocol.TempBlockOp operation,
                                    Map<UUID, BlockData> ownerViews) {
        public PaperPredictionProtocol.TempBlockOp forViewer(final UUID viewer) {
            final BlockData viewerData = ownerViews.get(viewer);
            if (viewerData == null) return operation;
            return new PaperPredictionProtocol.TempBlockOp(operation.operation(), operation.world(),
                    operation.x(), operation.y(), operation.z(), operation.material(), operation.revertAtMillis(),
                    operation.actionSequence(), operation.effectAbility(), operation.effectState(), operation.effectStep(),
                    operation.effectOrdinal(), operation.layerId(), operation.revision(), operation.ownerId(),
                    TempBlockSync.encode(viewerData),
                    operation.packetExpected());
        }
    }

    protected record PendingAbilityRemoval(UUID playerId, String ability, String abilityType, long actionSequence,
                                         boolean externallyCaused,
                                         boolean predictionRejected,
                                         CoreAbility instance) {
    }

    protected record PermissionContext(long candidateGeneration, boolean operator,
                                     Set<String> assignments) {
    }

    protected static final class Session {
        public final UUID player, session;
        public final int capabilities;
        public final long helloClientTick, helloServerTick;
        public final LinkedHashMap<Long, Action> actions = new LinkedHashMap<>();
        public final ArrayDeque<PaperPredictionProtocol.InputVeto> inputVetoes = new ArrayDeque<>();
        public final NativeActionTagStream actionTags = new NativeActionTagStream();
        public final RateLimiter claimLimiter = new RateLimiter();
        public final LinkedHashMap<DirectBlockCause, Integer> directBlockOrdinals = new LinkedHashMap<>();
        public final Set<String> predictedCooldowns = new HashSet<>();
        public final TempBlockDeliveryTracker tempLayers = new TempBlockDeliveryTracker();
        public Set<String> supportedAbilities = Set.of();
        public long lastSequence;
        long worldGeneration;
        long tempBlockStreamSequence;
        long tempBlockSnapshotSequence;
        long lastTempBlockSnapshotTick;
        String worldIdentity = "";
        int lastTempBlockSnapshotChunkX;
        int lastTempBlockSnapshotChunkZ;
        int lastTempBlockSnapshotViewDistance;
        int stateDigest;
        long regionProtectionSpatialKey = Long.MIN_VALUE;
        long nextRegionProtectionRefreshTick;
        RegionProtectionAuthority.Snapshot regionProtectionSpatial =
                RegionProtectionAuthority.Snapshot.empty();
        public boolean ready;
        boolean tempBlockSnapshotInitialized;
        PermissionContext permissionContext;
        List<String> predictionPermissions = List.of();

        Session(UUID player, UUID session, int capabilities,
                long helloClientTick, long helloServerTick) {
            this.player = player;
            this.session = session;
            this.capabilities = capabilities;
            this.helloClientTick = helloClientTick;
            this.helloServerTick = helloServerTick;
        }

        long mapClientTick(final long clientTick, final long currentServerTick,
                           final int attackerPing, final int defenderPing) {
            return HitRewind.mapClientTick(helloClientTick, helloServerTick, clientTick,
                    currentServerTick, attackerPing, defenderPing, PaperPredictionServer.MAX_REWIND_TICKS);
        }
    }

    protected record WorldScope(long generation, String identity) {
    }

    protected static final class Action {
        public final UUID owner;
        public final long sequence, acceptedTick;
        public final PaperPredictionProtocol.InputKind kind;
        public final int selectedSlot;
        public final String ability;
        public final double eyeX, eyeY, eyeZ;
        public final float yaw, pitch;
        public final long deterministicSeed;
        public final Map<UUID, Integer> velocityOrdinals = new HashMap<>();
        public final Map<UUID, Integer> abilityStateOrdinals = new HashMap<>();
        public final Map<UUID, Integer> glidingStateOrdinals = new HashMap<>();
        public final Map<String, Integer> directBlockOrdinals = new HashMap<>();
        public final Map<UUID, Claim> claims = new HashMap<>();
        public long clientSequence;
        public int tempFallingBlockOrdinal;
        public int tempBlockOrdinal;
        public boolean locallyPredicted;

        Action(UUID owner, long sequence, long acceptedTick,
               PaperPredictionProtocol.InputKind kind, int selectedSlot, String ability,
               double eyeX, double eyeY, double eyeZ, float yaw, float pitch,
               long deterministicSeed, boolean locallyPredicted) {
            this.owner = owner;
            this.sequence = sequence;
            this.acceptedTick = acceptedTick;
            this.kind = kind;
            this.selectedSlot = selectedSlot;
            this.ability = ability;
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            this.yaw = yaw;
            this.pitch = pitch;
            this.deterministicSeed = deterministicSeed;
            this.locallyPredicted = locallyPredicted;
        }
    }

    protected static final class Claim {
        public final UUID target;
        public final long rewindTick, expiresTick;
        public final Vector contact;
        public final org.bukkit.util.BoundingBox rewoundBox;

        Claim(UUID target, long rewindTick, long expiresTick, Vector contact,
              org.bukkit.util.BoundingBox rewoundBox) {
            this.target = target;
            this.rewindTick = rewindTick;
            this.expiresTick = expiresTick;
            this.contact = contact;
            this.rewoundBox = rewoundBox;
        }
    }

    protected record EntityFrame(long serverTick, UUID world,
                               org.bukkit.util.BoundingBox box) {
    }

    protected static final class RateLimiter {
        long windowStart;
        int count;

        boolean allow(final long currentTick, final int maximum) {
            if (currentTick - windowStart >= 20L) {
                windowStart = currentTick;
                count = 0;
            }
            return ++count <= maximum;
        }
    }

    protected record TempEffectIdentity(String ability, long step, int ordinal) {
    }

    protected record DirectBlockCause(long sequence, String ability) {
    }
}
