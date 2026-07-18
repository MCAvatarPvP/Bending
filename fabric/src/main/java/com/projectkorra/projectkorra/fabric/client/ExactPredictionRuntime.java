package com.projectkorra.projectkorra.fabric.client;

import com.projectkorra.projectkorra.BendingManager;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.CollisionInitializer;
import com.projectkorra.projectkorra.ability.util.CollisionManager;
import com.projectkorra.projectkorra.ability.util.EmbeddedAddonBootstrap;
import com.projectkorra.projectkorra.airbending.AirBlast;
import com.projectkorra.projectkorra.chiblocking.util.ChiblockingManager;
import com.projectkorra.projectkorra.earthbending.EarthTunnel;
import com.projectkorra.projectkorra.earthbending.util.EarthbendingManager;
import com.projectkorra.projectkorra.firebending.FireBlastCharged;
import com.projectkorra.projectkorra.firebending.util.FirebendingManager;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.fabric.prediction.PredictionPayloads;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.listener.CommonPlayerListenerCore;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.fabric.FabricClientPredictionPlatform;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.fabric.FabricPredictionMC;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Fire;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Snow;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.AirBlastTraceSync;
import com.projectkorra.projectkorra.prediction.CooldownSync;
import com.projectkorra.projectkorra.prediction.DirectBlockSync;
import com.projectkorra.projectkorra.prediction.ClientTempBlockLedger;
import com.projectkorra.projectkorra.prediction.ExternalVelocityFence;
import com.projectkorra.projectkorra.prediction.PredictionConfigSync;
import com.projectkorra.projectkorra.prediction.PredictionActionSeed;
import com.projectkorra.projectkorra.prediction.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.PredictionStateOrdering;
import com.projectkorra.projectkorra.prediction.PredictedContactSync;
import com.projectkorra.projectkorra.prediction.RegionProtectionAuthority;
import com.projectkorra.projectkorra.prediction.TempBlockSync;
import com.projectkorra.projectkorra.prediction.TempBlockTeardownFence;
import com.projectkorra.projectkorra.prediction.TempBlockTeardownPolicy;
import com.projectkorra.projectkorra.prediction.TempFallingBlockSync;
import com.projectkorra.projectkorra.prediction.VelocityReceiptPolicy;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.Cooldown;
import com.projectkorra.projectkorra.util.CooldownDisplayHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import com.projectkorra.projectkorra.util.FallHandler;
import com.projectkorra.projectkorra.util.FlightHandler;
import com.projectkorra.projectkorra.util.Information;
import com.projectkorra.projectkorra.util.RegenHandler;
import com.projectkorra.projectkorra.util.RevertChecker;
import com.projectkorra.projectkorra.waterbending.blood.Bloodbending;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;
import com.projectkorra.projectkorra.waterbending.util.WaterbendingManager;
import com.jedk1.jedcore.ability.passive.WallRun;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Runs the real common ProjectKorra ability implementation in the logical
 * client. This is deliberately not a visual approximation: constructors,
 * progress methods, particles, sounds, ray traces, temp blocks and velocity
 * calls all pass through the same common classes used by the server.
 */
public final class ExactPredictionRuntime implements CooldownSync.Listener,
        TempBlockSync.Listener, TempFallingBlockSync.Listener {
    private static final ExactPredictionRuntime INSTANCE = new ExactPredictionRuntime();
    private static final ThreadLocal<Long> INPUT_ACTION = new ThreadLocal<>();
    /** Captured Paper event pose; deliberately narrower than effect ownership. */
    private static final ThreadLocal<Long> INPUT_EVENT_POSE = new ThreadLocal<>();
    private static final int ACTION_RETENTION_TICKS = 160;
    private static final int AUTHORITATIVE_TEMP_BLOCK_HISTORY_LIMIT = 24;
    private static final int BLOCK_CONFIRMATION_TICKS = 40;
    private static final int MIN_ACTION_BLOCK_CONFIRMATION_TICKS = 4;
    private static final int ACTION_BLOCK_CONFIRMATION_MARGIN_TICKS = 2;
    private static final int VELOCITY_RECEIPT_TICKS = 40;
    private static final int EARTH_CAUSE_RETENTION_TICKS = BLOCK_CONFIRMATION_TICKS;
    private static final Set<String> PERSISTENT_FLIGHT_ABILITIES = Set.of(
            "airscooter", "airspout", "waterspout", "firejet", "flight");
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("projectkorra.prediction.debug", "false"));

    private final Map<Long, Action> actions = new LinkedHashMap<>();
    private final Map<CoreAbility, Long> abilityActions = new IdentityHashMap<>();
    private final Map<CoreAbility, Long> abilityCreationActions = new IdentityHashMap<>();
    private final List<String> abilityRemovalHistory = new ArrayList<>();
    private final List<String> tempBlockTeardownHistory = new ArrayList<>();
    private final List<String> authoritativeTempBlockHistory = new ArrayList<>();
    private final Map<AirBlastTraceKey, AirBlastTraceSync.Trace> localAirBlastTraces = new LinkedHashMap<>();
    private final Map<AirBlastTraceKey, AirBlastTraceSync.Trace> paperAirBlastTraces = new LinkedHashMap<>();
    /** Paper native action ordinal -> the same accepted Fabric native action. */
    private final Map<Long, Long> nativeActionAliases = new LinkedHashMap<>();
    /** Paper native ordinal -> semantically identical local native ordinal. */
    private final Map<Long, Long> airBlastSequenceAliases = new LinkedHashMap<>();
    private final Map<Long, String> airBlastNativeRejections = new LinkedHashMap<>();
    private final Map<Long, String> airBlastInputDifferences = new LinkedHashMap<>();
    private final Map<Long, String> airBlastTraceDifferences = new LinkedHashMap<>();
    private long lastAirBlastTraceSequence;
    private long lastComparedAirBlastTraceSequence;
    private AirBlastTraceSync.Trace lastComparedLocalAirBlastTrace;
    private AirBlastTraceSync.Trace lastComparedPaperAirBlastTrace;
    private Set<String> authoritativeFlightAbilities = Set.of();
    private long authoritativeFlightSequence = -1L;
    private Set<String> grantedPermissions = Set.of();
    private final Map<BlockKey, BlockMutation> blocks = new HashMap<>();
    private final Map<DirectBlockEffectKey, PredictedDirectBlock> predictedDirectBlocks = new LinkedHashMap<>();
    private final LinkedHashMap<DirectBlockCauseKey, PredictedDirectCause> predictedDirectCauses = new LinkedHashMap<>();
    private final LinkedHashMap<BlockKey, RecentDirectVisual> recentDirectVisuals = new LinkedHashMap<>();
    private final List<ConfirmedDirectBlockPacket> confirmedDirectBlockPackets = new ArrayList<>();
    /**
     * Durable owner view for ordinary Earth writes. Unlike the one-packet
     * receipt fence, this survives chunk snapshots and repeated vanilla block
     * updates. Paper's physical state is kept only as the comparison key; the
     * locally executed common lifecycle remains the caster's visible state.
     */
    private final Map<BlockKey, ServerDirectBlockMask> serverDirectBlockMasks = new LinkedHashMap<>();
    private final ClientTempBlockLedger<BlockKey, BlockState> serverTempBlocks = new ClientTempBlockLedger<>();
    private final Map<Long, LocalTempBlockPrediction> clientTempBlockActions = new LinkedHashMap<>();
    private final Map<Long, BlockState> pendingTempBlockUnderlays = new HashMap<>();
    private final Map<TempBlockEffectKey, Long> clientTempBlockEffects = new HashMap<>();
    private final Map<Long, ServerTempBlockPrediction> authoritativeTempBlockLayers = new HashMap<>();
    private final Map<TempBlockEffectKey, Long> authoritativeTempBlockEffects = new HashMap<>();
    private final Map<Long, Long> pairedServerTempBlocks = new HashMap<>();
    private final Map<BlockKey, Set<Long>> pairedTempBlockCoordinates = new HashMap<>();
    private final Map<BlockKey, CompletedTempBlockRestore> completedTempBlockRestores = new HashMap<>();
    /**
     * Survives an authoritative forced ability teardown so delayed vanilla
     * packets, full chunks and client fluid ticks cannot recreate a state the
     * removed local lifecycle produced. Ordinary moving/replacement TempBlock
     * closes never arm this fence. Entries are exact coordinate/full-state matches.
     */
    private final TempBlockTeardownFence<BlockKey, BlockState> tempBlockTeardownFences =
            new TempBlockTeardownFence<>();
    private final List<VelocityMutation> velocities = new ArrayList<>();
    private final List<VelocityReceipt> velocityReceipts = new ArrayList<>();
    private final ExternalVelocityFence<PendingExternalVelocity> externalVelocityFence =
            new ExternalVelocityFence<>();
    private final List<AbilityStateMutation> abilityStates = new ArrayList<>();
    private final List<AbilityStateReceipt> abilityStateReceipts = new ArrayList<>();
    private final List<ExperienceMutation> experiences = new ArrayList<>();
    private final Map<Integer, Entity> authoritativeEntityAliases = new HashMap<>();
    private final Set<Integer> tempFallingEntityAliases = new HashSet<>();
    /** Owner-only Paper falling entities which have no exact local alias. */
    private final Set<Integer> hiddenTempFallingEntities = new HashSet<>();
    private final Map<Entity, Vec3d> predictedSpawnOrigins = new IdentityHashMap<>();
    private final Map<TempFallingBlockKey, PredictedTempFallingBlock> predictedTempFallingBlocks = new HashMap<>();
    private final Map<TempFallingBlockKey, ServerTempFallingPrepare> serverTempFallingPrepares = new LinkedHashMap<>();
    private final Map<Integer, TempFallingBlockKey> preparedFallingEntityIds = new HashMap<>();
    private final Map<Integer, Long> observedFallingBlockSpawns = new HashMap<>();
    private final PredictionCooldownAuthority cooldownAuthority = new PredictionCooldownAuthority();
    private FabricClientPredictionPlatform platform;
    private BendingManager bendingManager;
    private BendingPlayer bendingPlayer;
    private long tick;
    private long directVisualRevision;
    private boolean ready;
    private boolean initializing;
    private boolean managersStarted;
    /** Set only after the prediction platform has been installed for common code. */
    private boolean commonRuntimeInstalled;
    /** True only while old-world common registries are being retired. */
    private boolean discardingWorldState;
    private String lastStartFailure = "";
    private boolean showServerTempBlocks = Boolean.parseBoolean(
            System.getProperty("projectkorra.prediction.debug.server-temp-blocks", "false"));

    private ExactPredictionRuntime() { }

    public static boolean start(MinecraftClient client, List<PredictionPayloads.ConfigEntry> config,
                                Map<Integer, String> binds, Map<String, Long> cooldowns,
                                List<String> elements, List<String> subElements,
                                List<String> permissions, double airBlastDecay, boolean chiBlocked,
                                RegionProtectionAuthority.Snapshot regionProtection) {
        return INSTANCE.start0(client, config, binds, cooldowns, elements, subElements, permissions,
                airBlastDecay, chiBlocked, regionProtection);
    }

    public static void updatePlayerState(Map<Integer, String> binds, Map<String, Long> cooldowns,
                                         List<String> elements, List<String> subElements,
                                         List<String> permissions, double airBlastDecay, boolean chiBlocked,
                                         RegionProtectionAuthority.Snapshot regionProtection) {
        INSTANCE.updatePlayerState0(binds, cooldowns, elements, subElements, permissions,
                airBlastDecay, chiBlocked, regionProtection);
    }

    public static boolean hasPermission(final String permission) {
        if ((!INSTANCE.ready && !INSTANCE.initializing) || permission == null || permission.isBlank()) return false;
        final String normalized = permission.toLowerCase(Locale.ROOT);
        if (INSTANCE.grantedPermissions.contains("*") || INSTANCE.grantedPermissions.contains(normalized)) return true;
        for (String granted : INSTANCE.grantedPermissions) {
            if (granted.endsWith(".*")
                    && normalized.startsWith(granted.substring(0, granted.length() - 1))) return true;
        }
        return false;
    }

    public static void reconcileActiveFlightAbilities(List<String> activeAbilities, long acknowledgedSequence) {
        INSTANCE.reconcileActiveFlightAbilities0(activeAbilities, acknowledgedSequence);
    }

    public static void tick(MinecraftClient client) { INSTANCE.tick0(client); }
    public static void stop(MinecraftClient client) { INSTANCE.stop0(client); }
    public static boolean isReady() { return INSTANCE.ready; }
    public static String lastStartFailure() { return INSTANCE.lastStartFailure; }
    public static boolean supports(String abilityName) {
        return INSTANCE.ready && abilityName != null
                && (CoreAbility.getAbility(abilityName) != null
                || abilityName.equalsIgnoreCase("FireBlastCharged")
                && CoreAbility.getAbility(FireBlastCharged.class) != null);
    }
    public static List<String> supportedAbilities() {
        if (!INSTANCE.ready) return List.of();
        final Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (CoreAbility ability : CoreAbility.getAbilities()) {
            if (ability != null && ability.getName() != null && !ability.getName().isBlank()) names.add(ability.getName());
        }
        if (CoreAbility.getAbility(FireBlastCharged.class) != null) names.add("FireBlastCharged");
        return List.copyOf(names);
    }
    public static boolean shouldPredictInput(String abilityName, PredictionPayloads.InputKind kind) {
        // Every supported input executes the same common lifecycle locally.
        // In particular, PhaseChange melt must close its client ICE layer; a
        // server-only melt would leave that collision/visual behind until its
        // unrelated timer expired.
        return supports(abilityName);
    }
    public static boolean canActivate(String abilityName) {
        return supports(abilityName) && INSTANCE.bendingPlayer != null && !INSTANCE.bendingPlayer.isOnCooldown(abilityName);
    }
    public static boolean isOnLocalCooldown(String abilityName) {
        if (!supports(abilityName) || INSTANCE.bendingPlayer == null) return false;
        return INSTANCE.bendingPlayer.isOnCooldown(abilityName);
    }
    public static boolean isInputCooldownActive(String abilityName, PredictionPayloads.InputKind kind) {
        if (!supports(abilityName) || INSTANCE.bendingPlayer == null
                || abilityName == null || abilityName.isBlank()) return false;
        if (INSTANCE.bendingPlayer.isOnCooldown(abilityName)) return true;
        // PhaseChange exposes one bound ability but intentionally maintains
        // separate freeze and melt cooldowns. Match only the branch represented
        // by this Paper input; treating either suffix as a family-wide gate
        // would incorrectly block thawing while freeze is cooling down.
        if (abilityName.equalsIgnoreCase("PhaseChange")) {
            return switch (kind) {
                case LEFT_CLICK -> INSTANCE.bendingPlayer.isOnCooldown("PhaseChangeFreeze");
                case SNEAK_START -> INSTANCE.bendingPlayer.isOnCooldown("PhaseChangeMelt");
                default -> false;
            };
        }
        return false;
    }
    public static void removeLocalCooldown(String abilityName) {
        if (INSTANCE.ready && INSTANCE.bendingPlayer != null && abilityName != null && !abilityName.isBlank()) {
            if (INSTANCE.cooldownAuthority.isLocallyPredicted(abilityName)) {
                debug("runtime ignored stale server cooldown removal over newer local generation ability="
                        + abilityName);
                return;
            }
            INSTANCE.bendingPlayer.removeCooldown(abilityName);
        }
    }
    public static void enforceLocalCooldown(String abilityName, long clientUntilMillis) {
        if ((!INSTANCE.ready && !INSTANCE.initializing) || INSTANCE.bendingPlayer == null
                || abilityName == null || abilityName.isBlank()) return;
        long now = System.currentTimeMillis();
        long existing = INSTANCE.bendingPlayer.getCooldown(abilityName);
        if (clientUntilMillis > now && clientUntilMillis > existing) {
            final Cooldown current = INSTANCE.bendingPlayer.getCooldowns().get(abilityName);
            INSTANCE.bendingPlayer.getCooldowns().put(abilityName,
                    new Cooldown(clientUntilMillis, current != null && current.isDatabase()));
            debug("runtime extended predicted cooldown to Paper expiry ability=" + abilityName
                    + " previous=" + existing + " authoritative=" + clientUntilMillis);
        }
    }
    public static void resetLocalAirBlast() {
        if (INSTANCE.ready && INSTANCE.bendingPlayer != null) INSTANCE.bendingPlayer.resetAirBlast();
    }
    public static void setLocalAirBlastDecay(double value) {
        if (INSTANCE.ready && INSTANCE.bendingPlayer != null && Double.isFinite(value)) {
            INSTANCE.bendingPlayer.setAirBlastDecay(Math.max(0.0, Math.min(1.0, value)));
        }
    }

    public static String inputAbilityName(int selectedSlot, String fallback,
                                          PredictionPayloads.InputKind kind) {
        return INSTANCE.inputAbilityName0(selectedSlot, fallback, kind);
    }

    public static boolean shouldTrackDrop() {
        return INSTANCE.ready && INSTANCE.bendingPlayer != null
                && CommonInputHandler.shouldTrackDrop(INSTANCE.bendingPlayer.getPlayer());
    }

    public static void prepareOffHandRightClickEntity() {
        if (INSTANCE.ready && INSTANCE.bendingPlayer != null) {
            CommonInputHandler.prepareRightClickEntity(INSTANCE.bendingPlayer.getPlayer());
        }
    }

    public static boolean input(long sequence, PredictionPayloads.InputKind kind, int selectedSlot,
                                 PredictionClient.ServerPose pose) {
        return INSTANCE.input0(sequence, kind, selectedSlot, pose);
    }

    public static boolean noteNativeAction(PredictionPayloads.NativeAction action) {
        return INSTANCE.noteNativeAction0(action);
    }
    public static long correlatedLocalActionSequence(long paperSequence) {
        return INSTANCE.localActionSequence(paperSequence);
    }

    public static void noteAirBlastTrace(PredictionPayloads.AirBlastTraceReceipt receipt) {
        INSTANCE.noteAirBlastTrace0(receipt);
    }

    static List<String> airBlastParityReport() {
        return INSTANCE.airBlastParityReport0();
    }

    static List<String> abilityRemovalReport() {
        return INSTANCE.abilityRemovalReport0();
    }

    static List<String> tempBlockReport() {
        return INSTANCE.tempBlockReport0();
    }

    public static boolean isNativeActionConfirmed(long sequence) {
        final Action action = INSTANCE.actions.get(sequence);
        return INSTANCE.ready && action != null && action.nativeConfirmed;
    }

    public static PredictionClient.ServerPose executionPose() {
        return INSTANCE.executionPose0();
    }

    public static void predictMovement(MinecraftClient client, PredictionClient.ServerPose from,
                                       PredictionClient.ServerPose to) {
        INSTANCE.predictMovement0(client, from, to);
    }

    public static void reconcile(long sequence, Vec3d authoritativeOrigin,
                                  String ability, long cooldownUntil) {
        INSTANCE.reconcile0(sequence, authoritativeOrigin, ability, cooldownUntil);
    }

    public static BlockState blockState(ClientWorld world, BlockPos pos) {
        return INSTANCE.simulatedBlockState0(world, pos.toImmutable());
    }

    public static void setPredictedBlock(ClientWorld world, BlockPos pos, BlockState state) {
        if (INSTANCE.discardingWorldState) return;
        if (TempBlockSync.currentWorldMutation() != null) {
            INSTANCE.setTempBlock0(world, pos.toImmutable(), state);
        } else {
            INSTANCE.setBlock0(world, pos.toImmutable(), state);
        }
    }

    public static boolean authoritativeBlock(ClientWorld world, BlockPos pos, BlockState state) {
        return INSTANCE.authoritativeBlock0(world, pos.toImmutable(), state);
    }

    public static boolean authoritativeBlockBatch(ClientWorld world, List<BlockPos> positions, List<BlockState> states) {
        return INSTANCE.authoritativeBlockBatch0(world, positions, states);
    }

    public static void acceptAuthoritativeChunk(ClientWorld world, int chunkX, int chunkZ) {
        INSTANCE.acceptAuthoritativeChunk0(world, chunkX, chunkZ);
    }

    public static void applyTempBlockBatch(ClientWorld world, PredictionPayloads.TempBlockBatch batch) {
        INSTANCE.applyTempBlockBatch0(world, batch);
    }

    public static void noteDirectBlock(Entity localPlayer, PredictionPayloads.DirectBlockReceipt receipt) {
        INSTANCE.noteDirectBlock0(localPlayer, receipt);
    }

    public static List<PredictionDesyncBlock> ownedTempDesyncs(ClientWorld world) {
        return INSTANCE.ownedTempDesyncs0(world);
    }

    public static void setPredictedVelocity(Entity entity, Vec3d velocity) {
        INSTANCE.setVelocity0(entity, velocity);
    }

    public static void noteVelocityOwner(Entity localPlayer, PredictionPayloads.VelocityOwner owner) {
        INSTANCE.noteVelocityOwner0(localPlayer, owner);
    }

    public static void noteVelocityOwner(Entity localPlayer, PredictionPayloads.VelocityOwnerV2 owner) {
        INSTANCE.noteVelocityOwner0(localPlayer, owner);
    }
    public static void noteAbilityStateOwner(Entity localPlayer, PredictionPayloads.AbilityStateOwner owner) {
        INSTANCE.noteAbilityStateOwner0(localPlayer, owner);
    }

    public static void noteTempFallingBlock(Entity localPlayer,
                                            PredictionPayloads.TempFallingBlockReceipt receipt) {
        INSTANCE.noteTempFallingBlock0(localPlayer, receipt);
    }
    public static void noteTempFallingBlockPrepare(Entity localPlayer,
                                                   PredictionPayloads.TempFallingBlockPrepare prepare) {
        INSTANCE.noteTempFallingBlockPrepare0(localPlayer, prepare);
    }

    public static void removeAuthoritativeAbility(Entity localPlayer, PredictionPayloads.AbilityRemoved removed) {
        INSTANCE.removeAuthoritativeAbility0(localPlayer, removed);
    }

    static boolean removalReceiptMayResolve(final boolean externallyCaused,
                                            final boolean actionRetained,
                                            final boolean nativeActionConfirmed) {
        // A collision/other external close is the authority the local owner
        // cannot derive. Its exact player + implementation + creation sequence
        // identity remains useful after the short-lived Action bookkeeping is
        // gone. Ordinary lifecycle closes still require the paired native
        // action so delayed Paper metadata cannot truncate local prediction.
        return externallyCaused || actionRetained && nativeActionConfirmed;
    }

    static boolean authoritativeEmptyTypeFenceCoversCandidate(final boolean externallyCaused,
                                                               final int remainingTypeInstances,
                                                               final long localAcknowledgedSequence,
                                                               final Long candidateLatestSequence) {
        // A zero count is an authoritative statement about the concrete class,
        // not its non-unique display name. Both sides' raw counters can drift,
        // so this fence accepts only already-correlated local ordinals. An
        // unknown identity is never permission to delete a live prediction.
        return externallyCaused && remainingTypeInstances == 0
                && localAcknowledgedSequence > 0L && candidateLatestSequence != null
                && candidateLatestSequence <= localAcknowledgedSequence;
    }

    /** @return true when vanilla must not apply the same impulse a second time. */
    public static boolean authoritativeVelocity(int entityId, Vec3d velocity) {
        return INSTANCE.authoritativeVelocity0(entityId, velocity);
    }

    public static void notePredictedAbilityState(boolean invulnerable, boolean flying, boolean allowFlying,
                                                 boolean creativeMode, float flySpeed, float walkSpeed) {
        INSTANCE.notePredictedAbilityState0(invulnerable, flying, allowFlying, creativeMode, flySpeed, walkSpeed);
    }
    public static void notePredictedExperience(float barProgress, int experience, int level) {
        INSTANCE.notePredictedExperience0(barProgress, experience, level);
    }
    public static boolean suppressAuthoritativeAbilityState(PlayerAbilitiesS2CPacket packet) {
        return INSTANCE.suppressAuthoritativeAbilityState0(packet);
    }
    public static boolean suppressAuthoritativeExperience(ExperienceBarUpdateS2CPacket packet) {
        return INSTANCE.suppressAuthoritativeExperience0(packet);
    }
    public static boolean notePredictedSelectedSlot(int slot) {
        return INSTANCE.notePredictedSelectedSlot0(slot);
    }
    public static boolean suppressAuthoritativeEntityData(int entityId) {
        return INSTANCE.ready && (INSTANCE.authoritativeEntityAliases.containsKey(entityId)
                || INSTANCE.hiddenTempFallingEntities.contains(entityId));
    }
    public static boolean suppressAuthoritativeBreakAnimation(ClientWorld world, BlockPos pos) {
        BlockMutation mutation = INSTANCE.blocks.get(new BlockKey(world, pos.toImmutable()));
        return INSTANCE.ready && mutation != null && mutation.locallyPredicted;
    }

    public static boolean isPredictedOwned(Entity entity) {
        return entity != null && INSTANCE.predictedSpawnOrigins.containsKey(entity);
    }
    public static void trackSpawn(Entity entity) { INSTANCE.trackSpawn0(entity); }
    public static boolean reconcileSpawn(EntitySpawnS2CPacket packet) { return INSTANCE.reconcileSpawn0(packet); }
    public static Entity aliasedEntity(int serverEntityId) {
        // Generic predicted entities adopt the authoritative id so normal
        // movement packets can finish reconciling them. TempFallingBlocks are
        // different: their common client simulation is the visual authority.
        // The packet handler already cancels the normal movement/teleport
        // packets for aliases. Returning null here also prevents any remaining
        // lookup-driven packet from steering the client-owned simulation and
        // reintroducing the round-trip snaps seen by EarthLine.
        return INSTANCE.tempFallingEntityAliases.contains(serverEntityId)
                ? null : INSTANCE.authoritativeEntityAliases.get(serverEntityId);
    }
    public static boolean hasEntityAlias(int serverEntityId) {
        return INSTANCE.authoritativeEntityAliases.containsKey(serverEntityId)
                || INSTANCE.hiddenTempFallingEntities.contains(serverEntityId);
    }
    public static boolean tracksVelocityEntity(int entityId) { return INSTANCE.tracksVelocityEntity0(entityId); }
    public static boolean removeAliasedEntity(int serverEntityId) { return INSTANCE.removeAliasedEntity0(serverEntityId); }
    public static boolean toggleServerTempBlockDebug() {
        INSTANCE.showServerTempBlocks = !INSTANCE.showServerTempBlocks;
        INSTANCE.repaintAllAuthoritativeTempBlocks();
        return INSTANCE.showServerTempBlocks;
    }
    public static boolean showsServerTempBlocks() { return INSTANCE.showServerTempBlocks; }
    public static long captureAction() { return INSTANCE.currentAction(); }
    public static void runWithAction(long action, Runnable task) {
        if (action <= 0) { task.run(); return; }
        Long previous = INPUT_ACTION.get();
        INPUT_ACTION.set(action);
        final Action correlated = INSTANCE.actions.get(action);
        final long deterministicSeed = correlated == null ? action : correlated.deterministicSeed;
        try { PredictionDeterminism.run(action, deterministicSeed, task); }
        finally { if (previous == null) INPUT_ACTION.remove(); else INPUT_ACTION.set(previous); }
    }

    private boolean start0(MinecraftClient client, List<PredictionPayloads.ConfigEntry> entries,
                           Map<Integer, String> binds, Map<String, Long> cooldowns,
                           List<String> elements, List<String> subElements,
                           List<String> permissions, double airBlastDecay, boolean chiBlocked,
                           RegionProtectionAuthority.Snapshot regionProtection) {
        debug("runtime start requested ready=" + ready + " initializing=" + initializing
                + " integratedServer=" + (client.getServer() != null)
                + " player=" + (client.player != null) + " world=" + (client.world != null)
                + " configEntries=" + entries.size() + " binds=" + binds);
        if (ready) {
            applyConfig(entries);
            updatePlayerState0(binds, cooldowns, elements, subElements, permissions, airBlastDecay,
                    chiBlocked, regionProtection);
            debug("runtime already ready; state refreshed");
            return true;
        }
        // A logical client and an integrated server share one JVM and therefore
        // cannot safely own the old global Platform singleton simultaneously.
        if (client.getServer() != null || client.player == null || client.world == null || initializing) {
            debug("runtime start refused integratedServer=" + (client.getServer() != null)
                    + " player=" + (client.player != null) + " world=" + (client.world != null)
                    + " initializing=" + initializing);
            return false;
        }
        initializing = true;
        grantedPermissions = normalizePermissions(permissions);
        try {
            platform = new FabricClientPredictionPlatform(client);
            Platform.install(platform);
            commonRuntimeInstalled = true;
            ProjectKorra.initCommon();
            Manager.startup();
            managersStarted = true;
            applyConfig(entries);
            ElementalAbility.clearBendableMaterials();
            ElementalAbility.setupBendableMaterials();
            EarthTunnel.clearBendableMaterials();
            EarthTunnel.setupBendableMaterials();
            Bloodbending.loadBloodlessFromConfig();

            // Reproduce GeneralMethods.reloadPlugin's Paper scheduler order.
            // These managers are gameplay, not presentation: WaterbendingManager
            // retires/redraws WaterArms, Torrent, and WaterSpoutWave layers;
            // EarthbendingManager drains moved-earth restores; BendingManager
            // advances abilities, falling blocks, and TempBlock expirations.
            // Scheduling them before embedded addons also preserves Bukkit's
            // task-id order instead of running every addon task first.
            bendingManager = new BendingManager();
            Platform.scheduler().runTimer(bendingManager, 0, 1);
            Platform.scheduler().runTimer(new WaterbendingManager(ProjectKorra.plugin), 0, 1);
            Platform.scheduler().runTimer(new EarthbendingManager(ProjectKorra.plugin), 0, 1);
            Platform.scheduler().runTimer(new FirebendingManager(ProjectKorra.plugin), 0, 1);
            Platform.scheduler().runTimer(new ChiblockingManager(ProjectKorra.plugin), 0, 1);
            Platform.scheduler().runTimer(new CooldownDisplayHandler(), 0, 1);
            Platform.scheduler().runTimer(new RegenHandler(ProjectKorra.plugin), 0, 20);
            Platform.scheduler().runTimer(new BendingManager.TempElementsRunnable(), 20, 20);
            ProjectKorra.plugin.revertChecker = Platform.scheduler().runTimerAsync(
                    new RevertChecker(ProjectKorra.plugin), 0, 200);

            new MultiAbilityManager();
            new ComboManager();
            if (ProjectKorra.collisionManager != null) {
                ProjectKorra.collisionManager.stopCollisionDetection();
            }
            ProjectKorra.collisionManager = new CollisionManager();
            ProjectKorra.collisionInitializer = new CollisionInitializer(ProjectKorra.collisionManager);
            CoreAbility.registerAbilities();
            EmbeddedAddonBootstrap.enable();
            // Addon startup registers its own Config instances. Apply the same
            // snapshot again so those late sources receive server values too.
            applyConfig(entries);
            AbilityActivationManager.reload();
            ComboManager.registerCombos();
            FallHandler.loadNoFallDamageAbilities();
            ProjectKorra.collisionInitializer.initializeDefaultCollisions();
            Player player = FabricPredictionMC.player(client.player);
            bendingPlayer = new BendingPlayer(player);
            BendingPlayer.getPlayers().put(player.getUniqueId(), bendingPlayer);
            BendingPlayer.getOfflinePlayers().put(player.getUniqueId(), bendingPlayer);
            CooldownSync.install(this);
            TempBlockSync.install(this);
            TempFallingBlockSync.install(this);
            updatePlayerState0(binds, cooldowns, elements, subElements, permissions, airBlastDecay,
                    chiBlocked, regionProtection);
            ready = true;
            lastStartFailure = "";
            ProjectKorra.log.info("Exact client prediction enabled with " + CoreAbility.getAbilities().size() + " local abilities");
            debug("runtime ready abilities=" + CoreAbility.getAbilities().size()
                    + " activeInstances=" + CoreAbility.getAbilitiesByInstances().size()
                    + " playerElements=" + bendingPlayer.getElements()
                    + " playerSubElements=" + bendingPlayer.getSubElements());
            return true;
        } catch (Throwable failure) {
            lastStartFailure = failure.getClass().getSimpleName()
                    + (failure.getMessage() == null || failure.getMessage().isBlank()
                    ? "" : ": " + failure.getMessage());
            ProjectKorra.log.log(Level.SEVERE, "Exact client prediction could not start", failure);
            debug("runtime start failed " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            stop0(client);
            return false;
        } finally {
            initializing = false;
        }
    }

    private void applyConfig(List<PredictionPayloads.ConfigEntry> entries) {
        Map<String, Map<String, Object>> namespaces = new HashMap<>();
        for (PredictionPayloads.ConfigEntry entry : entries) {
            int dot = entry.path().indexOf('.');
            if (dot <= 0 || dot == entry.path().length() - 1) continue;
            namespaces.computeIfAbsent(entry.path().substring(0, dot), ignored -> new LinkedHashMap<>())
                    .put(entry.path().substring(dot + 1), decode(entry));
        }
        apply(ConfigManager.defaultConfig, namespaces.get("config"));
        apply(ConfigManager.avatarStateConfig, namespaces.get("avatarstate"));
        apply(ConfigManager.collisionConfig, namespaces.get("collision"));
        apply(ConfigManager.fireColorsConfig, namespaces.get("fire_colors"));
        apply(ConfigManager.airColorsConfig, namespaces.get("air_colors"));
        apply(ConfigManager.waterCosmeticsConfig, namespaces.get("water_cosmetics"));
        apply(ConfigManager.earthCosmeticsConfig, namespaces.get("earth_cosmetics"));
        apply(ConfigManager.fallDamageConfig, namespaces.get("fall_damage"));
        if (ConfigManager.styleConfigs != null) {
            for (int i = 0; i < ConfigManager.styleConfigs.size(); i++) apply(ConfigManager.styleConfigs.get(i), namespaces.get("style_" + i));
        }
        PredictionConfigSync.sources().forEach((namespace, source) ->
                apply(source, namespaces.get(namespace)));
    }

    private static void apply(Config config, Map<String, Object> values) {
        if (config != null && values != null) config.applyRemoteValues(values);
    }

    private static Object decode(PredictionPayloads.ConfigEntry entry) {
        if (entry.type() == PredictionPayloads.ValueType.STRING_LIST) return List.copyOf(entry.values());
        String value = entry.values().isEmpty() ? "" : entry.values().get(0);
        try {
            return switch (entry.type()) {
                case BOOLEAN -> Boolean.parseBoolean(value);
                case INTEGER -> Long.parseLong(value);
                case DECIMAL -> Double.parseDouble(value);
                default -> value;
            };
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private void updatePlayerState0(Map<Integer, String> binds, Map<String, Long> cooldowns,
                                    List<String> elements, List<String> subElements,
                                    List<String> permissions, double airBlastDecay, boolean chiBlocked,
                                    RegionProtectionAuthority.Snapshot regionProtection) {
        grantedPermissions = normalizePermissions(permissions);
        if (!ready && !initializing || bendingPlayer == null) {
            debug("runtime state ignored ready=" + ready + " initializing=" + initializing
                    + " hasBendingPlayer=" + (bendingPlayer != null));
            return;
        }
        // A state packet sent before the server processes the WaterArms cast
        // can arrive after the client has already entered its multi-bind. Do
        // not replace those local mode slots with the stale pre-cast binds.
        // Rejection/removal calls unbindMultiAbility and restores them.
        if (!MultiAbilityManager.hasMultiAbilityBound(bendingPlayer.getPlayer())) {
            bendingPlayer.getAbilities().clear();
            bendingPlayer.getAbilities().putAll(binds);
        }
        bendingPlayer.getElements().clear();
        for (String name : elements) {
            Element element = Element.getElement(name);
            if (element != null && !(element instanceof Element.SubElement)) bendingPlayer.getElements().add(element);
        }
        bendingPlayer.getSubElements().clear();
        for (String name : subElements) {
            Element element = Element.getElement(name);
            if (element instanceof Element.SubElement subElement) bendingPlayer.getSubElements().add(subElement);
        }
        if (chiBlocked) bendingPlayer.blockChi();
        else bendingPlayer.unblockChi();
        RegionProtectionAuthority.install(bendingPlayer.getPlayer(), regionProtection);
        PassiveManager.registerPassives(bendingPlayer.getPlayer());
        // Import cooldowns once when prediction joins an already-running
        // server session. Later state snapshots describe Paper's delayed
        // timeline and must not extend a cooldown which the common client
        // already started on the input frame.
        reconcileAuthoritativeCooldowns(cooldowns);
        // Seed stamina once when prediction starts. Periodic server snapshots
        // arrive on a different clock and otherwise fight the locally
        // progressed AirAgility XP bar. Explicit resets use StateDirective.
        if (initializing || !ready) {
            bendingPlayer.setAirBlastDecay(airBlastDecay);
        }
        debug("runtime state applied binds=" + bendingPlayer.getAbilities()
                + " elements=" + bendingPlayer.getElements()
                + " subElements=" + bendingPlayer.getSubElements()
                + " cooldowns=" + bendingPlayer.getCooldowns().keySet()
                + " airBlastStamina=" + airBlastStamina());
    }

    private static Set<String> normalizePermissions(final List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) return Set.of();
        final Set<String> normalized = new HashSet<>();
        for (String permission : permissions) {
            if (permission != null && !permission.isBlank()) {
                normalized.add(permission.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(normalized);
    }

    private String inputAbilityName0(int selectedSlot, String fallback, PredictionPayloads.InputKind kind) {
        if (!ready || bendingPlayer == null) return fallback == null ? "" : fallback;
        Player player = bendingPlayer.getPlayer();
        if (kind == PredictionPayloads.InputKind.SNEAK_START && canStartFastSwim(player)) {
            return "FastSwim";
        }
        String multi = MultiAbilityManager.getBoundMultiAbility(player);
        if (multi != null && !multi.isBlank()) return multi;
        String local = bendingPlayer.getAbilities().get(selectedSlot + 1);
        String selected = local == null || local.isBlank() ? (fallback == null ? "" : fallback) : local;
        if (selected.equalsIgnoreCase("FireBlast")
                && (kind == PredictionPayloads.InputKind.SNEAK_START || kind == PredictionPayloads.InputKind.SNEAK_STOP)) {
            return "FireBlastCharged";
        }
        // WallRun is a global passive click and deliberately does not need a
        // bind. Give an otherwise unbound left click a logical ability so it
        // enters the exact prediction/reconciliation path.
        if (selected.isBlank() && kind == PredictionPayloads.InputKind.LEFT_CLICK
                && bendingPlayer.isToggled() && CoreAbility.getAbility(WallRun.class) != null) {
            return "WallRun";
        }
        return selected;
    }

    private boolean canStartFastSwim(Player player) {
        if (player == null || CoreAbility.hasAbility(player, FastSwim.class)) return false;
        CoreAbility bound = bendingPlayer.getBoundAbility();
        CoreAbility passive = CoreAbility.getAbility(FastSwim.class);
        return (bound == null || !bound.isSneakAbility()) && PassiveManager.hasPassive(player, passive);
    }

    private boolean input0(long sequence, PredictionPayloads.InputKind kind, int selectedSlot,
                           PredictionClient.ServerPose pose) {
        if (!ready || bendingPlayer == null) {
            debug("runtime input skipped sequence=" + sequence + " kind=" + kind
                    + " ready=" + ready + " hasBendingPlayer=" + (bendingPlayer != null));
            return false;
        }
        Set<CoreAbility> before = Collections.newSetFromMap(new IdentityHashMap<>());
        before.addAll(CoreAbility.getAbilitiesByInstances());
        Player player = bendingPlayer.getPlayer();
        String boundName = inputAbilityName0(player.getInventory().getHeldItemSlot(), bendingPlayer.getBoundAbilityName(), kind);
        Vec3d origin = pose.eyePos();
        Action action = new Action(sequence, tick, origin, pose.yaw(), pose.pitch(), pose.eyeHeight(),
                boundName, kind, selectedSlot);
        actions.put(sequence, action);
        boolean failed = false;
        AbilityActivationManager.TrackingResult trackingResult =
                new AbilityActivationManager.TrackingResult(false, List.of());
        debug("runtime input start sequence=" + sequence + " kind=" + kind
                + " bound=" + boundName
                + " activeBefore=" + before.size() + " tick=" + tick);
        INPUT_ACTION.set(sequence);
        INPUT_EVENT_POSE.set(sequence);
        AbilityActivationManager.beginTracking();
        try {
            PredictionDeterminism.run(sequence, action.deterministicSeed, () -> {
                switch (kind) {
                    case LEFT_CLICK -> {
                        CommonInputHandler.handleSwing(player, Set.of(), new HashSet<>());
                        com.projectkorra.projectkorra.platform.mc.entity.Entity target =
                                GeneralMethods.getTargetedEntity(player, 3);
                        if (target instanceof com.projectkorra.projectkorra.platform.mc.entity.LivingEntity living
                                && !target.equals(player)) {
                            CommonInputHandler.handleEntityLeftClick(player, living);
                        }
                    }
                    case RIGHT_CLICK -> CommonInputHandler.handleRightClick(player, ClickType.RIGHT_CLICK);
                    case RIGHT_CLICK_BLOCK -> CommonInputHandler.handleRightClick(player, ClickType.RIGHT_CLICK_BLOCK);
                    case RIGHT_CLICK_ENTITY -> CommonInputHandler.handleRightClickEntity(player);
                    case SNEAK_START -> PredictionClient.withInputSneaking(false,
                            () -> CommonInputHandler.handleSneak(player, false));
                    case SNEAK_STOP -> PredictionClient.withInputSneaking(true,
                            () -> CommonInputHandler.handleSneak(player, true));
                    case SWAP_HANDS -> CommonInputHandler.handleSwapHands(player,
                            player.getInventory().getItemInMainHand().getType() == Material.AIR,
                            player.getInventory().getItemInOffHand() == null
                                    || player.getInventory().getItemInOffHand().getType() == Material.AIR);
                }
            });
        } catch (Throwable failure) {
            ProjectKorra.log.warning("Predicted input " + sequence + " failed: " + failure.getMessage());
            debug("runtime input failed sequence=" + sequence + " " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            failed = true;
        } finally {
            trackingResult = AbilityActivationManager.finishTrackingResult();
            INPUT_EVENT_POSE.remove();
            INPUT_ACTION.remove();
            blocks.entrySet().removeIf(entry -> entry.getValue().lastAction == sequence);
        }
        for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (!before.contains(ability)) {
                associateAbility(action, ability);
                abilityCreationActions.putIfAbsent(ability, sequence);
                action.abilities.add(ability);
                debug("runtime created ability sequence=" + sequence + " ability=" + ability.getName()
                        + " instance=" + System.identityHashCode(ability));
            }
        }
        debug("runtime input finish sequence=" + sequence + " created=" + action.abilities.size()
                + " activeAfter=" + CoreAbility.getAbilitiesByInstances().size() + " failed=" + failed);
        if (failed) {
            abortFailedLocalInput(action);
            return false;
        }
        // Existing-instance transitions are generic: release, throw, redirect,
        // thaw, and multi-stage clicks may mutate a running instance without
        // constructing another persistent ability.
        boolean hasMatchingExistingAbility = affectedExistingAbility(before, boundName);
        List<CoreAbility> explicitExisting = trackingResult.affectedAbilities().stream()
                .filter(before::contains)
                .filter(ability -> ability != null && !ability.isRemoved() && ability.getPlayer() != null
                        && ability.getPlayer().getUniqueId().equals(player.getUniqueId()))
                .toList();
        boolean createdMatchingAbility = action.abilities.stream()
                .anyMatch(ability -> matchesInputAbility(ability, boundName));
        boolean affectedExisting = !explicitExisting.isEmpty()
                || trackingResult.handled() && hasMatchingExistingAbility && !createdMatchingAbility;
        boolean producedTempBlock = clientTempBlockActions.values().stream()
                .anyMatch(local -> local.actionSequence == sequence);
        // Semantic Earth attempts count even when this ClientWorld already had
        // the requested state. Paper uses the same definition, so an
        // asymmetric no-op cannot disagree about whether the action ran.
        boolean producedDirectBlock = !action.directBlockOrdinals.isEmpty();
        boolean locallyPredicted = !action.abilities.isEmpty() || !action.spawned.isEmpty()
                || producedTempBlock || producedDirectBlock || affectedExisting
                || !action.abilityStateOrdinals.isEmpty();
        action.locallyPredicted = locallyPredicted;
        if (affectedExisting) {
            if (!explicitExisting.isEmpty()) {
                for (CoreAbility ability : explicitExisting) associateAbility(action, ability);
            } else {
                for (CoreAbility ability : before) {
                    if (ability != null && !ability.isRemoved() && matchesInputAbility(ability, boundName)
                            && ability.getPlayer() != null
                            && ability.getPlayer().getUniqueId().equals(player.getUniqueId())) {
                        associateAbility(action, ability);
                    }
                }
            }
        }
        debug("runtime input localPrediction sequence=" + sequence + " immediateEffect=" + locallyPredicted
                + " affectedExisting=" + affectedExisting);
        // The server processes every frame regardless of this flag. This flag
        // only says whether the client produced an effect that must be paired
        // with its physical server counterpart during reconciliation.
        return locallyPredicted;
    }

    private void associateAbility(final Action action, final CoreAbility ability) {
        if (action == null || ability == null) return;
        final Long previousSequence = abilityActions.get(ability);
        if (!action.previousAbilityActions.containsKey(ability)) {
            action.previousAbilityActions.put(ability, previousSequence);
        }
        if (previousSequence != null && previousSequence != action.sequence) {
            final Action previous = actions.get(previousSequence);
            if (previous != null) previous.abilities.remove(ability);
        }
        abilityActions.put(ability, action.sequence);
        // This is lifecycle ownership, not just input-time bookkeeping. A
        // staged ability may create TempBlocks or falling blocks long after a
        // throw/release/redirect input, so the controlling action must remain
        // retained until the ability itself ends.
        action.abilities.add(ability);
    }

    private boolean affectedExistingAbility(Set<CoreAbility> before, String boundName) {
        if (before == null || before.isEmpty() || boundName == null || boundName.isBlank() || bendingPlayer == null) return false;
        Player player = bendingPlayer.getPlayer();
        if (player == null) return false;
        for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (!before.contains(ability) || ability.isRemoved() || !matchesInputAbility(ability, boundName)) continue;
            if (ability.getPlayer() == null || !ability.getPlayer().getUniqueId().equals(player.getUniqueId())) continue;
            return true;
        }
        return false;
    }

    private static boolean matchesInputAbility(CoreAbility ability, String inputName) {
        return ability != null && inputName != null
                && (inputName.equalsIgnoreCase(ability.getName())
                || inputName.equalsIgnoreCase("FireBlastCharged") && ability instanceof FireBlastCharged);
    }

    private void predictMovement0(MinecraftClient client, PredictionClient.ServerPose fromPose,
                                  PredictionClient.ServerPose toPose) {
        if (!ready || bendingPlayer == null || client == null || client.player == null || client.world == null
                || fromPose == null || toPose == null) {
            return;
        }
        Location from = FabricPredictionMC.location(client.world,
                new Vec3d(fromPose.x(), fromPose.y(), fromPose.z()), fromPose.yaw(), fromPose.pitch());
        Location to = FabricPredictionMC.location(client.world,
                new Vec3d(toPose.x(), toPose.y(), toPose.z()), toPose.yaw(), toPose.pitch());
        CommonPlayerListenerCore.MovementResult result = CommonPlayerListenerCore.handleMove(
                bendingPlayer.getPlayer(), from, to, false, false, 0.0);
        if (result.cancelEvent()) {
            debug("runtime movement prediction requested cancel from common listener");
        }
    }

    private void tick0(MinecraftClient client) {
        tick++;
        if (!ready || client.player == null || client.world == null) {
            if (DEBUG && tick % 20 == 0) debug("runtime tick skipped ready=" + ready
                    + " player=" + (client.player != null) + " world=" + (client.world != null));
            return;
        }
        try {
            platform.tick();
            // BendingManager.handleCooldowns runs inside the scheduler above,
            // after ability progress, exactly as it does on Paper. Removing a
            // raw timestamp before this heartbeat creates a one-tick window in
            // which Fabric accepts an input that Paper still rejects.
            cooldownAuthority.retainLocallyActive(Set.copyOf(bendingPlayer.getCooldowns().keySet()));
        } catch (Throwable failure) {
            ProjectKorra.log.warning("Predicted ability tick failed: " + failure.getMessage());
            debug("runtime tick failed " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        // Paper's externally owned impulse is the final velocity authority for
        // its server tick. Apply it after local scooter/jet/spout progress so a
        // client heartbeat cannot overwrite the hit before movement consumes it.
        applyPendingExternalVelocities(client.world);
        // Client fluid/block ticks can run after an authoritative forced
        // teardown. Audit before rendering, while the exact-state fence still
        // owns that removed local lifecycle's view.
        auditTempBlockTeardownFences(client.world);
        // Direct/permanent writes exist only as a read-after-write view inside
        // the currently executing input or progress pass. Retaining them into
        // another tick would let a wrong FireBlast/RaiseEarth guess influence
        // later common logic even though it was never painted into ClientWorld.
        blocks.clear();
        Set<CoreAbility> live = Collections.newSetFromMap(new IdentityHashMap<>());
        live.addAll(CoreAbility.getAbilitiesByInstances());
        abilityActions.keySet().removeIf(ability -> !live.contains(ability));
        abilityCreationActions.keySet().removeIf(ability -> !live.contains(ability));
        velocities.removeIf(mutation -> tick - mutation.tick > ACTION_RETENTION_TICKS);
        velocityReceipts.removeIf(receipt -> tick - receipt.receivedTick > VELOCITY_RECEIPT_TICKS);
        completedTempBlockRestores.entrySet().removeIf(entry -> tick - entry.getValue().tick > 2L);
        tempBlockTeardownFences.expireBefore(tick - ACTION_RETENTION_TICKS);
        abilityStates.removeIf(mutation -> tick - mutation.tick > ACTION_RETENTION_TICKS);
        abilityStateReceipts.removeIf(receipt -> tick - receipt.receivedTick > 4L);
        experiences.removeIf(mutation -> tick - mutation.tick > ACTION_RETENTION_TICKS);
        observedFallingBlockSpawns.entrySet().removeIf(entry -> tick - entry.getValue() > ACTION_RETENTION_TICKS);
        serverTempFallingPrepares.entrySet().removeIf(entry -> tick - entry.getValue().receivedTick > 8L);
        preparedFallingEntityIds.entrySet().removeIf(entry -> !serverTempFallingPrepares.containsKey(entry.getValue()));
        confirmedDirectBlockPackets.removeIf(packet -> tick - packet.receivedTick > 4L);
        // Safe bookkeeping only: equal physical/viewer entries paint nothing.
        // Keep them throughout the causal lifecycle so a later local restore
        // can move the viewer ahead of Paper without reopening a chunk race.
        serverDirectBlockMasks.entrySet().removeIf(entry -> {
            final ServerDirectBlockMask mask = entry.getValue();
            return mask.serverState.equals(mask.viewerState)
                    && tick - mask.updatedTick > ACTION_RETENTION_TICKS
                    && !hasActiveClientDirectCause(mask.ownerId, mask.cause);
        });
        expireUnconfirmedDirectBlocks();
        final Set<DirectBlockCauseKey> activeEarthCauses = activeClientEarthCauses(client.player.getUuid());
        predictedDirectCauses.entrySet().removeIf(entry ->
                tick - entry.getValue().lastTick > EARTH_CAUSE_RETENTION_TICKS
                        && !actions.containsKey(entry.getKey().actionSequence)
                        && !activeEarthCauses.contains(entry.getKey()));
        recentDirectVisuals.entrySet().removeIf(entry ->
                tick - entry.getValue().createdTick > EARTH_CAUSE_RETENTION_TICKS);
        while (predictedDirectCauses.size() > 4_096) {
            predictedDirectCauses.remove(predictedDirectCauses.keySet().iterator().next());
        }
        while (recentDirectVisuals.size() > 4_096) {
            recentDirectVisuals.remove(recentDirectVisuals.keySet().iterator().next());
        }
        expireUnconfirmedTempBlocks();
        blocks.entrySet().removeIf(entry -> {
            BlockMutation mutation = entry.getValue();
            long age = tick - mutation.lastTick;
            int confirmationTicks = blockConfirmationTicks(mutation.lastAction);
            return age > confirmationTicks;
        });
        Iterator<Action> iterator = actions.values().iterator();
        while (iterator.hasNext()) {
            Action action = iterator.next();
            action.abilities.removeIf(ability -> !live.contains(ability));
            if (tick - action.createdTick > ACTION_RETENTION_TICKS && action.abilities.isEmpty()) {
                action.spawned.forEach(predictedSpawnOrigins::remove);
                predictedTempFallingBlocks.keySet().removeIf(key -> key.actionSequence == action.sequence);
                serverTempFallingPrepares.keySet().removeIf(key -> key.actionSequence == action.sequence);
                preparedFallingEntityIds.entrySet().removeIf(entry -> entry.getValue().actionSequence == action.sequence);
                iterator.remove();
            }
        }
        if (DEBUG && tick % 20 == 0) {
            debug("runtime tick=" + tick + " active=" + live.size()
                    + " actions=" + actions.size() + " predictedBlocks=" + blocks.size()
                    + " velocities=" + velocities.size());
        }
    }

    private void reconcileAuthoritativeCooldowns(final Map<String, Long> authoritativeCooldowns) {
        if (bendingPlayer == null) return;
        if ((initializing || !ready) && authoritativeCooldowns != null) {
            authoritativeCooldowns.forEach(ExactPredictionRuntime::enforceLocalCooldown);
        }
    }

    private boolean noteNativeAction0(final PredictionPayloads.NativeAction receipt) {
        if (!ready || receipt == null || !receipt.predictable()) return false;
        final Long existingAlias = nativeActionAliases.get(receipt.actionSequence());
        Action action = existingAlias == null ? findUnpairedNativeAction(receipt) : actions.get(existingAlias);
        if (!matchesNativeAction(action, receipt)) {
            if ("AirBlast".equalsIgnoreCase(receipt.ability())) {
                airBlastNativeRejections.put(receipt.actionSequence(), nativeActionMismatch(action, receipt));
            }
            debug("runtime rejected non-identical native action sequence=" + receipt.actionSequence()
                    + " kind=" + receipt.kind() + " slot=" + receipt.selectedSlot()
                    + " ability=" + receipt.ability());
            trimAirBlastTraces();
            return false;
        }
        nativeActionAliases.put(receipt.actionSequence(), action.sequence);
        if ("AirBlast".equalsIgnoreCase(receipt.ability())) {
            airBlastSequenceAliases.put(receipt.actionSequence(), action.sequence);
            aliasLocalAirBlastTraces(receipt.actionSequence(), action.sequence);
            if (receipt.actionSequence() != action.sequence) {
                airBlastNativeRejections.put(receipt.actionSequence(),
                        "raw sequence differed; paired semantic local seq=" + action.sequence);
            }
        }
        action.nativeConfirmed = true;
        if (action.inputAbility.equalsIgnoreCase("AirBlast")) {
            airBlastInputDifferences.put(receipt.actionSequence(), firstInputPoseDifference(action, receipt));
            compareLatestAirBlastTrace(receipt.actionSequence());
        }
        debug("runtime confirmed native action sequence=" + receipt.actionSequence()
                + " localSequence=" + action.sequence
                + " originDeltaSquared=" + new Vec3d(receipt.originX(), receipt.originY(), receipt.originZ())
                .squaredDistanceTo(action.origin));
        return true;
    }

    private static boolean matchesNativeAction(final Action action,
                                               final PredictionPayloads.NativeAction receipt) {
        return action != null && receipt != null
                && action.kind == receipt.kind()
                && action.selectedSlot == receipt.selectedSlot()
                && action.inputAbility.equalsIgnoreCase(receipt.ability());
    }

    private Action findUnpairedNativeAction(final PredictionPayloads.NativeAction receipt) {
        if (receipt == null) return null;
        final Set<Long> paired = new HashSet<>(nativeActionAliases.values());
        long localFloor = 0L;
        for (Map.Entry<Long, Long> alias : nativeActionAliases.entrySet()) {
            if (alias.getKey() < receipt.actionSequence()) {
                localFloor = Math.max(localFloor, alias.getValue());
            }
        }
        Action closest = null;
        double closestScore = Double.POSITIVE_INFINITY;
        for (Action candidate : actions.values()) {
            if (candidate.sequence <= localFloor || paired.contains(candidate.sequence)
                    || !matchesNativeAction(candidate, receipt)) continue;
            final double score = nativePosePairingScore(candidate, receipt);
            if (score < closestScore) {
                closest = candidate;
                closestScore = score;
            }
        }
        return closest;
    }

    private long localActionSequence(final long paperSequence) {
        return mappedActionSequence(nativeActionAliases, paperSequence);
    }

    private long localAcknowledgedSequence(final long paperSequence) {
        return mappedAcknowledgedSequence(nativeActionAliases, paperSequence);
    }

    static long mappedActionSequence(final Map<Long, Long> aliases, final long paperSequence) {
        if (aliases == null || paperSequence <= 0L) return 0L;
        return aliases.getOrDefault(paperSequence, 0L);
    }

    static long mappedAcknowledgedSequence(final Map<Long, Long> aliases, final long paperSequence) {
        if (aliases == null || paperSequence <= 0L) return 0L;
        long acknowledged = 0L;
        for (Map.Entry<Long, Long> alias : aliases.entrySet()) {
            if (alias.getKey() <= paperSequence) acknowledged = Math.max(acknowledged, alias.getValue());
        }
        return acknowledged;
    }

    /**
     * Pairs diagnostic receipts by the packet-time pose they actually used.
     * FIFO is insufficient because a locally observed animation can be
     * discarded before Bukkit raises PlayerAnimationEvent; that leaves one
     * unmatched local AirBlast and shifts every later FIFO comparison back by
     * one cast. The corresponding action normally has an exact zero score.
     */
    private static double nativePosePairingScore(final Action local,
                                                 final PredictionPayloads.NativeAction paper) {
        if (local == null || paper == null) return Double.POSITIVE_INFINITY;
        final double dx = local.origin.x - paper.originX();
        final double dy = local.origin.y - paper.originY();
        final double dz = local.origin.z - paper.originZ();
        final double yaw = AirBlastTraceSync.signedAngleDelta(local.yaw, paper.yaw());
        final double pitch = local.pitch - paper.pitch();
        return dx * dx + dy * dy + dz * dz + yaw * yaw + pitch * pitch;
    }

    private static String nativeActionMismatch(final Action local,
                                               final PredictionPayloads.NativeAction paper) {
        if (local == null) return "no local action at or after Paper sequence " + paper.actionSequence();
        return "paper=" + paper.actionSequence() + "/" + paper.kind() + "/slot" + paper.selectedSlot()
                + "/" + paper.ability() + " local=" + local.sequence + "/" + local.kind
                + "/slot" + local.selectedSlot + "/" + local.inputAbility;
    }

    private void aliasLocalAirBlastTraces(final long paperSequence, final long localSequence) {
        if (paperSequence <= 0L || localSequence <= 0L || paperSequence == localSequence) return;
        final List<Map.Entry<AirBlastTraceKey, AirBlastTraceSync.Trace>> snapshot =
                List.copyOf(localAirBlastTraces.entrySet());
        for (Map.Entry<AirBlastTraceKey, AirBlastTraceSync.Trace> entry : snapshot) {
            if (entry.getKey().actionSequence() != localSequence) continue;
            final AirBlastTraceKey alias = new AirBlastTraceKey(
                    paperSequence, entry.getKey().eventOrdinal());
            localAirBlastTraces.put(alias, entry.getValue());
            compareAirBlastTrace(alias);
        }
    }

    private void noteAirBlastTrace0(final PredictionPayloads.AirBlastTraceReceipt receipt) {
        if (!ready || receipt == null || receipt.actionSequence() <= 0L) return;
        final AirBlastTraceKey key = new AirBlastTraceKey(receipt.actionSequence(), receipt.eventOrdinal());
        paperAirBlastTraces.put(key, receipt.trace());
        lastAirBlastTraceSequence = receipt.actionSequence();
        trimAirBlastTraces();
        compareAirBlastTrace(key);
    }

    private void compareAirBlastTrace(final AirBlastTraceKey key) {
        if (key == null) return;
        final AirBlastTraceSync.Trace local = localAirBlastTraces.get(key);
        final AirBlastTraceSync.Trace paper = paperAirBlastTraces.get(key);
        if (local == null || paper == null) return;
        final String difference = AirBlastTraceSync.firstDifference(local, paper);
        if (!difference.isEmpty()) {
            airBlastTraceDifferences.putIfAbsent(key.actionSequence(),
                    "event=" + key.eventOrdinal() + " " + difference);
        }
        lastComparedLocalAirBlastTrace = local;
        lastComparedPaperAirBlastTrace = paper;
        lastComparedAirBlastTraceSequence = key.actionSequence();
        if (difference.isEmpty()) {
            debug("AirBlast parity matched sequence=" + key.actionSequence()
                    + " " + AirBlastTraceSync.describe(local));
        } else {
            debug("AirBlast parity failed sequence=" + key.actionSequence() + " " + difference
                    + " local={" + AirBlastTraceSync.describe(local) + "}"
                    + " paper={" + AirBlastTraceSync.describe(paper) + "}");
        }
    }

    private void compareLatestAirBlastTrace(final long sequence) {
        paperAirBlastTraces.keySet().stream()
                .filter(key -> key.actionSequence() == sequence)
                .forEach(this::compareAirBlastTrace);
    }

    private List<String> airBlastParityReport0() {
        if (lastAirBlastTraceSequence <= 0L) {
            return List.of("AirBlast parity: no staged launch has been captured yet");
        }
        final long sequence = lastAirBlastTraceSequence;
        final String input = airBlastInputDifferences.get(sequence);
        final String trace = airBlastTraceDifferences.get(sequence);
        final Long mappedLocalSequence = airBlastSequenceAliases.get(sequence);
        final long localEvents = localAirBlastTraces.keySet().stream()
                .filter(key -> key.actionSequence() == sequence).count();
        final long paperEvents = paperAirBlastTraces.keySet().stream()
                .filter(key -> key.actionSequence() == sequence).count();
        final Set<Integer> localOrdinals = new TreeSet<>();
        final Set<Integer> paperOrdinals = new TreeSet<>();
        localAirBlastTraces.keySet().stream().filter(key -> key.actionSequence() == sequence)
                .map(AirBlastTraceKey::eventOrdinal).forEach(localOrdinals::add);
        paperAirBlastTraces.keySet().stream().filter(key -> key.actionSequence() == sequence)
                .map(AirBlastTraceKey::eventOrdinal).forEach(paperOrdinals::add);
        final List<String> report = new ArrayList<>();
        if (input == null) {
            report.add("AirBlast parity seq=" + sequence + ": awaiting Paper native input; localEvents="
                    + localEvents + " paperEvents=" + paperEvents);
            report.add("Native mapping: paperSeq=" + sequence + " localSeq=" + mappedLocalSequence
                    + " detail=" + airBlastNativeRejections.getOrDefault(sequence, "no receipt mapping"));
            report.add("Recent local AirBlast traces: " + recentAirBlastTraceSequences(localAirBlastTraces));
            report.add("Recent local actions: " + recentAirBlastActions());
        } else if (!input.isEmpty()) {
            report.add("AirBlast parity FAIL seq=" + sequence + " input " + input);
        } else if (trace != null) {
            report.add("AirBlast parity FAIL seq=" + sequence + " " + trace);
        } else if (paperEvents < localEvents) {
            report.add("AirBlast parity seq=" + sequence + ": input MATCH; awaiting Paper trace; localEvents="
                    + localEvents + " paperEvents=" + paperEvents);
        } else if (localEvents < paperEvents) {
            report.add("AirBlast parity FAIL seq=" + sequence
                    + " local lifecycle ended before Paper; localEvents=" + localEvents
                    + " paperEvents=" + paperEvents);
            report.add("Local events: " + airBlastTimeline(localAirBlastTraces, sequence));
            report.add("Paper events: " + airBlastTimeline(paperAirBlastTraces, sequence));
            report.add("Active local AirBlast: " + activeAirBlastSummary());
        } else if (!localOrdinals.equals(paperOrdinals)) {
            final Set<Integer> missingLocal = new TreeSet<>(paperOrdinals);
            missingLocal.removeAll(localOrdinals);
            final Set<Integer> missingPaper = new TreeSet<>(localOrdinals);
            missingPaper.removeAll(paperOrdinals);
            report.add("AirBlast parity FAIL seq=" + sequence + " unmatched events; missingLocal="
                    + missingLocal + " missingPaper=" + missingPaper);
        } else {
            report.add("AirBlast parity MATCH seq=" + sequence + " through " + localEvents
                    + " launch/progress events");
        }
        if (mappedLocalSequence != null && mappedLocalSequence != sequence) {
            report.add("Native sequence pairing: Paper=" + sequence + " local=" + mappedLocalSequence);
        }
        if (lastComparedAirBlastTraceSequence == sequence
                && lastComparedLocalAirBlastTrace != null && lastComparedPaperAirBlastTrace != null) {
            report.add("Local: " + AirBlastTraceSync.describe(lastComparedLocalAirBlastTrace));
            report.add("Paper: " + AirBlastTraceSync.describe(lastComparedPaperAirBlastTrace));
        }
        return List.copyOf(report);
    }

    private static String airBlastTimeline(
            final Map<AirBlastTraceKey, AirBlastTraceSync.Trace> traces,
            final long sequence) {
        return traces.entrySet().stream()
                .filter(entry -> entry.getKey().actionSequence() == sequence)
                .sorted(Map.Entry.comparingByKey((left, right) ->
                        Integer.compare(left.eventOrdinal(), right.eventOrdinal())))
                .map(entry -> entry.getKey().eventOrdinal() + ":" + entry.getValue().phase()
                        + "@" + entry.getValue().progressTick()
                        + "[" + entry.getValue().blockX() + "," + entry.getValue().blockY()
                        + "," + entry.getValue().blockZ() + ","
                        + entry.getValue().blockMaterial() + "]")
                .toList().toString();
    }

    private static String recentAirBlastTraceSequences(
            final Map<AirBlastTraceKey, AirBlastTraceSync.Trace> traces) {
        final Map<Long, List<String>> recent = new LinkedHashMap<>();
        for (Map.Entry<AirBlastTraceKey, AirBlastTraceSync.Trace> entry : traces.entrySet()) {
            recent.computeIfAbsent(entry.getKey().actionSequence(), ignored -> new ArrayList<>())
                    .add(entry.getKey().eventOrdinal() + ":" + entry.getValue().phase());
        }
        while (recent.size() > 6) recent.remove(recent.keySet().iterator().next());
        return recent.toString();
    }

    private String recentAirBlastActions() {
        final List<String> recent = new ArrayList<>();
        for (Action action : actions.values()) {
            if (!"AirBlast".equalsIgnoreCase(action.inputAbility)) continue;
            recent.add(action.sequence + ":" + action.kind + "/slot" + action.selectedSlot
                    + "/predicted=" + action.locallyPredicted + "/confirmed=" + action.nativeConfirmed);
        }
        return recent.subList(Math.max(0, recent.size() - 8), recent.size()).toString();
    }

    private static String firstInputPoseDifference(final Action local,
                                                   final PredictionPayloads.NativeAction paper) {
        String difference;
        if (!(difference = inputDecimal("eyeX", local.origin.x, paper.originX(), 1.0E-9)).isEmpty()) return difference;
        if (!(difference = inputDecimal("eyeY", local.origin.y, paper.originY(), 1.0E-9)).isEmpty()) return difference;
        if (!(difference = inputDecimal("eyeZ", local.origin.z, paper.originZ(), 1.0E-9)).isEmpty()) return difference;
        if (!(difference = inputAngle("yaw", local.yaw, paper.yaw(), 1.0E-5)).isEmpty()) return difference;
        return inputDecimal("pitch", local.pitch, paper.pitch(), 1.0E-5);
    }

    private static String inputAngle(final String field, final double local, final double paper,
                                     final double epsilon) {
        if (Double.doubleToLongBits(local) == Double.doubleToLongBits(paper)) return "";
        if (Double.isNaN(local) && Double.isNaN(paper)) return "";
        if (Double.isFinite(local) && Double.isFinite(paper)) {
            final double delta = AirBlastTraceSync.signedAngleDelta(local, paper);
            if (Math.abs(delta) <= epsilon) return "";
            return String.format(Locale.ROOT, "%s local=%.12f paper=%.12f angularDelta=%.12f",
                    field, local, paper, delta);
        }
        return String.format(Locale.ROOT, "%s local=%.12f paper=%.12f angularDelta=%.12f",
                field, local, paper, local - paper);
    }

    private static String inputDecimal(final String field, final double local, final double paper,
                                       final double epsilon) {
        if (Double.doubleToLongBits(local) == Double.doubleToLongBits(paper)
                || Double.isFinite(local) && Double.isFinite(paper) && Math.abs(local - paper) <= epsilon) return "";
        return String.format(Locale.ROOT, "%s local=%.12f paper=%.12f delta=%.12f",
                field, local, paper, local - paper);
    }

    private void trimAirBlastTraces() {
        while (localAirBlastTraces.size() > 128) {
            localAirBlastTraces.remove(localAirBlastTraces.keySet().iterator().next());
        }
        while (paperAirBlastTraces.size() > 128) {
            paperAirBlastTraces.remove(paperAirBlastTraces.keySet().iterator().next());
        }
        while (airBlastInputDifferences.size() > 32) {
            airBlastInputDifferences.remove(airBlastInputDifferences.keySet().iterator().next());
        }
        while (airBlastTraceDifferences.size() > 32) {
            airBlastTraceDifferences.remove(airBlastTraceDifferences.keySet().iterator().next());
        }
        while (airBlastSequenceAliases.size() > 64) {
            airBlastSequenceAliases.remove(airBlastSequenceAliases.keySet().iterator().next());
        }
        while (airBlastNativeRejections.size() > 64) {
            airBlastNativeRejections.remove(airBlastNativeRejections.keySet().iterator().next());
        }
    }

    private void reconcile0(long sequence, Vec3d authoritativeOrigin,
                            String ability, long cooldownUntil) {
        final long localSequence = localActionSequence(sequence);
        Action action = actions.get(localSequence);
        if (action == null || !action.nativeConfirmed
                || ability != null && !ability.isBlank() && !action.inputAbility.equalsIgnoreCase(ability)) {
            debug("runtime reconcile missing action paperSequence=" + sequence
                    + " localSequence=" + localSequence + " ability=" + ability);
            return;
        }
        action.reconciled = true;
        if (cooldownUntil > System.currentTimeMillis() && ability != null && !ability.isBlank()) {
            enforceLocalCooldown(ability, cooldownUntil);
        }
        action.previousAbilityActions.clear();
        // The time from local execution to this confirmation is a direct
        // measurement of how far the client simulation runs ahead of the server.
        // Ordinary (non-TempBlock) client block mutations use that measurement
        // as their bounded confirmation window. Common TempBlocks bypass this
        // ledger completely and own their local lifecycle.
        action.blockConfirmationTicks = Math.max(MIN_ACTION_BLOCK_CONFIRMATION_TICKS,
                Math.min(BLOCK_CONFIRMATION_TICKS,
                        (int) Math.max(0L, tick - action.createdTick)
                                + ACTION_BLOCK_CONFIRMATION_MARGIN_TICKS));
        debug("runtime reconcile confirmed paperSequence=" + sequence + " localSequence=" + localSequence
                + " ability=" + ability
                + " originDeltaSquared=" + authoritativeOrigin.squaredDistanceTo(action.origin));
    }

    /**
     * Disposes only the partial state left by an exception thrown while the
     * local input is still executing. This is not an authority response and
     * is never reached from reconcile or TempBlock metadata.
     */
    private void abortFailedLocalInput(final Action action) {
        debug("runtime abort failed local input sequence=" + action.sequence + " abilities=" + action.abilities.size()
                + " spawned=" + action.spawned.size());
        for (CoreAbility ability : List.copyOf(action.abilities)) {
            try {
                forceRemoveAbility(ability);
            } catch (Throwable ignored) { }
        }
        action.abilities.clear();

        // Existing-instance inputs temporarily replace the ability's previous
        // action association. Put it back when local execution itself aborts.
        for (Map.Entry<CoreAbility, Long> entry : action.previousAbilityActions.entrySet()) {
            final CoreAbility ability = entry.getKey();
            if (!Objects.equals(abilityActions.get(ability), action.sequence)) continue;
            if (entry.getValue() == null) abilityActions.remove(ability);
            else abilityActions.put(ability, entry.getValue());
        }
        action.previousAbilityActions.clear();

        for (Entity entity : action.spawned) {
            if (entity != null && !entity.isRemoved()) entity.discard();
        }
        tempFallingEntityAliases.removeIf(id -> action.spawned.contains(authoritativeEntityAliases.get(id)));
        authoritativeEntityAliases.entrySet().removeIf(entry -> action.spawned.contains(entry.getValue()));
        action.spawned.forEach(predictedSpawnOrigins::remove);
        predictedTempFallingBlocks.keySet().removeIf(key -> key.actionSequence == action.sequence);
        serverTempFallingPrepares.keySet().removeIf(key -> key.actionSequence == action.sequence);
        preparedFallingEntityIds.entrySet().removeIf(entry -> entry.getValue().actionSequence == action.sequence);
        action.spawned.clear();
        blocks.entrySet().removeIf(entry -> {
            BlockMutation mutation = entry.getValue();
            if (mutation.lastAction != action.sequence) return false;
            return true;
        });
        for (int i = velocities.size() - 1; i >= 0; i--) {
            VelocityMutation mutation = velocities.get(i);
            if (mutation.action != action.sequence) continue;
            Entity entity = mutation.world.getEntityById(mutation.entityId);
            if (entity != null && close(entity.getVelocity(), mutation.predicted, .08)) entity.setVelocity(mutation.before);
            velocities.remove(i);
        }
    }

    private int blockConfirmationTicks(final long actionSequence) {
        final Action action = actions.get(actionSequence);
        return action == null || !action.reconciled
                ? BLOCK_CONFIRMATION_TICKS : action.blockConfirmationTicks;
    }

    private void stop0(MinecraftClient client) {
        if (!commonRuntimeInstalled) {
            // PredictionClient resets once on every initial join, before the
            // authenticated snapshot installs the client prediction platform.
            // Do not touch any common gameplay class here. In particular,
            // invoking the elemental discard helpers initializes their
            // ElementalAbility superclass; doing that before Platform.install
            // permanently poisons the class for this JVM with an
            // ExceptionInInitializerError/NoClassDefFoundError.
            debug("runtime stop skipped before common startup actions=" + actions.size());
            if (platform != null) {
                try { platform.close(); } catch (Throwable ignored) { }
                platform = null;
            }
            grantedPermissions = Set.of();
            return;
        }
        debug("runtime stop ready=" + ready + " initializing=" + initializing
                + " activeInstances=" + CoreAbility.getAbilitiesByInstances().size()
                + " actions=" + actions.size());
        // Run the normal common cleanup so every ability-side collection is
        // drained, but suppress all block writes while doing so. Merely
        // discarding TempBlock.LAYERS leaves PhaseChange, Torrent, Surge,
        // WaterArms, moved-earth and RevertChecker handles alive; the next
        // ClientWorld then progresses those stale handles and corrupts its
        // otherwise fresh TempBlock registry.
        discardingWorldState = true;
        try {
            if (ready || initializing) {
                try { GeneralMethods.stopBending(); } catch (Throwable ignored) { }
                try { EmbeddedAddonBootstrap.disable(); } catch (Throwable ignored) { }
                if (ProjectKorra.collisionManager != null) {
                    try { ProjectKorra.collisionManager.stopCollisionDetection(); } catch (Throwable ignored) { }
                    ProjectKorra.collisionManager = null;
                    ProjectKorra.collisionInitializer = null;
                }
                if (managersStarted) {
                    try { Manager.shutdown(); } catch (Throwable ignored) { }
                    managersStarted = false;
                }
            }
            // These are callback-free finalizers. They also cover partial
            // startup and an addon whose remove() threw before super.remove().
            try { TempBlock.discardAll(); } catch (Throwable ignored) { }
            try { TempFallingBlock.discardAll(); } catch (Throwable ignored) { }
            try { CoreAbility.discardAllInstances(); } catch (Throwable ignored) { }
            try { AirAbility.discardAllAirbendingState(); } catch (Throwable ignored) { }
            try { EarthAbility.discardAllEarthbendingState(); } catch (Throwable ignored) { }
            try { WaterAbility.discardAllWaterbendingState(); } catch (Throwable ignored) { }
            try { FireAbility.discardAllFirebendingState(); } catch (Throwable ignored) { }
            try { RevertChecker.discardAll(); } catch (Throwable ignored) { }
            try { BlockSource.clearAll(); } catch (Throwable ignored) { }
            if (bendingPlayer != null) {
                RegionProtectionAuthority.clear(bendingPlayer.getPlayer());
                BendingPlayer.getPlayers().remove(bendingPlayer.getPlayer().getUniqueId());
                BendingPlayer.getOfflinePlayers().remove(bendingPlayer.getPlayer().getUniqueId());
            }
            if (platform != null) {
                try { platform.close(); } catch (Throwable ignored) { }
            }
            for (Action action : actions.values()) for (Entity entity : action.spawned) {
                if (entity != null && !entity.isRemoved()) {
                    try { entity.discard(); } catch (Throwable ignored) { }
                }
            }
            actions.clear(); abilityActions.clear(); abilityCreationActions.clear();
            abilityRemovalHistory.clear();
            tempBlockTeardownHistory.clear();
            authoritativeTempBlockHistory.clear();
            localAirBlastTraces.clear(); paperAirBlastTraces.clear();
            nativeActionAliases.clear();
            airBlastInputDifferences.clear(); airBlastTraceDifferences.clear();
            airBlastSequenceAliases.clear(); airBlastNativeRejections.clear();
            lastAirBlastTraceSequence = 0L;
            lastComparedAirBlastTraceSequence = 0L;
            lastComparedLocalAirBlastTrace = null;
            lastComparedPaperAirBlastTrace = null;
            authoritativeFlightAbilities = Set.of();
            authoritativeFlightSequence = -1L;
            grantedPermissions = Set.of();
            blocks.clear(); predictedDirectBlocks.clear(); predictedDirectCauses.clear();
            recentDirectVisuals.clear(); directVisualRevision = 0L;
            confirmedDirectBlockPackets.clear();
            serverDirectBlockMasks.clear();
            serverTempBlocks.clear(); clientTempBlockActions.clear(); clientTempBlockEffects.clear();
            pendingTempBlockUnderlays.clear();
            authoritativeTempBlockLayers.clear(); authoritativeTempBlockEffects.clear();
            pairedServerTempBlocks.clear(); pairedTempBlockCoordinates.clear();
            completedTempBlockRestores.clear();
            tempBlockTeardownFences.clear();
            velocities.clear(); velocityReceipts.clear(); externalVelocityFence.clear();
            abilityStates.clear(); abilityStateReceipts.clear(); experiences.clear(); authoritativeEntityAliases.clear();
            tempFallingEntityAliases.clear();
            hiddenTempFallingEntities.clear();
            predictedSpawnOrigins.clear();
            predictedTempFallingBlocks.clear();
            serverTempFallingPrepares.clear();
            preparedFallingEntityIds.clear();
            observedFallingBlockSpawns.clear();
            TempBlockSync.clear(this);
            TempFallingBlockSync.clear(this);
            CooldownSync.clear(this);
            cooldownAuthority.clear();
            platform = null; bendingManager = null; bendingPlayer = null; ready = false; managersStarted = false;
            commonRuntimeInstalled = false;
            debug("runtime stopped");
        } finally {
            discardingWorldState = false;
        }
    }

    @Override
    public boolean isAuthoritative() {
        return false;
    }

    @Override
    public void onChange(final TempBlockSync.Change change) {
        if (change == null || change.block() == null) return;
        final BlockKey key = clientBlockKey(change.block());
        if (change.operation() == TempBlockSync.Operation.REVERT
                || change.operation() == TempBlockSync.Operation.DISCARD) {
            pendingTempBlockUnderlays.remove(change.layerId());
            final LocalTempBlockPrediction local = clientTempBlockActions.get(change.layerId());
            if (local != null) {
                if (local.serverClosed) {
                    // Authority already completed this semantic lifecycle.
                    // The client was intentionally allowed to finish on its
                    // own clock, so its normal close needs no tombstone. A
                    // queued physical-close packet can still follow the
                    // metadata, however; make that one-shot fence reveal the
                    // lifecycle's actual final state rather than its former
                    // active state.
                    final BlockState finalState = materialState(TempBlockSync.encode(change.data()));
                    updateCompletedTempBlockRestores(change.layerId(), local.key, finalState);
                    detachLocalTempBlock(change.layerId());
                    return;
                }
                // Keep a tombstone even when CREATE metadata has not arrived
                // yet. A short-lived client water/ice layer can finish before
                // Paper receives the input; replaying Paper's delayed physical
                // lifecycle would otherwise cause a visible reconsolidation.
                local.closed = true;
                local.closedTick = tick;
                local.closedRevision = change.revision();
                local.closedState = materialState(TempBlockSync.encode(change.data()));
                updateCompletedTempBlockRestores(change.layerId(), local.key, local.closedState);
                debug("runtime retained predicted TempBlock close layer=" + change.layerId()
                        + " effect=" + local.effect + " pos=" + local.key.pos);
            }
            return;
        }
        long actionSequence = currentAction();
        if (actionSequence <= 0L && change.ability() != null) {
            actionSequence = abilityActions.getOrDefault(change.ability(), 0L);
        }
        if (actionSequence > 0L && key != null) {
            LocalTempBlockPrediction local = clientTempBlockActions.get(change.layerId());
            final BlockState createdState = materialState(TempBlockSync.encode(change.data()));
            final BlockState pendingUnderlay = pendingTempBlockUnderlays.remove(change.layerId());
            final BlockState capturedUnderlay = change.underlayData() == null
                    ? null : materialState(TempBlockSync.encode(change.underlayData()));
            final BlockState initialUnderlay = capturedUnderlay == null ? pendingUnderlay : capturedUnderlay;
            if (local == null) {
                String effectAbility = change.effectAbility();
                long effectStep = change.effectStep();
                int effectOrdinal = change.effectOrdinal();
                final Action action = actions.get(actionSequence);
                if (action != null) {
                    if (effectAbility == null || effectAbility.isBlank()) {
                        effectAbility = change.ability() == null
                                ? action.inputAbility : change.ability().getName();
                    }
                    effectStep = 0L;
                    effectOrdinal = ++action.tempBlockOrdinal;
                }
                final TempBlockEffectKey effect = effectKey(actionSequence, effectAbility,
                        effectStep, effectOrdinal);
                local = new LocalTempBlockPrediction(actionSequence, key, effect, tick,
                        createdState, initialUnderlay, change.ability());
                clientTempBlockActions.put(change.layerId(), local);
                if (effect != null) clientTempBlockEffects.putIfAbsent(effect, change.layerId());
            } else if (createdState != null) {
                // TempBlock#setType publishes another CREATE for the same
                // semantic layer. Keep every exact state which that lifecycle
                // can leave behind, including levelled water/fire variants.
                local.createdStates.add(createdState);
            }
            tryMatchLocalTempBlock(change.layerId(), local);
        }
    }

    @Override
    public void beforeWorldChange(final TempBlockSync.Change change) {
        if (change == null || change.block() == null) return;
        final BlockKey key = clientBlockKey(change.block());
        if (key == null) return;
        if (change.operation() == TempBlockSync.Operation.CREATE) {
            pendingTempBlockUnderlays.putIfAbsent(change.layerId(), key.world.getBlockState(key.pos));
            return;
        }
        if (change.operation() != TempBlockSync.Operation.DISCARD) return;

        // removeBlockBeforeWrite deliberately performs no captured-state
        // world write. Close the local semantic layer here so a shifted Paper
        // handoff cannot leave its old visual behind at the client coordinate.
        final LocalTempBlockPrediction local = clientTempBlockActions.get(change.layerId());
        if (local == null) {
            pendingTempBlockUnderlays.remove(change.layerId());
            return;
        }
        local.closed = true;
        local.closedTick = tick;
        local.closedRevision = change.revision();
        final BlockState capturedUnderlay = change.underlayData() == null
                ? null : materialState(TempBlockSync.encode(change.underlayData()));
        local.closedState = local.authoritativeUnderlay != null
                ? local.authoritativeUnderlay
                : capturedUnderlay != null ? capturedUnderlay : local.initialUnderlay;
        updateCompletedTempBlockRestores(change.layerId(), local.key, local.closedState);
        if (local.closedState != null) {
            local.key.world.setBlockState(local.key.pos, local.closedState, 19);
        }
        debug("runtime closed external TempBlock handoff layer=" + change.layerId()
                + " clientPos=" + local.key.pos);
    }

    private BlockKey clientBlockKey(final com.projectkorra.projectkorra.platform.mc.block.Block block) {
        final ClientWorld world = MinecraftClient.getInstance().world;
        if (block == null || block.getWorld() == null || world == null
                || !matchesWorld(world.getRegistryKey().getValue().toString(), block.getWorld().getName())) return null;
        return new BlockKey(world, new BlockPos(block.getX(), block.getY(), block.getZ()).toImmutable());
    }

    @Override
    public boolean hasAuthoritativeLayer(final com.projectkorra.projectkorra.platform.mc.block.Block block) {
        return topAuthoritativeTempBlock(clientBlockKey(block)) != null;
    }

    @Override
    public String authoritativeEffectAbility(final com.projectkorra.projectkorra.platform.mc.block.Block block) {
        final ServerTempBlockPrediction top = topAuthoritativeTempBlock(clientBlockKey(block));
        return top == null ? "" : top.effectAbility;
    }

    private ServerTempBlockPrediction topAuthoritativeTempBlock(final BlockKey key) {
        if (key == null) return null;
        long newestLayer = Long.MIN_VALUE;
        ServerTempBlockPrediction newest = null;
        for (Map.Entry<Long, ServerTempBlockPrediction> entry : authoritativeTempBlockLayers.entrySet()) {
            final ServerTempBlockPrediction candidate = entry.getValue();
            if (candidate == null || !key.equals(candidate.key)
                    || !serverTempBlocks.containsLayer(key, entry.getKey())
                    || entry.getKey() <= newestLayer) continue;
            newestLayer = entry.getKey();
            newest = candidate;
        }
        return newest;
    }

    @Override
    public void onAdded(CoreAbility source, BendingPlayer player, String ability, long expiresAtMillis) {
        if (player != bendingPlayer || ability == null || ability.isBlank()) return;
        cooldownAuthority.onLocalAdded(ability, expiresAtMillis);
    }

    @Override
    public void onRemoved(BendingPlayer player, String ability) {
        if (player != bendingPlayer || ability == null) return;
        cooldownAuthority.onLocalRemoved(ability);
    }

    /**
     * Applies a local common TempBlock directly. TempBlocks own their complete
     * client lifecycle and must never enter ordinary direct-write tracking.
     */
    private void setTempBlock0(final ClientWorld world, final BlockPos pos, final BlockState state) {
        if (world == null || pos == null || state == null) return;
        final BlockKey key = new BlockKey(world, pos);
        blocks.remove(key);
        final TempBlockSync.WorldMutation mutation = TempBlockSync.currentWorldMutation();
        if (mutation != null && mutation.operation() == TempBlockSync.Operation.REVERT
                && clientTempBlockState(world, pos) == null) {
            // The final local TempBlock layer has exposed its base. Keep the
            // direct-Earth owner view underneath the temporary stack aligned
            // without ever treating a temporary top layer as that base.
            updateServerDirectViewer(key, state, null, null);
        }
        BlockState visibleState = state;
        if (showServerTempBlocks) {
            visibleState = serverTempBlocks.physicalState(key).orElse(visibleState);
        }
        final ClientPlayerEntity localPlayer = MinecraftClient.getInstance().player;
        if (!showServerTempBlocks && localPlayer != null && hidesServerTempBlock(key)) {
            visibleState = serverTempBlocks.overlayState(key, localPlayer.getUuid()).orElse(visibleState);
        }
        world.setBlockState(pos, visibleState, 19);
        debug("runtime applied client TempBlock directly pos=" + pos + " state=" + visibleState);
    }

    private void setBlock0(ClientWorld world, BlockPos pos, BlockState state) {
        if (!ready || discardingWorldState || world == null || pos == null || state == null) return;
        final long actionSequence = currentAction();
        final Action action = actions.get(actionSequence);
        final BlockKey key = new BlockKey(world, pos);
        final BlockState before = simulatedBlockState0(world, pos);
        CoreAbility ability = AbilityExecutionContext.current();
        final DirectBlockSync.EarthLifecycle lifecycle = DirectBlockSync.currentEarthLifecycle();
        final long causalSequence = actionSequence > 0L ? actionSequence
                : lifecycle != null && lifecycle.valid() ? lifecycle.actionSequence() : 0L;
        String abilityName = ability != null ? ability.getName()
                : lifecycle != null && lifecycle.valid() ? lifecycle.ability()
                : action == null ? "" : action.inputAbility;
        final ClientPlayerEntity localPlayer = MinecraftClient.getInstance().player;
        DirectBlockEffectKey effect = null;
        DirectBlockCauseKey cause = null;
        if (causalSequence > 0L && DirectBlockSync.isPredictable(ability, abilityName)
                && (lifecycle == null || !lifecycle.valid() || localPlayer != null
                && localPlayer.getUuid().equals(lifecycle.ownerId()))) {
            final String normalized = abilityName.toLowerCase(Locale.ROOT);
            cause = new DirectBlockCauseKey(causalSequence, normalized);
            final PredictedDirectCause causeState = predictedDirectCauses.computeIfAbsent(cause,
                    ignored -> new PredictedDirectCause());
            // This is a semantic common-code ordinal, not a packet ordinal.
            // Advance it even when Fabric already has the requested state so
            // one asymmetric no-op cannot shift every later Paper receipt.
            final int ordinal = ++causeState.lastOrdinal;
            causeState.lastTick = tick;
            if (action != null) action.directBlockOrdinals.put(normalized, ordinal);
            effect = new DirectBlockEffectKey(causalSequence, normalized, ordinal);
        }

        if (effect != null) {
            updateLocalDirectView(key, before == null ? world.getBlockState(pos) : before,
                    state, cause, localPlayer == null ? null : localPlayer.getUuid());
        }

        // Equal full states remain physical no-ops: they paint nothing and
        // create no packet fence. Their semantic ordinal was still consumed
        // above to keep both common runtimes aligned.
        if (state.equals(before)) return;
        BlockMutation mutation = blocks.computeIfAbsent(key, ignored -> new BlockMutation(world, pos));
        mutation.lastAction = actionSequence;
        mutation.lastTick = tick;
        mutation.predicted = state;
        mutation.locallyPredicted = true;
        if (effect == null) return;
        final long visualRevision = ++directVisualRevision;
        predictedDirectBlocks.put(effect, new PredictedDirectBlock(
                key, before == null ? world.getBlockState(pos) : before, state, tick, visualRevision));
        recentDirectVisuals.put(key, new RecentDirectVisual(effect, state, tick, visualRevision));
        world.setBlockState(pos, state, 19);
        debug("runtime painted causal earth write effect=" + effect + " pos=" + pos + " state=" + state);
    }

    private BlockState simulatedBlockState0(final ClientWorld world, final BlockPos pos) {
        if (world == null || pos == null) return null;
        final BlockMutation mutation = blocks.get(new BlockKey(world, pos));
        if (mutation == null) return world.getBlockState(pos);
        final long action = currentAction();
        return action != 0L && action == mutation.lastAction
                ? mutation.predicted : world.getBlockState(pos);
    }

    private boolean authoritativeBlock0(ClientWorld world, BlockPos pos, BlockState state) {
        BlockKey key = new BlockKey(world, pos);
        final Optional<BlockState> teardownRestore = tempBlockTeardownFences.maskIncoming(key, state);
        if (teardownRestore.isPresent() && !showServerTempBlocks) {
            takeConfirmedDirectBlock(key, state);
            final BlockState retained = composeTempBlockTeardownView(key, teardownRestore.get());
            if (retained != null && !retained.equals(world.getBlockState(pos))) {
                world.setBlockState(pos, retained, 19);
            }
            blocks.remove(key);
            debug("runtime rejected late completed TempBlock state pos=" + pos
                    + " stale=" + state + " retained=" + retained);
            return true;
        }
        final CompletedTempBlockRestore completed = takeCompletedTempBlockRestore(key, state);
        if (completed != null) {
            takeConfirmedDirectBlock(key, state);
            final ServerDirectBlockMask direct = serverDirectMaskForIncoming(key, state);
            final BlockState retained = direct == null ? completed.state : direct.viewerState;
            world.setBlockState(pos, retained, 19);
            blocks.remove(key);
            debug("runtime hid completed physical TempBlock lifecycle pos=" + pos
                    + " state=" + retained);
            return true;
        }
        if (hidesServerTempBlock(key)) {
            takeConfirmedDirectBlock(key, state);
            debug("runtime hid physical server TempBlock update pos=" + pos + " state=" + state);
            return true;
        }
        final boolean serverTempPhysical = serverTempBlocks.physicalState(key)
                .filter(state::equals).isPresent();
        final ServerDirectBlockMask directMask = serverTempPhysical
                ? null : serverDirectMaskForIncoming(key, state);
        final BlockState directViewer = directMask == null ? null : directMask.viewerState;
        if (preserveClientTempBlockAuthority(key, directViewer == null ? state : directViewer)) {
            takeConfirmedDirectBlock(key, state);
            blocks.remove(key);
            debug("runtime rebased hidden client TempBlock underlay pos=" + pos
                    + " serverState=" + state + " viewerState="
                    + (directViewer == null ? state : directViewer));
            return true;
        }
        final ConfirmedDirectBlockPacket confirmed = takeConfirmedDirectBlock(key, state);
        if (confirmed != null) {
            final BlockState restore = directViewer == null
                    ? desiredConfirmedDirectState(confirmed) : directViewer;
            blocks.remove(key);
            if (restore != null && !restore.equals(state)) {
                world.setBlockState(pos, restore, 19);
                debug("runtime hid exactly-confirmed earth write pos=" + pos
                        + " serverState=" + state + " desired=" + restore);
                return true;
            }
            debug("runtime allowed no-op confirmed earth write pos=" + pos + " state=" + state);
            return false;
        }
        if (directViewer != null) {
            blocks.remove(key);
            if (!directViewer.equals(state)) {
                world.setBlockState(pos, directViewer, 19);
                debug("runtime hid repeated owned earth state pos=" + pos
                        + " serverState=" + state + " viewerState=" + directViewer);
                return true;
            }
            return false;
        }
        confirmDirectBlockFromVanilla(key, state);
        blocks.remove(key);
        return false;
    }

    private boolean authoritativeBlockBatch0(ClientWorld world, List<BlockPos> positions, List<BlockState> states) {
        if (!ready || world == null || positions == null || states == null || positions.isEmpty() || positions.size() != states.size()) return false;
        final boolean[] masked = new boolean[positions.size()];
        final BlockState[] retainedStates = new BlockState[positions.size()];
        int maskedEntries = 0;
        for (int i = 0; i < positions.size(); i++) {
            final BlockPos pos = positions.get(i).toImmutable();
            final BlockKey key = new BlockKey(world, pos);
            final BlockState incoming = states.get(i);
            final Optional<BlockState> teardownRestore =
                    tempBlockTeardownFences.maskIncoming(key, incoming);
            if (teardownRestore.isPresent() && !showServerTempBlocks) {
                masked[i] = true;
                retainedStates[i] = composeTempBlockTeardownView(key, teardownRestore.get());
                maskedEntries++;
                takeConfirmedDirectBlock(key, incoming);
                blocks.remove(key);
                continue;
            }
            final CompletedTempBlockRestore completed = takeCompletedTempBlockRestore(key, incoming);
            if (completed != null) {
                masked[i] = true;
                final ServerDirectBlockMask direct = serverDirectMaskForIncoming(key, incoming);
                retainedStates[i] = direct == null ? completed.state : direct.viewerState;
                maskedEntries++;
                takeConfirmedDirectBlock(key, incoming);
            } else if (hidesServerTempBlock(key)) {
                masked[i] = true;
                retainedStates[i] = desiredTempBlockState(key);
                maskedEntries++;
                takeConfirmedDirectBlock(key, incoming);
            } else {
                final boolean serverTempPhysical = serverTempBlocks.physicalState(key)
                        .filter(incoming::equals).isPresent();
                final ServerDirectBlockMask directMask = serverTempPhysical
                        ? null : serverDirectMaskForIncoming(key, incoming);
                final BlockState directViewer = directMask == null ? null : directMask.viewerState;
                if (preserveClientTempBlockAuthority(key,
                        directViewer == null ? incoming : directViewer)) {
                    masked[i] = true;
                    retainedStates[i] = desiredTempBlockState(key);
                    maskedEntries++;
                    takeConfirmedDirectBlock(key, incoming);
                    continue;
                }
                final ConfirmedDirectBlockPacket confirmed =
                        takeConfirmedDirectBlock(key, incoming);
                if (confirmed != null) {
                    final BlockState retained = directViewer == null
                            ? desiredConfirmedDirectState(confirmed) : directViewer;
                    if (retained != null && !retained.equals(incoming)) {
                        masked[i] = true;
                        retainedStates[i] = retained;
                        maskedEntries++;
                    } else {
                        blocks.remove(key);
                    }
                } else if (directViewer != null) {
                    blocks.remove(key);
                    if (!directViewer.equals(incoming)) {
                        masked[i] = true;
                        retainedStates[i] = directViewer;
                        maskedEntries++;
                    }
                } else {
                    confirmDirectBlockFromVanilla(key, incoming);
                    blocks.remove(key);
                }
            }
        }
        if (maskedEntries == 0) return false;

        // A chunk delta is one packet but ownership is per entry. Cancel the
        // packet as a whole, install every unrelated authoritative entry
        // ourselves, and leave owned entries on their client projection. This
        // prevents Paper's state from ever entering ClientWorld; there is no
        // post-packet rollback or one-frame server trail.
        for (int i = 0; i < positions.size(); i++) {
            final BlockPos pos = positions.get(i).toImmutable();
            final BlockState selected = masked[i] ? retainedStates[i] : states.get(i);
            if (selected != null && !world.getBlockState(pos).equals(selected)) {
                world.setBlockState(pos, selected, 19);
            }
        }
        debug("runtime masked owned chunk-delta entries=" + maskedEntries
                + " authoritativeEntries=" + (positions.size() - maskedEntries));
        return true;
    }

    /**
     * Consumes only the vanilla write announced by the corresponding
     * pre-mutation TempBlock metadata. Coordinates are frequently reused by
     * overlapping abilities in the same two-tick window, so consuming the
     * next arbitrary packet here would hide real FireBlast, HeatControl or
     * water/earth authority and manufacture a ghost block.
     */
    private CompletedTempBlockRestore takeCompletedTempBlockRestore(final BlockKey key,
                                                                     final BlockState receivedState) {
        final CompletedTempBlockRestore completed = completedTempBlockRestores.remove(key);
        if (completed == null) return null;
        if (completed.expectedState.equals(receivedState)) {
            final BlockState liveState = completed.followLiveClientState
                    ? clientTempBlockState(key.world, key.pos) : null;
            final BlockState retained = completedTempBlockRestoreState(
                    completed.followLiveClientState, liveState, completed.state);
            return retained == completed.state ? completed
                    : new CompletedTempBlockRestore(completed.expectedState, retained,
                    completed.followLiveClientState, completed.tick, completed.localLayerId);
        }
        debug("runtime released mismatched completed TempBlock fence pos=" + key.pos
                + " expected=" + completed.expectedState + " received=" + receivedState);
        return null;
    }

    static <T> T completedTempBlockRestoreState(final boolean followLiveClientState,
                                                 final T liveState, final T finalUnderlay) {
        return followLiveClientState && liveState != null ? liveState : finalUnderlay;
    }

    /**
     * Paper can close its paired layer before the locally predicted lifecycle
     * reaches the same common tick. If the client then closes before Paper's
     * vanilla packet is handled, update the one-shot repaint to the local
     * lifecycle's final state. Shifted server coordinates deliberately keep
     * their viewer underlay instead of copying a state from another block.
     */
    private void updateCompletedTempBlockRestores(final long localLayerId, final BlockKey localKey,
                                                   final BlockState finalState) {
        if (localLayerId <= 0L || localKey == null || finalState == null
                || completedTempBlockRestores.isEmpty()) return;
        completedTempBlockRestores.replaceAll((key, completed) ->
                completed.localLayerId == localLayerId && key.equals(localKey)
                        ? new CompletedTempBlockRestore(completed.expectedState, finalState,
                        completed.followLiveClientState, completed.tick, completed.localLayerId)
                        : completed);
    }

    private void acceptAuthoritativeChunk0(final ClientWorld world, final int chunkX, final int chunkZ) {
        if (!ready || world == null) return;
        final Set<BlockPos> preserved = new HashSet<>();
        final String worldName = FabricPredictionMC.world(world).getName();
        final Map<BlockKey, BlockState> teardownChunkStates = new LinkedHashMap<>();
        for (BlockKey key : tempBlockTeardownFences.keys()) {
            if (key == null || key.world != world || key.pos == null
                    || key.pos.getX() >> 4 != chunkX || key.pos.getZ() >> 4 != chunkZ) continue;
            // Save the freshly installed authoritative value before any owner
            // view below repaints this chunk.
            teardownChunkStates.put(key, world.getBlockState(key.pos));
        }

        // Full chunks are installed before this hook. Rebuild the durable
        // direct-Earth owner view first, then let local/remote TempBlock layers
        // compose above it below. A different unlayered state is external
        // authority and explicitly releases only that coordinate.
        for (Map.Entry<BlockKey, ServerDirectBlockMask> entry
                : List.copyOf(serverDirectBlockMasks.entrySet())) {
            final BlockKey key = entry.getKey();
            final ServerDirectBlockMask mask = entry.getValue();
            if (key.world != world || key.pos.getX() >> 4 != chunkX || key.pos.getZ() >> 4 != chunkZ) continue;
            final BlockState chunkState = world.getBlockState(key.pos);
            if (serverTempBlocks.physicalState(key).filter(chunkState::equals).isPresent()) continue;
            if (!mask.serverState.equals(chunkState)) {
                serverDirectBlockMasks.remove(key, mask);
                debug("runtime released owned earth view for external chunk state pos="
                        + key.pos + " expected=" + mask.serverState + " received=" + chunkState);
                continue;
            }
            if (!chunkState.equals(mask.viewerState)) {
                world.setBlockState(key.pos, mask.viewerState, 19);
            }
            preserved.add(key.pos);
        }

        // A full chunk is already installed when this hook runs. Apply the
        // same exact-state decision as individual/delta packets before render;
        // a third authoritative state releases only its coordinate.
        for (Map.Entry<BlockKey, BlockState> entry : teardownChunkStates.entrySet()) {
            final Optional<BlockState> retained =
                    tempBlockTeardownFences.maskIncoming(entry.getKey(), entry.getValue());
            if (retained.isEmpty() || showServerTempBlocks) continue;
            final BlockState desired = composeTempBlockTeardownView(entry.getKey(), retained.get());
            if (desired != null && !desired.equals(entry.getKey().world.getBlockState(entry.getKey().pos))) {
                entry.getKey().world.setBlockState(entry.getKey().pos, desired, 19);
            }
            preserved.add(entry.getKey().pos);
        }

        final Set<BlockPos> localCoordinates = new HashSet<>();
        for (TempBlock layer : TempBlock.getActiveLayers()) {
            com.projectkorra.projectkorra.platform.mc.block.Block block = layer.getBlock();
            if (block.getWorld() == null || !block.getWorld().getName().equals(worldName)
                    || block.getX() >> 4 != chunkX || block.getZ() >> 4 != chunkZ) continue;
            BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ()).toImmutable();
            if (!localCoordinates.add(pos)) continue;
            final BlockKey key = new BlockKey(world, pos);
            // Chunk data has already been installed. Preserve every running
            // client lifecycle, not just ones whose metadata happened to beat
            // this packet. For an unpaired coordinate, the chunk state becomes
            // the new hidden underlay which the TempBlock will reveal when its
            // own lifecycle closes.
            if (!hidesServerTempBlock(key)) rebaseClientTempBlockUnderlay(key, world.getBlockState(pos));
            preserved.add(pos);
            // Chunk data must use the same merged view as single and delta
            // updates. A newer remote/server-only layer can legitimately sit
            // above this locally predicted layer.
            final BlockState desired = desiredTempBlockState(key);
            if (desired != null) world.setBlockState(pos, desired, 19);
        }

        // A locally completed short-lived layer remains a paired tombstone
        // until Paper confirms the same close. Preserve its predicted underlay
        // across chunk snapshots so delayed server ice/water is not replayed.
        for (BlockKey key : List.copyOf(pairedTempBlockCoordinates.keySet())) {
            if (key.world != world || key.pos.getX() >> 4 != chunkX || key.pos.getZ() >> 4 != chunkZ
                    || preserved.contains(key.pos) || !hidesServerTempBlock(key)) continue;
            final BlockState desired = desiredTempBlockState(key);
            if (desired != null) world.setBlockState(key.pos, desired, 19);
            preserved.add(key.pos);
        }

        // Paper may create a different number/order of physical layers even
        // though it authoritatively attributes all of them to this client's
        // predicted action. Never expose those server coordinates merely
        // because an exact client layer ordinal did not exist. The client
        // lifecycle is the visual answer; Paper's viewer state is the hidden
        // underlay at coordinates the client did not use.
        for (ServerTempBlockPrediction server : List.copyOf(authoritativeTempBlockLayers.values())) {
            if (server == null || !server.hiddenForLocalViewer || server.key.world != world
                    || server.key.pos.getX() >> 4 != chunkX || server.key.pos.getZ() >> 4 != chunkZ
                    || preserved.contains(server.key.pos) || !hidesServerTempBlock(server.key)) continue;
            final BlockState desired = desiredTempBlockState(server.key);
            if (desired != null) world.setBlockState(server.key.pos, desired, 19);
            preserved.add(server.key.pos);
        }

        blocks.entrySet().removeIf(entry -> {
            final BlockKey key = entry.getKey();
            if (key.world != world || key.pos.getX() >> 4 != chunkX || key.pos.getZ() >> 4 != chunkZ) return false;
            return !preserved.contains(key.pos);
        });
    }

    private void applyTempBlockBatch0(ClientWorld world, PredictionPayloads.TempBlockBatch batch) {
        if (!ready || world == null || batch == null) return;
        debug("runtime temp-block batch serverTick=" + batch.serverTick() + " ops=" + batch.operations().size());
        final String worldName = world.getRegistryKey().getValue().toString();
        final ClientPlayerEntity localPlayer = MinecraftClient.getInstance().player;
        final UUID viewerId = localPlayer == null ? null : localPlayer.getUuid();
        for (PredictionPayloads.TempBlockOp operation : batch.operations()) {
            if (!matchesWorld(worldName, operation.world())) {
                recordAuthoritativeTempBlock("SKIP_WORLD snapshot=" + batch.snapshot()
                        + " operation=" + operation.operation() + " layer=" + operation.layerId()
                        + " operationWorld=" + operation.world() + " clientWorld=" + worldName);
                continue;
            }
            final BlockPos pos = new BlockPos(operation.x(), operation.y(), operation.z()).toImmutable();
            final BlockKey key = new BlockKey(world, pos);
            final BlockState worldBefore = world.getBlockState(pos);
            final TempBlockSync.Operation commonOperation = switch (operation.operation()) {
                case CREATE -> TempBlockSync.Operation.CREATE;
                case UPDATE_EXPIRY -> TempBlockSync.Operation.UPDATE_EXPIRY;
                case REVERT -> TempBlockSync.Operation.REVERT;
                case DISCARD -> TempBlockSync.Operation.DISCARD;
            };
            final BlockState physicalState = materialState(operation.material());
            final BlockState viewerState = materialState(operation.viewerMaterial());
            final boolean hiddenBefore = hidesServerTempBlock(key);
            final Long pairedLocalLayer = pairedServerTempBlocks.get(operation.layerId());
            // The action is useful only for semantic lifecycle pairing. The
            // authenticated owner carried by the endpoint is the visibility
            // boundary, so action expiry cannot reveal a long-lived layer.
            final boolean locallyOwned = viewerId != null && viewerId.equals(operation.ownerId());
            final long causalSequence = locallyOwned
                    ? localActionSequence(operation.actionSequence()) : 0L;
            final boolean advanced = serverTempBlocks.apply(key, commonOperation,
                    operation.actionSequence(), operation.layerId(), operation.revision(), operation.ownerId(),
                    physicalState, viewerState);
            if (!advanced) continue;

            if (commonOperation == TempBlockSync.Operation.REVERT
                    || commonOperation == TempBlockSync.Operation.DISCARD) {
                final ServerTempBlockPrediction server = authoritativeTempBlockLayers.get(operation.layerId());
                final boolean hiddenClosingLayer = server != null && server.hiddenForLocalViewer;
                if (server != null && server.effect != null) {
                    authoritativeTempBlockEffects.remove(server.effect, operation.layerId());
                }
                unpairServerTempBlock(operation.layerId());
                authoritativeTempBlockLayers.remove(operation.layerId());
                BlockState completedRestore = null;
                long completedLocalLayer = 0L;
                boolean followLiveClientState = false;
                if (pairedLocalLayer != null) {
                    final LocalTempBlockPrediction local = clientTempBlockActions.get(pairedLocalLayer);
                    final TempBlock localLayer = findActiveTempBlock(pairedLocalLayer);
                    if (local != null && operation.packetExpected()) {
                        BlockState restore = local.key.equals(key)
                                ? clientTempBlockState(key.world, key.pos) : viewerState;
                        if (restore == null && local.key.equals(key)) {
                            restore = closedClientTempBlockState(key);
                            if (restore == null) {
                                restore = local.closedState != null ? local.closedState : viewerState;
                            }
                        }
                        if (restore != null) {
                            completedRestore = restore;
                            completedLocalLayer = pairedLocalLayer;
                            followLiveClientState = local.key.equals(key) && localLayer != null;
                        }
                    }
                    if (local != null && localLayer != null) {
                        // The client TempBlock is the visual authority. Paper
                        // closing its physical counterpart must not roll the
                        // local animation back or cut it short. Keep the exact
                        // local layer until its common lifecycle closes it.
                        local.serverClosed = true;
                    } else {
                        detachLocalTempBlock(pairedLocalLayer);
                    }
                }
                if (operation.packetExpected() && hiddenClosingLayer && completedRestore == null) {
                    // Concealment is action-owned, not ordinal-owned. A client
                    // and Paper may legitimately create a different number of
                    // overlapping layers at this coordinate while still
                    // simulating the same accepted input. In that case there
                    // is no exact semantic pair, but the following vanilla
                    // close packet must still reveal the client layer (or the
                    // viewer underlay), never a lower Paper-only layer.
                    final BlockState activeLocal = clientTempBlockState(key.world, key.pos);
                    followLiveClientState = activeLocal != null;
                    completedRestore = followLiveClientState
                            ? viewerState
                            : hidesServerTempBlock(key) ? desiredTempBlockState(key) : viewerState;
                }
                if (operation.packetExpected() && completedRestore != null) {
                    completedTempBlockRestores.put(key,
                            new CompletedTempBlockRestore(physicalState, completedRestore,
                                    followLiveClientState, tick,
                                    completedLocalLayer));
                }
                repaintAuthoritativeTempBlock(key, viewerState);
                recordAuthoritativeTempBlock("CLOSE snapshot=" + batch.snapshot()
                        + " operation=" + commonOperation + " layer=" + operation.layerId()
                        + " revision=" + operation.revision() + " owner=" + operation.ownerId()
                        + " localOwner=" + locallyOwned + " hidden=" + hiddenClosingLayer
                        + " pairedLocal=" + pairedLocalLayer + " packetExpected=" + operation.packetExpected()
                        + " effect=" + operation.effectAbility() + " pos=" + pos
                        + " physical=" + physicalState + " viewer=" + viewerState
                        + " world=" + worldBefore + "->" + world.getBlockState(pos));
                debug("runtime closed paired TempBlock operation=" + commonOperation
                        + " serverLayer=" + operation.layerId() + " localLayer=" + pairedLocalLayer
                        + " pos=" + pos);
                continue;
            }

            final TempBlockEffectKey effect = effectKey(causalSequence, operation.effectAbility(),
                    operation.effectStep(), operation.effectOrdinal());
            final ServerTempBlockPrediction previousServer = authoritativeTempBlockLayers.get(operation.layerId());
            // The endpoint only supplies ownerId when that player authenticated
            // this ability as locally supported. Hide the complete owned
            // lifecycle even when a progress-created layer no longer has an
            // input Action/ordinal to pair against.
            final boolean hiddenForLocalViewer = previousServer != null
                    ? previousServer.hiddenForLocalViewer
                    : viewerId != null && viewerId.equals(operation.ownerId());
            final ServerTempBlockPrediction server = new ServerTempBlockPrediction(
                    causalSequence, key, effect, operation.effectAbility(),
                    operation.ownerId(), physicalState, hiddenForLocalViewer);
            authoritativeTempBlockLayers.put(operation.layerId(), server);
            if (effect != null && viewerId != null && viewerId.equals(operation.ownerId())) {
                authoritativeTempBlockEffects.put(effect, operation.layerId());
                tryMatchServerTempBlock(operation.layerId(), server);
            }

            final boolean hiddenAfter = hidesServerTempBlock(key);
            if (hiddenAfter) {
                blocks.remove(key);
                world.setBlockState(pos, desiredTempBlockState(key), 19);
            } else if (!operation.packetExpected()) {
                // Snapshot and buried-layer metadata have no following vanilla
                // packet. Keep an existing client lifecycle visible and make
                // the authoritative physical top its hidden underlay; only an
                // otherwise unowned coordinate adopts that top immediately.
                if (preserveClientTempBlockAuthority(key, physicalState)) {
                    world.setBlockState(pos, desiredTempBlockState(key), 19);
                } else {
                    repaintAuthoritativeTempBlock(key, physicalState);
                }
            }
            recordAuthoritativeTempBlock("OPEN snapshot=" + batch.snapshot()
                    + " operation=" + commonOperation + " layer=" + operation.layerId()
                    + " revision=" + operation.revision() + " owner=" + operation.ownerId()
                    + " localOwner=" + locallyOwned + " causal=" + causalSequence
                    + " hidden=" + hiddenAfter + " pairedLocal="
                    + pairedServerTempBlocks.get(operation.layerId())
                    + " packetExpected=" + operation.packetExpected()
                    + " effect=" + operation.effectAbility() + " pos=" + pos
                    + " physical=" + physicalState + " viewer=" + viewerState
                    + " world=" + worldBefore + "->" + world.getBlockState(pos));
            debug("runtime recorded server TempBlock operation=" + commonOperation
                    + " layer=" + operation.layerId() + " revision=" + operation.revision()
                    + " effect=" + effect + " pos=" + pos + " paired=" + hiddenAfter
                    + " wasPaired=" + hiddenBefore);
        }
    }

    private void noteDirectBlock0(final Entity localPlayer,
                                  final PredictionPayloads.DirectBlockReceipt receipt) {
        if (!ready || localPlayer == null || receipt == null
                || receipt.actionSequence() <= 0L
                || !localPlayer.getUuid().equals(receipt.abilityOwner())) return;
        final ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null || !matchesWorld(world.getRegistryKey().getValue().toString(), receipt.world())) return;
        final long localSequence = localActionSequence(receipt.actionSequence());
        if (localSequence <= 0L) {
            debug("runtime allowed authoritative direct write without mapped action paperSequence="
                    + receipt.actionSequence() + " ability=" + receipt.ability());
            return;
        }
        final DirectBlockEffectKey effect = new DirectBlockEffectKey(localSequence,
                receipt.ability().toLowerCase(Locale.ROOT), receipt.mutationOrdinal());
        final DirectBlockCauseKey cause = new DirectBlockCauseKey(localSequence,
                receipt.ability().toLowerCase(Locale.ROOT));
        final PredictedDirectBlock local = predictedDirectBlocks.remove(effect);
        if (local == null && !receipt.movedEarthLifecycle()
                && !predictedDirectCauses.containsKey(cause)) {
            // No write from this causal Earth lifecycle has ever existed on
            // the client. A non-movement write may be a genuinely server-only
            // permanent branch. Moved-earth is explicitly stamped with this
            // client's accepted owner/action, so its exact server coordinate
            // is always an echo; Fabric's transaction remains the visual
            // answer even when the two runtimes produced different ordinals.
            debug("runtime allowed authoritative direct write without exact local effect=" + effect);
            return;
        }

        final BlockKey serverKey = new BlockKey(world,
                new BlockPos(receipt.x(), receipt.y(), receipt.z()).toImmutable());
        final BlockState serverState = materialState(receipt.material());
        final BlockState serverUnderlay = world.getBlockState(serverKey.pos);
        final RecentDirectVisual observedVisual = recentDirectVisuals.get(serverKey);
        final long observedVisualRevision = observedVisual == null ? 0L : observedVisual.revision;
        final ServerDirectBlockMask existingMask = serverDirectBlockMasks.get(serverKey);
        final BlockState viewerState = existingMask == null
                ? clientDirectBaseState(serverKey, serverUnderlay) : existingMask.viewerState;
        serverDirectBlockMasks.put(serverKey, new ServerDirectBlockMask(
                serverState, viewerState, cause, localPlayer.getUuid(), tick));
        if (local != null && (!local.key.equals(serverKey) || !local.after.equals(serverState))) {
            // The local common runtime is the visual answer. This receipt is a
            // fence for the following vanilla write, never permission to undo
            // the client coordinate or paint Paper's divergent coordinate.
            debug("runtime concealed divergent causal write effect=" + effect
                    + " clientPos=" + local.key.pos + " serverPos=" + serverKey.pos
                    + " clientState=" + local.after + " serverState=" + serverState);
        } else if (local == null && receipt.movedEarthLifecycle()) {
            debug("runtime concealed unmatched moved-earth physical write effect=" + effect
                    + " serverPos=" + serverKey.pos + " serverState=" + serverState);
        }
        // ServerWorld may collapse several writes to one coordinate in the
        // same tick into a single chunk-delta entry.  Metadata is emitted
        // before each attempted write, so keeping every receipt would leave
        // the intermediate ones armed after the one observable packet.  A
        // later, unrelated restore to the same state could then be swallowed
        // and become a ghost block (EarthBlast source focus is the common
        // case).  If an earlier packet was actually emitted it has already
        // consumed its receipt due to connection ordering; otherwise the
        // newest write is exactly the state Minecraft will flush.
        confirmedDirectBlockPackets.removeIf(packet ->
                packet.serverTick == receipt.serverTick() && packet.key.equals(serverKey));
        confirmedDirectBlockPackets.add(new ConfirmedDirectBlockPacket(
                receipt.serverTick(), serverKey, serverState, cause, localPlayer.getUuid(),
                local == null ? 0L : local.visualRevision, observedVisualRevision,
                serverUnderlay, tick));
    }

    private void updateLocalDirectView(final BlockKey key, final BlockState before,
                                       final BlockState viewerState,
                                       final DirectBlockCauseKey cause, final UUID ownerId) {
        if (key == null || before == null || viewerState == null || cause == null || ownerId == null) return;
        final ServerDirectBlockMask existing = serverDirectBlockMasks.get(key);
        serverDirectBlockMasks.put(key, new ServerDirectBlockMask(
                existing == null ? before : existing.serverState,
                viewerState, cause, ownerId, tick));
    }

    private void updateServerDirectViewer(final BlockKey key, final BlockState viewerState,
                                          final DirectBlockCauseKey cause, final UUID ownerId) {
        if (key == null || viewerState == null) return;
        final ServerDirectBlockMask existing = serverDirectBlockMasks.get(key);
        if (existing == null) return;
        serverDirectBlockMasks.put(key, new ServerDirectBlockMask(
                existing.serverState, viewerState,
                cause == null ? existing.cause : cause,
                ownerId == null ? existing.ownerId : ownerId,
                tick));
    }

    /**
     * Returns the hidden direct-Earth view only for the physical state it owns.
     * A different unlayered state is an external edit and releases the mask;
     * equality/proximity/age guesses are never used.
     */
    private ServerDirectBlockMask serverDirectMaskForIncoming(final BlockKey key,
                                                               final BlockState incoming) {
        final ServerDirectBlockMask mask = serverDirectBlockMasks.get(key);
        if (mask == null || incoming == null) return null;
        if (mask.serverState.equals(incoming)) return mask;
        serverDirectBlockMasks.remove(key, mask);
        debug("runtime released owned earth view for external state pos=" + key.pos
                + " expected=" + mask.serverState + " received=" + incoming);
        return null;
    }

    private static BlockState clientDirectBaseState(final BlockKey key, final BlockState fallback) {
        if (key == null || key.world == null || key.pos == null) return fallback;
        final TempBlock layer = TempBlock.get(FabricPredictionMC.block(key.world, key.pos));
        if (layer == null || layer.getState() == null || layer.getState().getBlockData() == null) return fallback;
        return materialState(TempBlockSync.encode(layer.getState().getBlockData()));
    }

    private boolean hasActiveClientDirectCause(final UUID ownerId, final DirectBlockCauseKey cause) {
        if (ownerId == null || cause == null) return false;
        for (Map.Entry<CoreAbility, Long> entry : abilityActions.entrySet()) {
            final CoreAbility ability = entry.getKey();
            if (entry.getValue() == cause.actionSequence && ability != null && !ability.isRemoved()
                    && ability.getName().equalsIgnoreCase(cause.ability)) return true;
        }
        return activeClientEarthCauses(ownerId).contains(cause);
    }

    private static Set<DirectBlockCauseKey> activeClientEarthCauses(final UUID ownerId) {
        final Set<DirectBlockCauseKey> causes = new HashSet<>();
        if (ownerId == null) return causes;
        for (Information information : EarthAbility.getMovedEarth().values()) {
            addEarthLifecycle(causes, information, ownerId);
        }
        for (Information information : EarthAbility.getTempAirLocations().values()) {
            addEarthLifecycle(causes, information, ownerId);
        }
        return causes;
    }

    private static void addEarthLifecycle(final Set<DirectBlockCauseKey> causes,
                                          final Information information, final UUID ownerId) {
        if (information == null || !ownerId.equals(information.getPredictionOwner())
                || information.getPredictionActionSequence() <= 0L
                || information.getPredictionAbility() == null) return;
        causes.add(new DirectBlockCauseKey(information.getPredictionActionSequence(),
                information.getPredictionAbility().toLowerCase(Locale.ROOT)));
    }

    private static boolean matchesEarthLifecycle(final Information information, final UUID ownerId,
                                                 final DirectBlockCauseKey cause) {
        return information != null
                && ownerId.equals(information.getPredictionOwner())
                && information.getPredictionActionSequence() == cause.actionSequence
                && information.getPredictionAbility() != null
                && information.getPredictionAbility().equalsIgnoreCase(cause.ability);
    }

    private ConfirmedDirectBlockPacket takeConfirmedDirectBlock(final BlockKey key,
                                                                 final BlockState state) {
        for (int index = 0; index < confirmedDirectBlockPackets.size(); index++) {
            final ConfirmedDirectBlockPacket packet = confirmedDirectBlockPackets.get(index);
            if (!packet.key.equals(key) || !packet.state.equals(state)) continue;
            confirmedDirectBlockPackets.remove(index);
            return packet;
        }
        return null;
    }

    /**
     * Selects a repaint only from causal client ownership. Never snapshot the
     * arbitrary block visible when the vanilla batch arrives: it may already
     * be an older Paper trail. A later local direct write wins; an active
     * moved-earth coordinate keeps its current common-runtime state; otherwise
     * reveal the state captured immediately before this exact server write was
     * announced.
     */
    private BlockState desiredConfirmedDirectState(final ConfirmedDirectBlockPacket packet) {
        if (packet == null || packet.key == null || packet.key.world == null) return null;
        final BlockState current = packet.key.world.getBlockState(packet.key.pos);
        if (current.equals(packet.state)) return current;

        final RecentDirectVisual recent = recentDirectVisuals.get(packet.key);
        if (recent != null && current.equals(recent.state)) {
            final boolean retainsExactOrLaterLocalEffect = packet.localVisualRevision > 0L
                    && recent.revision >= packet.localVisualRevision;
            final boolean changedAfterReceipt = recent.revision > packet.observedVisualRevision;
            if (retainsExactOrLaterLocalEffect || changedAfterReceipt) return recent.state;
        }
        if (hasActiveClientEarthCoordinate(packet.key, packet.ownerId, packet.cause)) {
            return current;
        }
        return packet.serverUnderlay;
    }

    private boolean hasActiveClientEarthCoordinate(final BlockKey key, final UUID ownerId,
                                                   final DirectBlockCauseKey cause) {
        if (key == null || ownerId == null || cause == null) return false;
        for (Map.Entry<com.projectkorra.projectkorra.platform.mc.block.Block, Information> entry
                : EarthAbility.getMovedEarth().entrySet()) {
            if (key.equals(clientBlockKey(entry.getKey()))
                    && matchesEarthLifecycle(entry.getValue(), ownerId, cause)) return true;
        }
        for (Information information : EarthAbility.getTempAirLocations().values()) {
            if (information != null && key.equals(clientBlockKey(information.getBlock()))
                    && matchesEarthLifecycle(information, ownerId, cause)) return true;
        }
        return false;
    }

    private void confirmDirectBlockFromVanilla(final BlockKey key, final BlockState state) {
        for (PredictedDirectBlock predicted : predictedDirectBlocks.values()) {
            if (predicted.key.equals(key) && predicted.after.equals(state)) {
                predicted.vanillaConfirmed = true;
            }
        }
    }

    private void expireUnconfirmedDirectBlocks() {
        // Expiry is bookkeeping only. Repainting the saved `before` state was
        // a rollback and resurrected EarthBlast/EarthShard source blocks after
        // their client lifecycle had already moved on.
        predictedDirectBlocks.entrySet().removeIf(entry ->
                tick - entry.getValue().createdTick
                        > blockConfirmationTicks(entry.getKey().actionSequence));
    }

    private void setVelocity0(Entity entity, Vec3d velocity) {
        if (!ready || entity == null || velocity == null || !finite(velocity)) return;
        if (isLocalPlayerEntity(entity.getId())
                && externalVelocityFence.blocksPredictedWrite(entity.getId())) {
            // Paper has already identified this entity's next impulse as an
            // external hit. A still-live Scooter/Jet instance can progress
            // once more before its ordered removal receipt is handled; that
            // late local write must not replace the server-owned knockback.
            debug("runtime suppressed late predicted velocity behind external authority entity="
                    + entity.getId() + " ability=" + currentAbilityName()
                    + " attempted=" + velocityString(velocity));
            return;
        }
        long actionSequence = currentAction();
        Action action = actions.get(actionSequence);
        int impulseOrdinal = action == null ? 0 : action.velocityOrdinals.merge(entity.getId(), 1, Integer::sum);
        String abilityName = currentAbilityName();
        if ("<none>".equals(abilityName) && action != null) abilityName = action.inputAbility;
        if (isLocalPlayerEntity(entity.getId())) {
            debug("runtime predicted velocity local action=" + actionSequence
                    + " ordinal=" + impulseOrdinal
                    + " ability=" + abilityName
                    + " before=" + velocityString(entity.getVelocity())
                    + " after=" + velocityString(velocity)
                    + " stamina=" + airBlastStamina()
                    + " activeAirBlasts=" + activeAirBlastSummary());
        }
        velocities.add(new VelocityMutation((ClientWorld) entity.getEntityWorld(), entity.getId(), actionSequence,
                impulseOrdinal, abilityName, tick, entity.getVelocity(), velocity));
        entity.setVelocity(velocity);
        entity.velocityDirty = true;
    }

    private boolean authoritativeVelocity0(int entityId, Vec3d velocity) {
        if (!ready || !finite(velocity)) return false;
        // Ownership is authoritative metadata from the server. The latest
        // receipt describes the vanilla velocity state that follows it; never
        // guess again from vector similarity or local call ordering.
        if (!velocityReceipts.isEmpty()) {
            // Custom payloads and vanilla velocity updates are emitted in
            // order. Consume the oldest ownership receipt: using the newest
            // one lets a backlog produced by high ping assign an old impulse
            // to a newer ability action.
            int receiptIndex = -1;
            for (int i = 0; i < velocityReceipts.size(); i++) {
                VelocityReceipt candidate = velocityReceipts.get(i);
                if (candidate.entityId == entityId) {
                    receiptIndex = i;
                    break;
                }
            }
            if (receiptIndex < 0) {
                return stageUnownedLocalVelocity(entityId, velocity, "unpaired-receipt");
            }
            VelocityReceipt receipt = velocityReceipts.remove(receiptIndex);
            MinecraftClient client = MinecraftClient.getInstance();
            boolean selfOwned = client.player != null && client.player.getUuid().equals(receipt.abilityOwner);
            if (selfOwned) {
                // This packet is positive proof that Paper still owns a newer
                // local velocity lifecycle. It resolves any older external
                // hit fence before exact self-echo reconciliation below.
                externalVelocityFence.release(entityId);
                for (int i = 0; i < velocities.size(); i++) {
                    VelocityMutation mutation = velocities.get(i);
                    if (mutation.entityId != entityId
                            || mutation.action != receipt.actionSequence
                            || mutation.impulseOrdinal != receipt.impulseOrdinal) continue;
                    // Acknowledge exactly one predicted write. Later writes
                    // from this action must remain available for their own
                    // delayed packets, especially after ownership transfers
                    // from a surf/spout to another movement ability.
                    velocities.remove(i);
                    debug("runtime suppressed exactly-owned authoritative velocity action="
                            + receipt.actionSequence + " ordinal=" + receipt.impulseOrdinal
                            + " ability=" + receipt.ability);
                    return true;
                }
                boolean superseded = velocities.stream().anyMatch(mutation -> mutation.entityId == entityId
                        && (mutation.action > receipt.actionSequence
                        || mutation.action == receipt.actionSequence
                        && mutation.impulseOrdinal > receipt.impulseOrdinal));
                if (superseded) {
                    debug("runtime suppressed superseded self-owned velocity action="
                            + receipt.actionSequence + " ordinal=" + receipt.impulseOrdinal
                            + " ability=" + receipt.ability);
                    return true;
                }
                // A receipt proves who owns the server write, but it does not
                // prove that Fabric applied a matching local write. Suppress
                // only an exact action+ordinal mutation (or one superseded by
                // a newer local mutation). Otherwise the vanilla absolute
                // velocity is the only push, as with a back-to-back AirBlast
                // whose local collision did not occur.
                debug("runtime allowed self-owned velocity without retained mutation action="
                        + receipt.actionSequence + " ordinal=" + receipt.impulseOrdinal
                        + " ability=" + receipt.ability);
                return false;
            }
            debug("runtime allowed externally-owned authoritative velocity owner=" + receipt.abilityOwner
                    + " action=" + receipt.actionSequence + " ability=" + receipt.ability);
            return stageExternalLocalVelocity(entityId, velocity, receipt.serverTick,
                    receipt.ability, "owned-external");
        }
        return stageUnownedLocalVelocity(entityId, velocity, "no-receipt");
    }

    private boolean stageUnownedLocalVelocity(final int entityId, final Vec3d velocity,
                                               final String reason) {
        final boolean staged = stageExternalLocalVelocity(entityId, velocity, tick,
                "<unowned>", reason);
        if (!staged) {
            debug("runtime allowed unowned authoritative velocity packet=" + velocityString(velocity)
                    + " reason=" + reason + " pendingReceipts=" + velocityReceipts.size());
        }
        return staged;
    }

    private boolean stageExternalLocalVelocity(final int entityId, final Vec3d velocity,
                                               final long serverTick, final String ability,
                                               final String reason) {
        final ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || player.getId() != entityId) return false;
        final boolean liveWriter = hasLivePredictedVelocityWriter(entityId);
        externalVelocityFence.receive(entityId, new PendingExternalVelocity(
                velocity, serverTick, ability, tick), liveWriter);
        debug("runtime staged authoritative external/unowned velocity after local progress entity="
                + entityId + " serverTick=" + serverTick + " ability=" + ability
                + " reason=" + reason + " liveWriter=" + liveWriter
                + " velocity=" + velocityString(velocity));
        return true;
    }

    private void applyPendingExternalVelocities(final ClientWorld world) {
        if (world == null) return;
        for (Integer entityId : externalVelocityFence.entityIds()) {
            final boolean wasCommitted = externalVelocityFence.isCommitted(entityId);
            final boolean retained = externalVelocityFence.isRetained(entityId);
            final Optional<PendingExternalVelocity> pending =
                    externalVelocityFence.afterLocalProgress(entityId, tick);
            if (pending.isPresent()) {
                final PendingExternalVelocity external = pending.get();
                final Entity entity = world.getEntityById(entityId);
                if (entity != null && finite(external.velocity)) {
                    entity.setVelocity(external.velocity);
                    entity.velocityDirty = true;
                    debug("runtime committed externally-owned velocity after local progress entity="
                            + entityId + " serverTick=" + external.serverTick
                            + " ability=" + external.ability);
                }
            }
            if (wasCommitted && retained && !hasLivePredictedVelocityWriter(entityId)) {
                externalVelocityFence.release(entityId);
                debug("runtime released external velocity fence after its local writer ended entity="
                        + entityId);
            }
        }
    }

    private boolean hasLivePredictedVelocityWriter(final int entityId) {
        final ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || player.getId() != entityId) return false;
        for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (ability == null || ability.isRemoved() || ability.getPlayer() == null
                    || !player.getUuid().equals(ability.getPlayer().getUniqueId())) continue;
            final Long sequence = abilityActions.containsKey(ability)
                    ? abilityActions.get(ability) : abilityCreationActions.get(ability);
            for (VelocityMutation mutation : velocities) {
                if (mutation.entityId != entityId) continue;
                if ((sequence != null && sequence > 0L && mutation.action == sequence)
                        || ability.getName().equalsIgnoreCase(mutation.ability)) return true;
            }
        }
        return false;
    }

    private boolean tracksVelocityEntity0(int entityId) {
        if (!ready || entityId < 0) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.player.getId() == entityId) return true;
        return velocities.stream().anyMatch(mutation -> mutation.entityId == entityId)
                || velocityReceipts.stream().anyMatch(receipt -> receipt.entityId == entityId)
                || externalVelocityFence.blocksPredictedWrite(entityId);
    }

    private void noteVelocityOwner0(Entity localPlayer, PredictionPayloads.VelocityOwner owner) {
        if (owner == null) return;
        int targetEntityId = localPlayer != null && localPlayer.getUuid().equals(owner.target()) ? localPlayer.getId() : -1;
        noteVelocityOwner0(localPlayer, owner.serverTick(), owner.actionSequence(), owner.impulseOrdinal(),
                owner.abilityOwner(), targetEntityId, owner.ability());
    }

    private void noteVelocityOwner0(Entity localPlayer, PredictionPayloads.VelocityOwnerV2 owner) {
        if (owner == null) return;
        noteVelocityOwner0(localPlayer, owner.serverTick(), owner.actionSequence(), owner.impulseOrdinal(),
                owner.abilityOwner(), owner.targetEntityId(), owner.ability());
    }

    private void noteVelocityOwner0(Entity localPlayer, long serverTick, long actionSequence, int impulseOrdinal,
                                    UUID abilityOwner, int targetEntityId, String ability) {
        if (!ready || localPlayer == null) return;
        final boolean locallyOwned = localPlayer.getUuid().equals(abilityOwner);
        if (!VelocityReceiptPolicy.accepts(
                locallyOwned, actionSequence, impulseOrdinal, targetEntityId)) return;
        final long correlatedSequence = locallyOwned ? localActionSequence(actionSequence) : actionSequence;
        if (locallyOwned && correlatedSequence <= 0L) {
            debug("runtime ignored uncorrelated self velocity paperAction=" + actionSequence
                    + " ordinal=" + impulseOrdinal + " ability=" + ability);
            return;
        }
        // Only the final setVelocity in a server tick is observable through
        // the vanilla entity-velocity packet. Replace an intermediate receipt
        // for the same owned action instead of letting it steal that packet.
        int replacement = -1;
        for (int i = 0; i < velocityReceipts.size(); i++) {
            if (velocityReceipts.get(i).serverTick == serverTick
                    && velocityReceipts.get(i).entityId == targetEntityId) {
                replacement = i;
                break;
            }
        }
        VelocityReceipt receipt = new VelocityReceipt(serverTick, correlatedSequence, targetEntityId,
                impulseOrdinal, abilityOwner, ability, tick);
        if (replacement >= 0) velocityReceipts.set(replacement, receipt);
        else velocityReceipts.add(receipt);
        debug("runtime received velocity owner paperAction=" + actionSequence
                + " localAction=" + correlatedSequence
                + " ordinal=" + impulseOrdinal + " ability=" + ability);
    }

    private void removeAuthoritativeAbility0(Entity localPlayer, PredictionPayloads.AbilityRemoved removed) {
        if (!ready || localPlayer == null || removed == null || !localPlayer.getUuid().equals(removed.player())) return;
        final long localCreationSequence = localActionSequence(removed.actionSequence());
        final long localAcknowledgement = localAcknowledgedSequence(removed.acknowledgedSequence());
        if (removed.actionSequence() > 0L) {
            final Action action = actions.get(localCreationSequence);
            if (!removalReceiptMayResolve(removed.externallyCaused(), action != null,
                    action != null && action.nativeConfirmed)) {
                recordAbilityRemoval(removed, "IGNORED missing/unconfirmed correlated creation action"
                        + " local=" + localCreationSequence, List.of());
                return;
            }
        }
        List<CoreAbility> matching = new ArrayList<>();
        for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (ability.getPlayer() != null && ability.getPlayer().getUniqueId().equals(removed.player())
                    && ability.getName().equalsIgnoreCase(removed.ability())
                    && AbilityRemovalSync.isType(ability, removed.abilityType())) matching.add(ability);
        }
        final List<CoreAbility> coveredByEmptyTypeFence = matching.stream()
                .filter(ability -> authoritativeEmptyTypeFenceCoversCandidate(
                        removed.externallyCaused(), removed.remainingTypeInstances(),
                        localAcknowledgement, abilityActions.getOrDefault(
                                ability, abilityCreationActions.get(ability))))
                .toList();
        if (!coveredByEmptyTypeFence.isEmpty()) {
            int removedCount = 0;
            try {
                for (CoreAbility ability : coveredByEmptyTypeFence) {
                    forceRemoveAbility(ability);
                    if (ability.isRemoved()) removedCount++;
                }
                recordAbilityRemoval(removed,
                        (removedCount == coveredByEmptyTypeFence.size() ? "APPLIED" : "FAILED")
                                + " authoritative-empty-type-fence removed=" + removedCount
                                + "/" + coveredByEmptyTypeFence.size(), matching);
            } catch (RuntimeException failure) {
                recordAbilityRemoval(removed, "FAILED " + failure.getClass().getSimpleName()
                        + ": " + String.valueOf(failure.getMessage())
                        + " authoritative-empty-type-fence", matching);
                ProjectKorra.log.log(Level.WARNING, "Authoritative empty-type cleanup failed for "
                        + removed.abilityType(), failure);
            }
            return;
        }
        CoreAbility selected = null;
        if (localCreationSequence > 0L) {
            for (CoreAbility ability : matching) {
                if (Objects.equals(abilityCreationActions.get(ability), localCreationSequence)) {
                    selected = ability;
                    break;
                }
            }
        }
        if (selected == null) {
            recordAbilityRemoval(removed, "NO_MATCH", matching);
            return;
        }
        final Long creationSequence = abilityCreationActions.get(selected);
        final Action predictedAction = creationSequence == null ? null : actions.get(creationSequence);
        if (!removed.externallyCaused() && predictedAction != null
                && predictedAction.reconciled && predictedAction.locallyPredicted) {
            // Server removal is lifecycle metadata, not permission to cut
            // an accepted local simulation short. Doing so reverted every
            // TempBlock owned by PhaseChange/WaterGimbal and made visual
            // projectiles such as Discharge stop at the network boundary.
            // Persistent movement abilities follow the same rule: their
            // local environment/input lifecycle is the prediction answer,
            // so a delayed Paper close cannot invert the next toggle.
            // The common client instance remains bounded by its own normal
            // range, duration, input and collision rules.
            debug("runtime retained accepted client ability lifecycle after server close ability="
                    + removed.ability() + " type=" + removed.abilityType()
                    + " action=" + removed.actionSequence());
            recordAbilityRemoval(removed, "RETAINED ordinary predicted lifecycle", matching);
            return;
        }
        debug("runtime applied authoritative ability removal ability=" + removed.ability()
                + " type=" + removed.abilityType()
                + " paperAction=" + removed.actionSequence()
                + " localCreation=" + localCreationSequence
                + " external=" + removed.externallyCaused());
        try {
            forceRemoveAbility(selected);
            recordAbilityRemoval(removed, (selected.isRemoved() ? "APPLIED" : "FAILED instance remained active")
                    + " selectedCreation=" + creationSequence, matching);
        } catch (RuntimeException failure) {
            recordAbilityRemoval(removed, "FAILED " + failure.getClass().getSimpleName()
                    + ": " + String.valueOf(failure.getMessage())
                    + " selectedCreation=" + creationSequence, matching);
            ProjectKorra.log.log(Level.WARNING, "Authoritative ability cleanup failed for "
                    + removed.abilityType(), failure);
        }
    }

    private void recordAbilityRemoval(final PredictionPayloads.AbilityRemoved removed,
                                      final String resolution, final List<CoreAbility> matching) {
        if (removed == null) return;
        final List<String> candidates = new ArrayList<>();
        if (matching != null) {
            for (CoreAbility ability : matching) {
                candidates.add(ability.getClass().getSimpleName() + "@"
                        + String.valueOf(abilityCreationActions.get(ability))
                        + (ability.isRemoved() ? "(removed)" : "(active)"));
            }
        }
        abilityRemovalHistory.add("ability=" + removed.ability()
                + " type=" + removed.abilityType()
                + " action=" + removed.actionSequence()
                + " external=" + removed.externallyCaused()
                + " ack=" + removed.acknowledgedSequence()
                + " remainingType=" + removed.remainingTypeInstances()
                + " result=" + resolution
                + " candidates=" + candidates);
        while (abilityRemovalHistory.size() > 12) abilityRemovalHistory.remove(0);
    }

    private List<String> abilityRemovalReport0() {
        final List<String> report = new ArrayList<>();
        if (abilityRemovalHistory.isEmpty()) {
            report.add("Ability removals: no Paper removal receipt has reached this client session");
        } else {
            report.add("Ability removals (oldest to newest):");
            report.addAll(abilityRemovalHistory);
        }
        final List<String> active = new ArrayList<>();
        for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (ability.getPlayer() == null || bendingPlayer == null || bendingPlayer.getPlayer() == null
                    || !ability.getPlayer().getUniqueId().equals(bendingPlayer.getPlayer().getUniqueId())) continue;
            if (!(ability.getName().equalsIgnoreCase("WaterSpout")
                    || ability.getName().equalsIgnoreCase("AirSpout"))) continue;
            active.add(ability.getClass().getSimpleName() + "@"
                    + String.valueOf(abilityCreationActions.get(ability)));
        }
        report.add("Local spout instances=" + active
                + " Paper flight snapshot=" + authoritativeFlightAbilities
                + " snapshotAck=" + authoritativeFlightSequence);
        return List.copyOf(report);
    }

    private List<String> tempBlockReport0() {
        final List<String> report = new ArrayList<>();
        report.add("TempBlocks: localRecords=" + clientTempBlockActions.size()
                + " localActive=" + TempBlock.getActiveLayers().size()
                + " serverLayers=" + authoritativeTempBlockLayers.size()
                + " serverCoordinates=" + serverTempBlocks.coordinateCount()
                + " closeFences=" + completedTempBlockRestores.size()
                + " teardownFences=" + tempBlockTeardownFences.size());
        if (tempBlockTeardownHistory.isEmpty()) {
            report.add("Authoritative teardown: no ability teardown has captured TempBlock coordinates");
        } else {
            report.add("Recent authoritative TempBlock teardowns:");
            report.addAll(tempBlockTeardownHistory);
        }
        if (authoritativeTempBlockHistory.isEmpty()) {
            report.add("Authoritative TempBlock wire history: no lifecycle operation advanced this runtime");
        } else {
            report.add("Authoritative TempBlock operations (oldest to newest):");
            report.addAll(authoritativeTempBlockHistory);
        }
        int details = 0;
        for (Map.Entry<Long, LocalTempBlockPrediction> entry : clientTempBlockActions.entrySet()) {
            final LocalTempBlockPrediction local = entry.getValue();
            if (local == null || local.owner == null
                    || !local.owner.getName().equalsIgnoreCase("WaterSpout")) continue;
            report.add("local layer=" + entry.getKey() + " pos=" + local.key.pos
                    + " active=" + (findActiveTempBlock(entry.getKey()) != null)
                    + " closed=" + local.closed + " serverClosed=" + local.serverClosed
                    + " serverLayer=" + local.serverLayerId
                    + " world=" + local.key.world.getBlockState(local.key.pos));
            if (++details >= 12) break;
        }
        int fenceDetails = 0;
        for (BlockKey key : tempBlockTeardownFences.keys()) {
            if (key == null || key.world == null || key.pos == null) continue;
            final BlockState current = key.world.getBlockState(key.pos);
            final Optional<BlockState> retained = tempBlockTeardownFences.retainedState(key);
            report.add("teardown fence pos=" + key.pos + " world=" + current
                    + " retained=" + retained.orElse(null)
                    + " staleNow=" + tempBlockTeardownFences.audit(key, current).isPresent());
            if (++fenceDetails >= 12) break;
        }
        return List.copyOf(report);
    }

    private void recordAuthoritativeTempBlock(final String event) {
        if (event == null || event.isBlank()) return;
        authoritativeTempBlockHistory.add("tick=" + tick + " " + event);
        while (authoritativeTempBlockHistory.size() > AUTHORITATIVE_TEMP_BLOCK_HISTORY_LIMIT) {
            authoritativeTempBlockHistory.remove(0);
        }
    }

    private void notePredictedAbilityState0(boolean invulnerable, boolean flying, boolean allowFlying,
                                            boolean creativeMode, float flySpeed, float walkSpeed) {
        if (!ready) return;
        final long actionSequence = currentAction();
        final Action action = actions.get(actionSequence);
        final ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (action == null || player == null) return;
        final int ordinal = action.abilityStateOrdinals.merge(player.getId(), 1, Integer::sum);
        abilityStates.add(new AbilityStateMutation(tick, actionSequence, ordinal));
    }

    private void noteAbilityStateOwner0(final Entity localPlayer,
                                        final PredictionPayloads.AbilityStateOwner owner) {
        if (!ready || localPlayer == null || owner == null
                || owner.actionSequence() <= 0L || owner.mutationOrdinal() <= 0
                || !localPlayer.getUuid().equals(owner.target())) return;
        final boolean locallyOwned = localPlayer.getUuid().equals(owner.abilityOwner());
        final long correlatedSequence = locallyOwned
                ? localActionSequence(owner.actionSequence()) : owner.actionSequence();
        if (locallyOwned && correlatedSequence <= 0L) {
            debug("runtime ignored uncorrelated self ability-state paperAction="
                    + owner.actionSequence() + " ordinal=" + owner.mutationOrdinal()
                    + " ability=" + owner.ability());
            return;
        }
        final AbilityStateReceipt receipt = new AbilityStateReceipt(
                owner.serverTick(), correlatedSequence, owner.mutationOrdinal(),
                owner.abilityOwner(), owner.target(), owner.ability(),
                owner.flying(), owner.allowFlight(), owner.flySpeed(), tick);
        // PlayerAbilitiesS2CPacket is an absolute snapshot. Multiple
        // setAllowFlight/setFlying calls in one server tick can be coalesced
        // into one final packet, just like entity velocity. Retaining an
        // intermediate ownership receipt lets it steal a later unowned
        // correction and is what made WaterSpout poison AirSpout after a
        // round trip. If the intermediate packet was emitted separately,
        // connection order has already consumed the old receipt before this
        // replacement arrives.
        int replacement = -1;
        for (int index = 0; index < abilityStateReceipts.size(); index++) {
            final AbilityStateReceipt candidate = abilityStateReceipts.get(index);
            if (candidate.serverTick == owner.serverTick()
                    && candidate.target.equals(owner.target())) {
                replacement = index;
                break;
            }
        }
        if (replacement >= 0) abilityStateReceipts.set(replacement, receipt);
        else abilityStateReceipts.add(receipt);
    }

    private void notePredictedExperience0(float barProgress, int experience, int level) {
        if (!ready) return;
        experiences.add(new ExperienceMutation(tick, barProgress, experience, level));
    }

    private boolean notePredictedSelectedSlot0(int slot) {
        if (slot < 0 || slot > 8) return false;
        if (!ready || bendingPlayer == null || bendingPlayer.getPlayer() == null) return true;
        final CommonInputHandler.SlotResult result = CommonInputHandler.handleSlotChange(
                bendingPlayer.getPlayer(), slot);
        if (!result.accepted()) return false;
        return true;
    }

    private boolean suppressAuthoritativeAbilityState0(PlayerAbilitiesS2CPacket packet) {
        if (!ready || packet == null) return false;
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return false;
        int receiptIndex = -1;
        for (int index = 0; index < abilityStateReceipts.size(); index++) {
            final AbilityStateReceipt candidate = abilityStateReceipts.get(index);
            if (player.getUuid().equals(candidate.target)) {
                if (!matchesAbilityStatePacket(candidate, packet)) continue;
                receiptIndex = index;
                break;
            }
        }
        if (receiptIndex < 0) {
            // Abilities packets are absolute and also contain unrelated
            // vanilla/external fields. While common prediction owns a live
            // flight lease—or has just closed one—merge those fields but keep
            // the local flying/allowFlying projection. Restricting this to the
            // pre-ack window let an ordinary anti-flight packet invert a
            // long-lived WaterSpout after its input had been acknowledged.
            // Any mismatched ownership receipt remains armed for the exact
            // projection it announced.
            if ((!hasLocalFlightLease() && !hasRecentLocalAbilityState())
                    || player.getAbilities().flying == packet.isFlying()
                    && player.getAbilities().allowFlying == packet.allowFlying()) return false;
            final var abilities = player.getAbilities();
            abilities.invulnerable = packet.isInvulnerable();
            abilities.creativeMode = packet.isCreativeMode();
            abilities.setFlySpeed(packet.getFlySpeed());
            abilities.setWalkSpeed(packet.getWalkSpeed());
            debug("runtime preserved locally-leased flight across unowned server correction"
                    + " flying=" + abilities.flying + " allowFlying=" + abilities.allowFlying);
            return true;
        }
        final AbilityStateReceipt receipt = abilityStateReceipts.remove(receiptIndex);
        if (!player.getUuid().equals(receipt.abilityOwner)) {
            debug("runtime allowed externally-owned abilities packet owner=" + receipt.abilityOwner
                    + " ability=" + receipt.ability);
            return false;
        }
        abilityStates.removeIf(mutation -> mutation.actionSequence == receipt.actionSequence
                && mutation.mutationOrdinal == receipt.mutationOrdinal);
        // The receipt itself is exact authority for ownership. Suppressing the
        // following echo cannot hide gamemode/plugin state because those
        // packets have no ProjectKorra ownership receipt and pass normally.
        debug("runtime suppressed self-owned abilities packet action=" + receipt.actionSequence
                + " ordinal=" + receipt.mutationOrdinal + " ability=" + receipt.ability);
        return true;
    }

    private static boolean matchesAbilityStatePacket(final AbilityStateReceipt receipt,
                                                      final PlayerAbilitiesS2CPacket packet) {
        if (receipt == null || packet == null) return false;
        // Bukkit exposes fly speed at twice Minecraft's packet-scale value;
        // Fabric's common Player adapter intentionally mirrors that contract.
        final float commonFlySpeed = packet.getFlySpeed() * 2.0F;
        return receipt.flying == packet.isFlying()
                && receipt.allowFlight == packet.allowFlying()
                && Float.compare(receipt.flySpeed, commonFlySpeed) == 0;
    }

    private boolean hasLocalFlightLease() {
        if (bendingPlayer == null || bendingPlayer.getPlayer() == null) return false;
        try {
            return Manager.getManager(FlightHandler.class).getInstance(bendingPlayer.getPlayer()) != null;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private boolean hasRecentLocalAbilityState() {
        for (int index = abilityStates.size() - 1; index >= 0; index--) {
            if (tick - abilityStates.get(index).tick <= ACTION_RETENTION_TICKS) return true;
        }
        return false;
    }

    private void reconcileActiveFlightAbilities0(List<String> activeAbilities, long acknowledgedSequence) {
        if (!ready || bendingPlayer == null || bendingPlayer.getPlayer() == null) return;
        long latestLocalSequence = latestLocalSequence();
        final long localAcknowledgement = localAcknowledgedSequence(acknowledgedSequence);
        if (!PredictionStateOrdering.snapshotCoversLatestInput(localAcknowledgement, latestLocalSequence)) {
            debug("runtime deferred flight snapshot ack=" + acknowledgedSequence
                    + " localAck=" + localAcknowledgement + " latestLocal=" + latestLocalSequence);
            return;
        }
        final Set<String> next = new HashSet<>();
        if (activeAbilities != null) {
            for (String ability : activeAbilities) {
                if (ability == null) continue;
                final String normalized = ability.toLowerCase(Locale.ROOT);
                if (PERSISTENT_FLIGHT_ABILITIES.contains(normalized)) next.add(normalized);
            }
        }
        // This snapshot is diagnostics, not lifecycle authority. The common
        // client simulation owns when a predicted spout/scooter/jet starts and
        // ends. Reconstructing or removing an instance from a delayed Paper
        // set caused off-water WaterSpout to remain half alive (flight lease
        // present, column gone) and inverted later AirSpout toggles.
        authoritativeFlightAbilities = Set.copyOf(next);
        authoritativeFlightSequence = acknowledgedSequence;
        debug("runtime observed sequence-fenced Paper flight snapshot ack=" + acknowledgedSequence
                + " localAck=" + localAcknowledgement + " active=" + authoritativeFlightAbilities);
    }

    private long latestLocalSequence() {
        return actions.keySet().stream().mapToLong(Long::longValue).max().orElse(0L);
    }

    private void forceRemoveAbility(final CoreAbility ability) {
        if (ability == null || ability.isRemoved()) return;
        final Map<BlockKey, CapturedAbilityTempBlock> capturedTempBlocks =
                captureAbilityTempBlocks(ability);
        try {
            PredictedContactSync.forceRemoval(ability,
                    () -> AbilityExecutionContext.run(ability, ability::remove));
        } finally {
            finalizeAbilityTempBlockTeardown(ability, capturedTempBlocks);
            abilityActions.remove(ability);
            abilityCreationActions.remove(ability);
        }
    }

    private Map<BlockKey, CapturedAbilityTempBlock> captureAbilityTempBlocks(final CoreAbility ability) {
        if (ability == null) return Map.of();
        final Map<BlockKey, CapturedAbilityTempBlock> captured = new LinkedHashMap<>();
        for (LocalTempBlockPrediction local : clientTempBlockActions.values()) {
            if (local == null || local.owner != ability || local.key == null) continue;
            final BlockState underlay = local.closedState != null ? local.closedState
                    : local.authoritativeUnderlay != null ? local.authoritativeUnderlay
                    : local.initialUnderlay;
            final CapturedAbilityTempBlock lifecycle = captured.computeIfAbsent(
                    local.key, ignored -> new CapturedAbilityTempBlock());
            // Records are insertion ordered. A newer generation at the same
            // coordinate has the newest rebased/final underlay.
            if (underlay != null) lifecycle.underlay = underlay;
            lifecycle.staleStates.addAll(local.createdStates);
            lifecycle.addStale(local.initialUnderlay);
            lifecycle.addStale(local.authoritativeUnderlay);
            lifecycle.addStale(local.closedState);
        }
        for (TempBlock layer : TempBlock.getActiveLayers()) {
            if (layer == null || layer.isReverted() || layer.getAbility().orElse(null) != ability) continue;
            final BlockKey key = clientBlockKey(layer.getBlock());
            if (key == null) continue;
            final BlockState underlay = materialState(TempBlockSync.encode(layer.getState().getBlockData()));
            final BlockState created = materialState(TempBlockSync.encode(layer.getBlockData()));
            final CapturedAbilityTempBlock lifecycle = captured.computeIfAbsent(
                    key, ignored -> new CapturedAbilityTempBlock());
            // The active common stack is the strongest source: all of its
            // layers share the currently rebased base and override an older
            // high-latency tombstone for the same coordinate.
            if (underlay != null) lifecycle.underlay = underlay;
            lifecycle.addStale(created);
            lifecycle.addStale(underlay);
        }
        return captured;
    }

    private void finalizeAbilityTempBlockTeardown(final CoreAbility ability,
                                                   final Map<BlockKey, CapturedAbilityTempBlock> capturedTempBlocks) {
        if (capturedTempBlocks == null || capturedTempBlocks.isEmpty()) return;
        int repainted = 0;
        int armed = 0;
        int remainingLocal = 0;
        int hiddenServer = 0;
        final List<String> samples = new ArrayList<>();
        final ClientPlayerEntity player = MinecraftClient.getInstance().player;
        for (Map.Entry<BlockKey, CapturedAbilityTempBlock> entry : capturedTempBlocks.entrySet()) {
            final BlockKey key = entry.getKey();
            if (key == null || key.world == null || key.pos == null) continue;
            final CapturedAbilityTempBlock lifecycle = entry.getValue();
            final boolean hidden = hidesServerTempBlock(key);
            BlockState local = clientTempBlockState(key.world, key.pos);
            if (hidden && player != null) {
                local = serverTempBlocks.overlayState(key, player.getUuid()).orElse(local);
            }
            final BlockState hiddenViewer = hidden
                    ? serverTempBlocks.viewerState(key).orElse(null) : null;
            final BlockState visiblePhysical = hidden
                    ? null : serverTempBlocks.physicalState(key).orElse(null);
            final ServerDirectBlockMask direct = serverDirectBlockMasks.get(key);
            final BlockState before = key.world.getBlockState(key.pos);
            final BlockState selected = TempBlockTeardownPolicy.select(
                    local, hiddenViewer, visiblePhysical,
                    direct == null ? null : direct.viewerState,
                    lifecycle == null ? null : lifecycle.underlay, before);
            if (local != null) remainingLocal++;
            if (hidden) hiddenServer++;
            if (selected != null && !selected.equals(before)) {
                key.world.setBlockState(key.pos, selected, 19);
                repainted++;
            }
            if (lifecycle != null && selected != null && !lifecycle.staleStates.isEmpty()) {
                tempBlockTeardownFences.arm(key, lifecycle.staleStates, selected, tick);
                armed++;
            }
            blocks.remove(key);
            if (samples.size() < 6) {
                samples.add(key.pos + ":" + before + "->" + selected
                        + (hidden ? "(server-hidden)" : "")
                        + " stale=" + (lifecycle == null ? 0 : lifecycle.staleStates.size()));
            }
        }
        tempBlockTeardownHistory.add("ability=" + (ability == null ? "<null>" : ability.getName())
                + " captured=" + capturedTempBlocks.size() + " armed=" + armed + " repainted=" + repainted
                + " remainingLocal=" + remainingLocal + " hiddenServer=" + hiddenServer
                + " samples=" + samples);
        while (tempBlockTeardownHistory.size() > 12) tempBlockTeardownHistory.remove(0);
        debug("runtime finalized authoritative TempBlock teardown "
                + tempBlockTeardownHistory.get(tempBlockTeardownHistory.size() - 1));
    }

    /**
     * Composes a completed lifecycle's retained state with any newer live local,
     * remote TempBlock or owned direct-Earth layer. The fence supplies only the
     * fallback; it never suppresses a newer layer which the ledgers know about.
     */
    private BlockState composeTempBlockTeardownView(final BlockKey key,
                                                    final BlockState retainedFallback) {
        if (key == null || key.world == null || key.pos == null) return retainedFallback;
        final boolean hidden = hidesServerTempBlock(key);
        BlockState local = clientTempBlockState(key.world, key.pos);
        final ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (hidden && player != null) {
            local = serverTempBlocks.overlayState(key, player.getUuid()).orElse(local);
        }
        final BlockState hiddenViewer = hidden
                ? serverTempBlocks.viewerState(key).orElse(null) : null;
        final BlockState visiblePhysical = hidden
                ? null : serverTempBlocks.physicalState(key).orElse(null);
        final ServerDirectBlockMask direct = serverDirectBlockMasks.get(key);
        return TempBlockTeardownPolicy.select(local, hiddenViewer, visiblePhysical,
                direct == null ? null : direct.viewerState,
                retainedFallback, retainedFallback);
    }

    private void auditTempBlockTeardownFences(final ClientWorld world) {
        if (world == null || showServerTempBlocks || tempBlockTeardownFences.size() == 0) return;
        int repaired = 0;
        for (BlockKey key : tempBlockTeardownFences.keys()) {
            if (key == null || key.world != world || key.pos == null) continue;
            final BlockState current = world.getBlockState(key.pos);
            final Optional<BlockState> retained = tempBlockTeardownFences.audit(key, current);
            if (retained.isEmpty()) continue;
            final BlockState desired = composeTempBlockTeardownView(key, retained.get());
            if (desired != null && !desired.equals(current)) {
                world.setBlockState(key.pos, desired, 19);
                repaired++;
            }
        }
        if (repaired > 0) {
            debug("runtime rejected client-side late TempBlock writes=" + repaired);
        }
    }

    private boolean suppressAuthoritativeExperience0(ExperienceBarUpdateS2CPacket packet) {
        if (!ready || packet == null) return false;
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return false;
        // Never hide actual vanilla XP/level changes. Only the fractional bar
        // is prediction-owned while a bending passive is actively writing it.
        if (packet.getExperience() != player.totalExperience
                || packet.getExperienceLevel() != player.experienceLevel) {
            experiences.clear();
            return false;
        }
        for (int i = experiences.size() - 1; i >= 0; i--) {
            ExperienceMutation mutation = experiences.get(i);
            if (tick - mutation.tick <= 4) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLocalPlayerEntity(int entityId) {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && client.player.getId() == entityId;
    }

    private String currentAbilityName() {
        CoreAbility ability = AbilityExecutionContext.current();
        return ability == null ? "<none>" : ability.getName();
    }

    private String airBlastStamina() {
        return bendingPlayer == null ? "<none>" : String.format(Locale.ROOT, "%.4f", bendingPlayer.getAirBlastDecay());
    }

    private String activeAirBlastSummary() {
        if (bendingPlayer == null) return "[]";
        List<String> summary = new ArrayList<>();
        for (AirBlast blast : CoreAbility.getAbilities(bendingPlayer.getPlayer(), AirBlast.class)) {
            summary.add("{progressing=" + blast.isProgressing()
                    + ",fromOther=" + blast.isFromOtherOrigin()
                    + ",ticks=" + blast.getTicks()
                    + ",action=" + abilityActions.get(blast)
                    + ",creation=" + abilityCreationActions.get(blast)
                    + ",traceAction=" + blast.getTraceActionSequence()
                    + ",loc=" + compactLocation(blast.getLocation())
                    + ",origin=" + compactLocation(blast.getOrigin())
                    + ",radius=" + String.format(Locale.ROOT, "%.3f", blast.getRadius())
                    + "}");
        }
        return summary.toString();
    }

    private static String velocityString(Vec3d velocity) {
        if (velocity == null) return "<null>";
        return String.format(Locale.ROOT, "(%.4f, %.4f, %.4f)", velocity.x, velocity.y, velocity.z);
    }

    private static String compactLocation(Location location) {
        if (location == null) return "<null>";
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", location.getX(), location.getY(), location.getZ());
    }

    private void trackSpawn0(Entity entity) {
        long action = currentAction();
        Action owner = actions.get(action);
        if (owner != null && entity != null) {
            owner.spawned.add(entity);
            predictedSpawnOrigins.put(entity, entity.getEntityPos());
        }
    }

    @Override
    public int beforeSpawn(final CoreAbility ability, final Location location,
                           final BlockData blockData) {
        if ((!ready && !initializing) || ability == null) return 0;
        long sequence = currentAction();
        if (sequence <= 0L) sequence = abilityActions.getOrDefault(ability, 0L);
        final Action action = actions.get(sequence);
        return action == null ? 0 : ++action.tempFallingBlockOrdinal;
    }

    @Override
    public void onSpawn(final CoreAbility ability, final FallingBlock fallingBlock,
                        final int spawnOrdinal) {
        if ((!ready && !initializing) || ability == null || fallingBlock == null
                || spawnOrdinal <= 0 || !(fallingBlock.handle() instanceof Entity entity)) return;
        long sequence = currentAction();
        if (sequence <= 0L) sequence = abilityActions.getOrDefault(ability, 0L);
        final Action action = actions.get(sequence);
        if (action == null) return;
        predictedTempFallingBlocks.put(new TempFallingBlockKey(sequence, spawnOrdinal),
                new PredictedTempFallingBlock(entity, ability.getName()));
    }

    private void noteTempFallingBlockPrepare0(final Entity localPlayer,
                                              final PredictionPayloads.TempFallingBlockPrepare prepare) {
        if (!ready || localPlayer == null || prepare == null
                || prepare.actionSequence() <= 0L
                || !localPlayer.getUuid().equals(prepare.abilityOwner())) return;
        final long localSequence = localActionSequence(prepare.actionSequence());
        if (localSequence <= 0L) return;
        final TempFallingBlockKey key = new TempFallingBlockKey(
                localSequence, prepare.spawnOrdinal());
        serverTempFallingPrepares.put(key, new ServerTempFallingPrepare(prepare, tick));
    }

    private void noteTempFallingBlock0(final Entity localPlayer,
                                       final PredictionPayloads.TempFallingBlockReceipt receipt) {
        if (!ready || localPlayer == null || receipt == null
                || receipt.actionSequence() <= 0L
                || !localPlayer.getUuid().equals(receipt.abilityOwner())) return;

        final long localSequence = localActionSequence(receipt.actionSequence());
        if (localSequence <= 0L) {
            hiddenTempFallingEntities.add(receipt.serverEntityId());
            return;
        }
        final TempFallingBlockKey key = new TempFallingBlockKey(
                localSequence, receipt.spawnOrdinal());
        final PredictedTempFallingBlock pending = predictedTempFallingBlocks.get(key);
        final TempFallingBlockKey preparedKey = preparedFallingEntityIds.get(receipt.serverEntityId());
        if (preparedKey != null && !preparedKey.equals(key)) return;
        predictedTempFallingBlocks.remove(key);
        serverTempFallingPrepares.remove(key);
        preparedFallingEntityIds.remove(receipt.serverEntityId());
        final Entity predicted = pending != null && pending.ability.equalsIgnoreCase(receipt.ability())
                ? pending.entity : null;

        final ClientWorld world = MinecraftClient.getInstance().world;
        final boolean vanillaSpawnSeen = observedFallingBlockSpawns.remove(receipt.serverEntityId()) != null;
        final Entity authority = world == null ? null : world.getEntityById(receipt.serverEntityId());
        if (vanillaSpawnSeen || authority != null && authority != predicted) {
            // Packet order must not choose the rendered entity. Even when the
            // delayed vanilla spawn wins the race, retire that duplicate and
            // keep the continuously simulated local falling block.
            if (authority != null && authority != predicted && !authority.isRemoved()) authority.discard();
        }

        // The following vanilla spawn (or the one just retired) is consumed.
        // Movement/velocity packets are intentionally ignored for the alias;
        // the common client TempFallingBlock remains the visual authority.
        if (predicted != null) {
            hiddenTempFallingEntities.remove(receipt.serverEntityId());
            authoritativeEntityAliases.put(receipt.serverEntityId(), predicted);
            tempFallingEntityAliases.add(receipt.serverEntityId());
        } else {
            // Authority says this belongs to the caster's predicted visual,
            // even if the local ordinal was coalesced or already retired.
            hiddenTempFallingEntities.add(receipt.serverEntityId());
        }
    }

    private boolean reconcileSpawn0(EntitySpawnS2CPacket packet) {
        if (!ready || MinecraftClient.getInstance().world == null) return false;
        if (authoritativeEntityAliases.containsKey(packet.getEntityId())
                || hiddenTempFallingEntities.contains(packet.getEntityId())) return true;
        // Falling blocks are never reconciled by proximity. Only the caster's
        // exact TempFallingBlock receipt may consume its server entity; every
        // other player's falling blocks must remain normal vanilla spawns.
        if (packet.getEntityType() == net.minecraft.entity.EntityType.FALLING_BLOCK) {
            final Vec3d spawn = new Vec3d(packet.getX(), packet.getY(), packet.getZ());
            final BlockState spawnedState = net.minecraft.block.Block.getStateFromRawId(packet.getEntityData());
            for (Map.Entry<TempFallingBlockKey, ServerTempFallingPrepare> entry
                    : serverTempFallingPrepares.entrySet()) {
                if (preparedFallingEntityIds.containsValue(entry.getKey())) continue;
                final PredictionPayloads.TempFallingBlockPrepare prepare = entry.getValue().prepare;
                // A prepare is owner-only, but another player's falling block
                // can cross the same coordinate before a stale prepare is
                // retired. Include the exact vanilla block-state payload so
                // that remote entity can never be consumed merely by position.
                if (!materialState(prepare.material()).equals(spawnedState)) continue;
                if (!matchesWorld(MinecraftClient.getInstance().world.getRegistryKey().getValue().toString(),
                        prepare.world())) continue;
                final Vec3d expected = new Vec3d(prepare.x(), prepare.y(), prepare.z());
                // Spawn coordinates are encoded as doubles. This is an exact
                // packet-order match, never a nearest-entity search.
                if (!close(spawn, expected, 1.0E-7)) continue;
                preparedFallingEntityIds.put(packet.getEntityId(), entry.getKey());
                observedFallingBlockSpawns.put(packet.getEntityId(), tick);
                hiddenTempFallingEntities.add(packet.getEntityId());
                return true;
            }
            observedFallingBlockSpawns.put(packet.getEntityId(), tick);
            return false;
        }
        Vec3d serverPosition = new Vec3d(packet.getX(), packet.getY(), packet.getZ());
        Entity best = null; double bestDistance = 32.0 * 32.0;
        for (Action action : actions.values()) {
            if (tick - action.createdTick > ACTION_RETENTION_TICKS && action.abilities.isEmpty()) continue;
            for (Entity candidate : action.spawned) {
                if (candidate == null || candidate.getType() != packet.getEntityType()
                        || authoritativeEntityAliases.containsValue(candidate)) continue;
                // Match the authoritative spawn to where the predicted entity
                // was created, not where it has moved during the round trip.
                // Removed candidates remain tombstones so a short-lived shard
                // or fragment cannot reappear when its delayed server spawn
                // finally arrives.
                Vec3d predictedOrigin = predictedSpawnOrigins.getOrDefault(candidate, candidate.getEntityPos());
                double distance = predictedOrigin.squaredDistanceTo(serverPosition);
                if (distance < bestDistance) { best = candidate; bestDistance = distance; }
            }
        }
        if (best == null) return false;
        // Keep the local UUID: ClientWorld indexed this entity under that UUID
        // when prediction spawned it. Paper's numeric ID is translated by the
        // alias mixin without corrupting that client-side UUID index.
        authoritativeEntityAliases.put(packet.getEntityId(), best);
        return true;
    }

    private boolean removeAliasedEntity0(int serverEntityId) {
        final boolean hidden = hiddenTempFallingEntities.remove(serverEntityId);
        Entity entity = authoritativeEntityAliases.remove(serverEntityId);
        if (entity == null) return hidden;
        final boolean clientOwnedFallingBlock = tempFallingEntityAliases.remove(serverEntityId);
        if (!clientOwnedFallingBlock && !entity.isRemoved()) entity.discard();
        return true;
    }

    private long currentAction() {
        Long input = INPUT_ACTION.get();
        if (input != null) return input;
        CoreAbility ability = AbilityExecutionContext.current();
        if (ability == null) return 0L;
        final Long associated = abilityActions.get(ability);
        if (associated != null && associated > 0L) return associated;

        // CoreAbility captures PredictionDeterminism.currentAction() in its
        // constructor. This is the exact parent input for children created by
        // a progressing ability (RaiseEarthWall -> RaiseEarth and Shockwave ->
        // Ripple), even though those children were not present during the
        // original input tracking pass. Adopt that identity before their first
        // delayed block/falling/velocity effect.
        final long inherited = ability.getPredictionActionSequence();
        final Action action = inherited <= 0L ? null : actions.get(inherited);
        if (action == null) return 0L;
        abilityActions.put(ability, inherited);
        abilityCreationActions.putIfAbsent(ability, inherited);
        action.abilities.add(ability);
        debug("runtime inherited child action=" + inherited + " ability=" + ability.getName()
                + " instance=" + System.identityHashCode(ability));
        return inherited;
    }

    private PredictionClient.ServerPose executionPose0() {
        final Long eventAction = INPUT_EVENT_POSE.get();
        final Action action = eventAction == null ? null : actions.get(eventAction);
        // Paper exposes the packet-time pose only while its native event is
        // executing. Progress and delayed tasks return to PredictionClient's
        // server-visible timeline instead of inheriting the action's constructor
        // coordinates. That timeline independently models Paper's shift flag and
        // its later entity-pose update across the scheduler/world-tick boundary.
        if (action == null) return null;
        return new PredictionClient.ServerPose(action.origin.x, action.origin.y - action.eyeHeight,
                action.origin.z, action.yaw, action.pitch, action.eyeHeight);
    }

    private static boolean finite(Vec3d value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static BlockState materialState(String materialName) {
        if (materialName != null && !materialName.contains(";")) {
            return FabricMC.blockState(materialName);
        }
        Material material;
        String[] fields = materialName == null ? new String[0] : materialName.trim().split(";");
        try {
            String key = fields.length == 0 ? "" : fields[0];
            int namespace = key.indexOf(':');
            if (namespace >= 0) key = key.substring(namespace + 1);
            material = key.isBlank()
                    ? Material.AIR
                    : Material.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            material = Material.AIR;
        }
        BlockData data = material.createBlockData();
        for (int i = 1; i < fields.length; i++) {
            int separator = fields[i].indexOf('=');
            if (separator <= 0) continue;
            String name = fields[i].substring(0, separator);
            String value = fields[i].substring(separator + 1);
            try {
                if (data instanceof Levelled levelled) {
                    if (name.equals("level")) levelled.setLevel(Integer.parseInt(value));
                    else if (name.equals("waterlogged")) levelled.setWaterlogged(value.equals("1"));
                } else if (data instanceof Fire fire
                        && name.equals("faces") && !value.isBlank()) {
                    for (String face : value.split(",")) {
                        fire.setFace(BlockFace.valueOf(face), true);
                    }
                } else if (data instanceof Snow snow && name.equals("layers")) {
                    snow.setLayers(Math.max(1, Math.min(8, Integer.parseInt(value))));
                }
            } catch (IllegalArgumentException ignored) { }
        }
        return FabricMC.blockState(data);
    }

    private static boolean matchesWorld(String clientWorld, String serverWorld) {
        if (serverWorld == null || serverWorld.isBlank()) return false;
        if (clientWorld.equals(serverWorld)) return true;
        return serverWorld.indexOf(':') < 0 && ("minecraft:overworld".equals(clientWorld) || "overworld".equals(clientWorld));
    }

    private static boolean close(Vec3d first, Vec3d second, double tolerance) {
        return first.squaredDistanceTo(second) <= tolerance * tolerance;
    }

    private boolean hidesServerTempBlock(final BlockKey key) {
        if (showServerTempBlocks) return false;
        if (key == null || key.world == null || key.pos == null) return false;
        for (Map.Entry<Long, ServerTempBlockPrediction> entry : authoritativeTempBlockLayers.entrySet()) {
            final ServerTempBlockPrediction server = entry.getValue();
            if (server != null && server.hiddenForLocalViewer && key.equals(server.key)
                    && serverTempBlocks.containsLayer(key, entry.getKey())) return true;
        }
        return hasSemanticTempBlockPair(key);
    }

    private boolean hasSemanticTempBlockPair(final BlockKey key) {
        if (key == null || key.world == null || key.pos == null) return false;
        final Set<Long> paired = pairedTempBlockCoordinates.get(key);
        if (paired == null || paired.isEmpty()) return false;
        paired.removeIf(serverLayer -> {
            final Long localLayer = pairedServerTempBlocks.get(serverLayer);
            final LocalTempBlockPrediction local = localLayer == null
                    ? null : clientTempBlockActions.get(localLayer);
            return local == null || (!local.closed && findActiveTempBlock(localLayer) == null)
                    || !serverTempBlocks.containsLayer(key, serverLayer);
        });
        if (paired.isEmpty()) {
            pairedTempBlockCoordinates.remove(key);
            return false;
        }
        return true;
    }

    private boolean hasActiveServerPair(final LocalTempBlockPrediction local) {
        if (local == null || local.serverLayerId == 0L) return false;
        final ServerTempBlockPrediction server = authoritativeTempBlockLayers.get(local.serverLayerId);
        return server != null && serverTempBlocks.containsLayer(server.key, local.serverLayerId);
    }

    private static TempBlockEffectKey effectKey(final long actionSequence, final String ability,
                                                 final long step, final int ordinal) {
        if (actionSequence <= 0L || ability == null || ability.isBlank() || ordinal <= 0) return null;
        return new TempBlockEffectKey(actionSequence, ability.toLowerCase(Locale.ROOT), step, ordinal);
    }

    private void tryMatchLocalTempBlock(final long localLayerId, final LocalTempBlockPrediction local) {
        if (local == null || local.effect == null || local.serverLayerId != 0L || local.serverClosed) return;
        final Long serverLayerId = authoritativeTempBlockEffects.get(local.effect);
        if (serverLayerId == null) return;
        final ServerTempBlockPrediction server = authoritativeTempBlockLayers.get(serverLayerId);
        if (server != null) reconcileTempBlockPair(serverLayerId, server, localLayerId, local);
    }

    private void tryMatchServerTempBlock(final long serverLayerId, final ServerTempBlockPrediction server) {
        if (server == null || server.effect == null || pairedServerTempBlocks.containsKey(serverLayerId)) return;
        final Long localLayerId = clientTempBlockEffects.get(server.effect);
        if (localLayerId == null) return;
        final LocalTempBlockPrediction local = clientTempBlockActions.get(localLayerId);
        if (local != null) reconcileTempBlockPair(serverLayerId, server, localLayerId, local);
    }

    private void reconcileTempBlockPair(final long serverLayerId, final ServerTempBlockPrediction server,
                                        final long localLayerId, final LocalTempBlockPrediction local) {
        if (server == null || local == null || !Objects.equals(server.effect, local.effect)) return;
        if (local.serverLayerId != 0L && local.serverLayerId != serverLayerId) {
            unpairServerTempBlock(local.serverLayerId);
        }
        final Long oldLocal = pairedServerTempBlocks.put(serverLayerId, localLayerId);
        if (oldLocal != null && oldLocal != localLayerId) {
            final LocalTempBlockPrediction old = clientTempBlockActions.get(oldLocal);
            if (old != null) old.serverLayerId = 0L;
        }
        local.serverLayerId = serverLayerId;
        pairedTempBlockCoordinates.computeIfAbsent(server.key, ignored -> new HashSet<>()).add(serverLayerId);
        debug("runtime paired semantic TempBlock effect=" + server.effect
                + " serverLayer=" + serverLayerId + " localLayer=" + localLayerId
                + " clientPos=" + local.key.pos + " serverPos=" + server.key.pos
                + " shifted=" + !server.key.equals(local.key));
    }

    private LocalTempBlockPrediction detachLocalTempBlock(final long localLayerId) {
        final LocalTempBlockPrediction local = clientTempBlockActions.remove(localLayerId);
        if (local == null) return null;
        if (local.effect != null) clientTempBlockEffects.remove(local.effect, localLayerId);
        if (local.serverLayerId != 0L) {
            pairedServerTempBlocks.remove(local.serverLayerId, localLayerId);
            final ServerTempBlockPrediction server = authoritativeTempBlockLayers.get(local.serverLayerId);
            final BlockKey serverKey = server == null ? local.key : server.key;
            final Set<Long> atCoordinate = pairedTempBlockCoordinates.get(serverKey);
            if (atCoordinate != null) {
                atCoordinate.remove(local.serverLayerId);
                if (atCoordinate.isEmpty()) pairedTempBlockCoordinates.remove(serverKey);
            }
        }
        return local;
    }

    private void unpairServerTempBlock(final long serverLayerId) {
        final Long localLayerId = pairedServerTempBlocks.remove(serverLayerId);
        final ServerTempBlockPrediction server = authoritativeTempBlockLayers.get(serverLayerId);
        if (server != null) {
            final Set<Long> atCoordinate = pairedTempBlockCoordinates.get(server.key);
            if (atCoordinate != null) {
                atCoordinate.remove(serverLayerId);
                if (atCoordinate.isEmpty()) pairedTempBlockCoordinates.remove(server.key);
            }
        }
        if (localLayerId != null) {
            final LocalTempBlockPrediction local = clientTempBlockActions.get(localLayerId);
            if (local != null && local.serverLayerId == serverLayerId) local.serverLayerId = 0L;
        }
    }

    private static TempBlock findActiveTempBlock(final long layerId) {
        for (TempBlock layer : TempBlock.getActiveLayers()) {
            if (layer.getLayerId() == layerId && !layer.isReverted()) return layer;
        }
        return null;
    }

    private void repaintAuthoritativeTempBlock(final BlockKey key, final BlockState fallback) {
        if (key == null || key.world == null || key.pos == null) return;
        final BlockState local = clientTempBlockState(key.world, key.pos);
        final BlockState desired;
        if (hidesServerTempBlock(key)) {
            // A partial close can reveal another Paper layer owned by the
            // same locally predicted action. It remains hidden even when no
            // exact local layer exists at this coordinate; exposing the
            // ledger's physical top here creates persistent overlap trails.
            desired = desiredTempBlockState(key);
        } else if (local != null) {
            desired = local;
        } else {
            desired = serverTempBlocks.physicalState(key).orElseGet(() -> {
                final ServerDirectBlockMask direct = serverDirectBlockMasks.get(key);
                return direct == null ? fallback : direct.viewerState;
            });
        }
        if (desired != null) key.world.setBlockState(key.pos, desired, 19);
    }

    private void expireUnconfirmedTempBlocks() {
        for (Map.Entry<Long, LocalTempBlockPrediction> entry : List.copyOf(clientTempBlockActions.entrySet())) {
            final LocalTempBlockPrediction local = entry.getValue();
            if (!local.closed && findActiveTempBlock(entry.getKey()) == null) {
                detachLocalTempBlock(entry.getKey());
                continue;
            }
            final long confirmationStart = local.closed ? local.closedTick : local.createdTick;
            if (local.serverLayerId != 0L || local.serverClosed) continue;
            // Accepted client TempBlocks own their full local lifecycle even
            // if authority produced no counterpart (for example, a random
            // cosmetic branch). Only a completed tombstone needs bounded
            // retention in case delayed CREATE metadata is still in flight.
            if (!local.closed) continue;
            // A TempBlock close must outlive latency/jitter, not use the short
            // direct-write confirmation window. Otherwise delayed CREATE
            // metadata can replay server ice/water after the local lifecycle
            // already completed and cause reconsolidation.
            if (tick - confirmationStart <= ACTION_RETENTION_TICKS) continue;
            detachLocalTempBlock(entry.getKey());
            debug("runtime expired unconfirmed TempBlock lifecycle layer=" + entry.getKey()
                    + " closed=" + local.closed + " effect=" + local.effect);
        }
    }

    /**
     * Keeps a running client TempBlock as the visual answer while retaining
     * the newest vanilla state underneath it. A closed prediction is metadata
     * only: until it is semantically paired, it may neither hide unrelated
     * authority nor repaint a coordinate after its local lifecycle ended.
     */
    private boolean preserveClientTempBlockAuthority(final BlockKey key,
                                                     final BlockState authoritativeState) {
        if (key == null || key.world == null || key.pos == null) return false;
        final BlockState localState = clientTempBlockState(key.world, key.pos);
        if (localState != null) {
            rebaseClientTempBlockUnderlay(key, authoritativeState);
            blocks.remove(key);
            return true;
        }
        return false;
    }

    private void rebaseClientTempBlockUnderlay(final BlockKey key,
                                               final BlockState authoritativeState) {
        if (key == null || authoritativeState == null) return;
        final com.projectkorra.projectkorra.platform.mc.block.Block block =
                FabricPredictionMC.block(key.world, key.pos);
        final TempBlock layer = TempBlock.get(block);
        if (layer == null) return;
        final com.projectkorra.projectkorra.platform.mc.block.BlockState snapshot =
                FabricPredictionMC.blockStateSnapshot(key.world, key.pos, authoritativeState);
        if (snapshot != null) {
            layer.setState(snapshot);
            // TempBlock#setState rebases the complete common stack. Mirror
            // that base in every lifecycle record as well, because an
            // external direct write removes the registry before its DISCARD
            // callback reaches this runtime.
            for (LocalTempBlockPrediction local : clientTempBlockActions.values()) {
                if (local != null && !local.closed && key.equals(local.key)) {
                    local.authoritativeUnderlay = authoritativeState;
                }
            }
        }
    }

    private LocalTempBlockPrediction newestClosedClientTempBlock(final BlockKey key) {
        long newestRevision = Long.MIN_VALUE;
        long newestTick = Long.MIN_VALUE;
        long newestLayer = Long.MIN_VALUE;
        LocalTempBlockPrediction newest = null;
        for (Map.Entry<Long, LocalTempBlockPrediction> entry : clientTempBlockActions.entrySet()) {
            final LocalTempBlockPrediction local = entry.getValue();
            if (local == null || !local.closed || !key.equals(local.key)) continue;
            if (local.closedRevision < newestRevision
                    || local.closedRevision == newestRevision && local.closedTick < newestTick
                    || local.closedRevision == newestRevision && local.closedTick == newestTick
                    && entry.getKey() < newestLayer) continue;
            newestRevision = local.closedRevision;
            newestTick = local.closedTick;
            newestLayer = entry.getKey();
            newest = local;
        }
        return newest;
    }

    private BlockState desiredTempBlockState(final BlockKey key) {
        if (showServerTempBlocks) {
            final Optional<BlockState> physical = serverTempBlocks.physicalState(key);
            if (physical.isPresent()) return physical.get();
        }
        final ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (hidesServerTempBlock(key) && player != null) {
            final Optional<BlockState> overlay = serverTempBlocks.overlayState(key, player.getUuid());
            if (overlay.isPresent()) return overlay.get();
        }
        final BlockState local = clientTempBlockState(key.world, key.pos);
        if (local != null) return local;
        // A semantically paired server layer may be at a different coordinate
        // from the client prediction. At that server coordinate the correct
        // display is Paper's computed underlay, never its physical TempBlock.
        if (hasSemanticTempBlockPair(key)) {
            final BlockState closedPrediction = closedClientTempBlockState(key);
            if (closedPrediction != null) return closedPrediction;
        }
        if (hidesServerTempBlock(key)) {
            final Optional<BlockState> viewerState = serverTempBlocks.viewerState(key);
            if (viewerState.isPresent()) return viewerState.get();
        }
        final Optional<BlockState> physical = serverTempBlocks.physicalState(key);
        if (physical.isPresent()) return physical.get();
        final Optional<BlockState> serverViewer = serverTempBlocks.viewerState(key);
        if (serverViewer.isPresent()) return serverViewer.get();
        final ServerDirectBlockMask direct = serverDirectBlockMasks.get(key);
        return direct == null ? key.world.getBlockState(key.pos) : direct.viewerState;
    }

    private BlockState closedClientTempBlockState(final BlockKey key) {
        final LocalTempBlockPrediction local = newestClosedClientTempBlock(key);
        return local == null ? null : local.closedState;
    }

    private static BlockState clientTempBlockState(final ClientWorld world, final BlockPos pos) {
        if (world == null || pos == null) return null;
        final com.projectkorra.projectkorra.platform.mc.block.Block block = FabricPredictionMC.block(world, pos);
        final TempBlock layer = TempBlock.get(block);
        return layer == null ? null : materialState(TempBlockSync.encode(layer.getBlockData()));
    }

    private void repaintAllAuthoritativeTempBlocks() {
        final Set<BlockKey> coordinates = new HashSet<>();
        for (ServerTempBlockPrediction server : authoritativeTempBlockLayers.values()) {
            if (server != null && server.key != null) coordinates.add(server.key);
        }
        for (LocalTempBlockPrediction local : clientTempBlockActions.values()) {
            if (local != null && local.key != null) coordinates.add(local.key);
        }
        coordinates.addAll(tempBlockTeardownFences.keys());
        for (BlockKey key : coordinates) {
            if (key.world == null || key.pos == null) continue;
            final BlockState current = key.world.getBlockState(key.pos);
            final Optional<BlockState> fenced = showServerTempBlocks
                    ? Optional.empty() : tempBlockTeardownFences.audit(key, current);
            final BlockState desired = fenced.isPresent()
                    ? composeTempBlockTeardownView(key, fenced.get())
                    : desiredTempBlockState(key);
            if (desired != null && !desired.equals(current)) {
                key.world.setBlockState(key.pos, desired, 19);
            }
        }
        debug("runtime server TempBlock debug=" + showServerTempBlocks
                + " repainted=" + coordinates.size());
    }

    private List<PredictionDesyncBlock> ownedTempDesyncs0(final ClientWorld world) {
        // A different physical server TempBlock is expected and intentionally
        // hidden. It is not a desync and should never render a rollback marker.
        return List.of();
    }

    private static void debug(String message) {
        if (DEBUG) System.out.println("[ProjectKorraPrediction] " + message);
    }

    private static final class Action {
        final long sequence;
        final long createdTick;
        final Vec3d origin;
        final float yaw;
        final float pitch;
        final double eyeHeight;
        final String inputAbility;
        final PredictionPayloads.InputKind kind;
        final int selectedSlot;
        final long deterministicSeed;
        final Set<CoreAbility> abilities = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Entity> spawned = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<CoreAbility, Long> previousAbilityActions = new IdentityHashMap<>();
        final Map<Integer, Integer> velocityOrdinals = new HashMap<>();
        final Map<Integer, Integer> abilityStateOrdinals = new HashMap<>();
        final Map<String, Integer> directBlockOrdinals = new HashMap<>();
        int tempFallingBlockOrdinal;
        int tempBlockOrdinal;
        boolean reconciled;
        boolean nativeConfirmed;
        boolean locallyPredicted;
        int blockConfirmationTicks = BLOCK_CONFIRMATION_TICKS;
        private Action(long sequence, long createdTick, Vec3d origin, float yaw, float pitch,
                       double eyeHeight, String inputAbility, PredictionPayloads.InputKind kind,
                       int selectedSlot) {
            this.sequence = sequence;
            this.createdTick = createdTick;
            this.origin = origin;
            this.yaw = yaw;
            this.pitch = pitch;
            this.eyeHeight = eyeHeight;
            this.inputAbility = inputAbility == null ? "" : inputAbility;
            this.kind = kind;
            this.selectedSlot = selectedSlot;
            this.deterministicSeed = PredictionActionSeed.from(
                    kind == null ? "" : kind.name(), selectedSlot, this.inputAbility,
                    origin.x, origin.y, origin.z, yaw, pitch);
        }
    }

    private record AirBlastTraceKey(long actionSequence, int eventOrdinal) { }
    private record BlockKey(ClientWorld world, BlockPos pos) { }
    private record DirectBlockCauseKey(long actionSequence, String ability) { }
    private record DirectBlockEffectKey(long actionSequence, String ability, int mutationOrdinal) { }
    private static final class PredictedDirectCause {
        int lastOrdinal;
        long lastTick;
    }
    private static final class PredictedDirectBlock {
        final BlockKey key;
        final BlockState before;
        final BlockState after;
        final long createdTick;
        final long visualRevision;
        boolean vanillaConfirmed;

        private PredictedDirectBlock(BlockKey key, BlockState before, BlockState after,
                                     long createdTick, long visualRevision) {
            this.key = key;
            this.before = before;
            this.after = after;
            this.createdTick = createdTick;
            this.visualRevision = visualRevision;
        }
    }
    private record RecentDirectVisual(DirectBlockEffectKey effect, BlockState state,
                                      long createdTick, long revision) { }
    private record ConfirmedDirectBlockPacket(long serverTick, BlockKey key, BlockState state,
                                              DirectBlockCauseKey cause, UUID ownerId,
                                              long localVisualRevision, long observedVisualRevision,
                                              BlockState serverUnderlay, long receivedTick) { }
    private record ServerDirectBlockMask(BlockState serverState, BlockState viewerState,
                                         DirectBlockCauseKey cause, UUID ownerId,
                                         long updatedTick) { }
    private record TempBlockEffectKey(long actionSequence, String ability, long step, int ordinal) { }
    private static final class CapturedAbilityTempBlock {
        BlockState underlay;
        final Set<BlockState> staleStates = new HashSet<>();

        private void addStale(final BlockState state) {
            if (state != null) staleStates.add(state);
        }
    }
    private static final class LocalTempBlockPrediction {
        final long actionSequence;
        final BlockKey key;
        final TempBlockEffectKey effect;
        final long createdTick;
        final Set<BlockState> createdStates = new HashSet<>();
        final BlockState initialUnderlay;
        final CoreAbility owner;
        long serverLayerId;
        boolean closed;
        boolean serverClosed;
        long closedTick;
        long closedRevision;
        BlockState closedState;
        BlockState authoritativeUnderlay;

        private LocalTempBlockPrediction(final long actionSequence, final BlockKey key,
                                         final TempBlockEffectKey effect, final long createdTick,
                                         final BlockState createdState, final BlockState initialUnderlay,
                                         final CoreAbility owner) {
            this.actionSequence = actionSequence;
            this.key = key;
            this.effect = effect;
            this.createdTick = createdTick;
            if (createdState != null) this.createdStates.add(createdState);
            this.initialUnderlay = initialUnderlay;
            this.owner = owner;
        }
    }
    private record ServerTempBlockPrediction(long actionSequence, BlockKey key,
                                             TempBlockEffectKey effect, String effectAbility, UUID ownerId,
                                             BlockState physicalState, boolean hiddenForLocalViewer) { }
    private record CompletedTempBlockRestore(BlockState expectedState, BlockState state,
                                             boolean followLiveClientState,
                                             long tick, long localLayerId) { }
    private record TempFallingBlockKey(long actionSequence, int spawnOrdinal) { }
    private record PredictedTempFallingBlock(Entity entity, String ability) { }
    private record ServerTempFallingPrepare(PredictionPayloads.TempFallingBlockPrepare prepare,
                                            long receivedTick) { }
    public record PredictionDesyncBlock(BlockPos pos, BlockState predicted, BlockState authoritative) { }
    private static final class BlockMutation {
        final ClientWorld world; final BlockPos pos;
        BlockState predicted;
        long lastAction; long lastTick; boolean locallyPredicted;
        private BlockMutation(ClientWorld world, BlockPos pos) {
            this.world = world;
            this.pos = pos;
        }
    }
    private static final class VelocityMutation {
        final ClientWorld world; final int entityId; final long action; final int impulseOrdinal; final String ability;
        final long tick; final Vec3d before; final Vec3d predicted;
        private VelocityMutation(ClientWorld world, int entityId, long action, int impulseOrdinal, String ability,
                                 long tick, Vec3d before, Vec3d predicted) {
            this.world = world; this.entityId = entityId; this.action = action; this.impulseOrdinal = impulseOrdinal;
            this.ability = ability == null ? "" : ability; this.tick = tick; this.before = before; this.predicted = predicted;
        }
    }
    private record VelocityReceipt(long serverTick, long actionSequence, int entityId, int impulseOrdinal,
                                   UUID abilityOwner, String ability, long receivedTick) { }
    private record PendingExternalVelocity(Vec3d velocity, long serverTick, String ability,
                                           long receivedTick) { }
    private record AbilityStateMutation(long tick, long actionSequence, int mutationOrdinal) { }
    private record AbilityStateReceipt(long serverTick, long actionSequence, int mutationOrdinal,
                                       UUID abilityOwner, UUID target, String ability,
                                       boolean flying, boolean allowFlight, float flySpeed,
                                       long receivedTick) { }
    private static final class ExperienceMutation {
        final long tick; final float barProgress; final int experience; final int level;
        private ExperienceMutation(long tick, float barProgress, int experience, int level) {
            this.tick = tick; this.barProgress = barProgress; this.experience = experience; this.level = level;
        }
    }
}
