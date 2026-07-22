package com.projectkorra.projectkorra.fabric.client;

import com.jedk1.jedcore.ability.passive.WallRun;
import com.projectkorra.projectkorra.BendingManager;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.BendingManager.TempElementsRunnable;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager.TrackingResult;
import com.projectkorra.projectkorra.ability.util.CollisionInitializer;
import com.projectkorra.projectkorra.ability.util.CollisionManager;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.ability.util.EmbeddedAddonBootstrap;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.airbending.AirBlast;
import com.projectkorra.projectkorra.chiblocking.util.ChiblockingManager;
import com.projectkorra.projectkorra.earthbending.EarthSmash;
import com.projectkorra.projectkorra.earthbending.RaiseEarth;
import com.projectkorra.projectkorra.earthbending.EarthTunnel;
import com.projectkorra.projectkorra.earthbending.EarthSmash.PredictionBlock;
import com.projectkorra.projectkorra.earthbending.EarthSmash.PredictionTransfer;
import com.projectkorra.projectkorra.earthbending.util.EarthbendingManager;
import com.projectkorra.projectkorra.fabric.client.prediction.action.ClientNativeActionCorrelation;
import com.projectkorra.projectkorra.fabric.client.prediction.block.ClientDirectBlockAuthority;
import com.projectkorra.projectkorra.fabric.client.prediction.block.ClientTempBlockAuthority;
import com.projectkorra.projectkorra.fabric.client.prediction.config.ClientPredictionConfig;
import com.projectkorra.projectkorra.fabric.client.prediction.entity.ClientEntityReconciliation;
import com.projectkorra.projectkorra.fabric.client.prediction.movement.ClientVelocityAuthority;
import com.projectkorra.projectkorra.fabric.client.prediction.state.ClientPlayerStateAuthority;
import com.projectkorra.projectkorra.fabric.client.prediction.state.PredictionCooldownAuthority;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.AbilityRemoved;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.AbilityStateOwner;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.AbilityTransfer;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.ConfigEntry;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.DirectBlockReceipt;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.InputKind;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.NativeAction;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.TempBlockBatch;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.TempFallingBlockPrepare;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.TempFallingBlockReceipt;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.VelocityOwner;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.VelocityOwnerV2;
import com.projectkorra.projectkorra.firebending.FireBlastCharged;
import com.projectkorra.projectkorra.firebending.util.FirebendingManager;
import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.listener.CommonPlayerListenerCore;
import com.projectkorra.projectkorra.listener.CommonInputHandler.SlotResult;
import com.projectkorra.projectkorra.listener.CommonPlayerListenerCore.MovementResult;
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
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.action.PredictionActionSeed;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority;
import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority.Snapshot;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;
import com.projectkorra.projectkorra.prediction.block.TempFallingBlockSync;
import com.projectkorra.projectkorra.prediction.hit.PredictedContactSync;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import com.projectkorra.projectkorra.prediction.state.PredictionStateOrdering;
import com.projectkorra.projectkorra.prediction.state.CooldownSync.Listener;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.Cooldown;
import com.projectkorra.projectkorra.util.CooldownDisplayHandler;
import com.projectkorra.projectkorra.util.FallHandler;
import com.projectkorra.projectkorra.util.FlightHandler;
import com.projectkorra.projectkorra.util.RegenHandler;
import com.projectkorra.projectkorra.util.RevertChecker;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import com.projectkorra.projectkorra.waterbending.blood.Bloodbending;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;
import com.projectkorra.projectkorra.waterbending.util.WaterbendingManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.logging.Level;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class ExactPredictionRuntime
        implements Listener,
        com.projectkorra.projectkorra.prediction.block.TempFallingBlockSync.Listener,
        com.projectkorra.projectkorra.prediction.hit.PredictedContactSync.Listener {
    private static final com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime INSTANCE = new com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime();
    private static final ThreadLocal<Long> INPUT_ACTION = new ThreadLocal<>();
    private static final ThreadLocal<Long> INPUT_EVENT_POSE = new ThreadLocal<>();

    private static final Set<String> PERSISTENT_FLIGHT_ABILITIES = Set.of("airscooter", "airspout", "waterspout", "firejet", "flight");
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("projectkorra.prediction.debug", "false"));
    private final Map<Long, com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action> actions = new LinkedHashMap<>();
    private final Map<CoreAbility, Long> abilityActions = new IdentityHashMap<>();
    private final Map<CoreAbility, Long> abilityCreationActions = new IdentityHashMap<>();
    private final Set<CoreAbility> authoritativelyEstablishedAbilities = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<String> abilityRemovalHistory = new ArrayList<>();
    private final ClientNativeActionCorrelation nativeActions = new ClientNativeActionCorrelation();
    private Set<String> authoritativeFlightAbilities = Set.of();
    private long authoritativeFlightSequence = -1L;
    private Set<String> grantedPermissions = Set.of();
    private final ClientVelocityAuthority velocityAuthority = new ClientVelocityAuthority(
            com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime::debug
    );
    private final ClientEntityReconciliation entityReconciliation = new ClientEntityReconciliation(
            com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime::materialState
    );
    private final ClientDirectBlockAuthority directBlockAuthority;
    private final ClientTempBlockAuthority tempBlockAuthority;
    private final ClientPlayerStateAuthority playerStateAuthority =
            new ClientPlayerStateAuthority(ExactPredictionRuntime::debug);
    private final PredictionCooldownAuthority cooldownAuthority = new PredictionCooldownAuthority();
    private FabricClientPredictionPlatform platform;
    private BendingManager bendingManager;
    private BendingPlayer bendingPlayer;
    private long tick;
    private boolean ready;
    private boolean initializing;
    private boolean managersStarted;
    private boolean commonRuntimeInstalled;
    private boolean discardingWorldState;
    private String lastStartFailure = "";

    private ExactPredictionRuntime() {
        this.directBlockAuthority = new ClientDirectBlockAuthority(
                new ClientDirectBlockAuthority.Context() {
                    @Override
                    public long currentAction() {
                        return ExactPredictionRuntime.this.currentAction();
                    }

                    @Override
                    public long tick() {
                        return ExactPredictionRuntime.this.tick;
                    }

                    @Override
                    public String inputAbility(long actionSequence) {
                        com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action = ExactPredictionRuntime.this.actions.get(actionSequence);
                        return action == null ? "" : action.inputAbility;
                    }

                    @Override
                    public void markMutation(long actionSequence, String ability, int ordinal) {
                        com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action = ExactPredictionRuntime.this.actions.get(actionSequence);
                        if (action != null) {
                            action.directBlockOrdinals.put(ability, ordinal);
                        }
                    }

                    @Override
                    public boolean hasAction(long actionSequence) {
                        return ExactPredictionRuntime.this.actions.containsKey(actionSequence);
                    }

                    @Override
                    public boolean hasActiveAbility(long actionSequence, String abilityName) {
                        for (Entry<CoreAbility, Long> entry : ExactPredictionRuntime.this.abilityActions.entrySet()) {
                            CoreAbility ability = entry.getKey();
                            if (entry.getValue() == actionSequence && ability != null && !ability.isRemoved() && ability.getName().equalsIgnoreCase(abilityName)) {
                                return true;
                            }
                        }

                        return false;
                    }

                    @Override
                    public int confirmationTicks(long actionSequence) {
                        return ExactPredictionRuntime.this.blockConfirmationTicks(actionSequence);
                    }
                },
                com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime::materialState,
                com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime::debug
        );
        this.tempBlockAuthority = new ClientTempBlockAuthority(
                new ClientTempBlockAuthority.Context() {
                    @Override
                    public boolean ready() {
                        return ExactPredictionRuntime.this.ready;
                    }

                    @Override
                    public long tick() {
                        return ExactPredictionRuntime.this.tick;
                    }

                    @Override
                    public long currentAction() {
                        return ExactPredictionRuntime.this.currentAction();
                    }

                    @Override
                    public long actionForAbility(CoreAbility ability) {
                        return ExactPredictionRuntime.this.abilityActions.getOrDefault(ability, 0L);
                    }

                    @Override
                    public String inputAbility(long actionSequence) {
                        Action action = ExactPredictionRuntime.this.actions.get(actionSequence);
                        return action == null ? "" : action.inputAbility;
                    }

                    @Override
                    public int nextTempBlockOrdinal(long actionSequence) {
                        Action action = ExactPredictionRuntime.this.actions.get(actionSequence);
                        return action == null ? 0 : ++action.tempBlockOrdinal;
                    }

                    @Override
                    public long localActionSequence(long paperSequence) {
                        return ExactPredictionRuntime.this.localActionSequence(paperSequence);
                    }
                },
                this.directBlockAuthority,
                com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime::materialState,
                com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime::debug
        );
    }

    public static boolean start(
            MinecraftClient client,
            List<ConfigEntry> config,
            Map<Integer, String> binds,
            Map<String, Long> cooldowns,
            List<String> elements,
            List<String> subElements,
            List<String> permissions,
            double airBlastDecay,
            boolean chiBlocked,
            Snapshot regionProtection
    ) {
        return INSTANCE.start0(client, config, binds, cooldowns, elements, subElements, permissions, airBlastDecay, chiBlocked, regionProtection);
    }

    public static void updatePlayerState(
            Map<Integer, String> binds,
            Map<String, Long> cooldowns,
            List<String> elements,
            List<String> subElements,
            List<String> permissions,
            double airBlastDecay,
            boolean chiBlocked,
            Snapshot regionProtection
    ) {
        INSTANCE.updatePlayerState0(binds, cooldowns, elements, subElements, permissions, airBlastDecay, chiBlocked, regionProtection);
    }

    public static boolean hasPermission(String permission) {
        if ((INSTANCE.ready || INSTANCE.initializing) && permission != null && !permission.isBlank()) {
            String normalized = permission.toLowerCase(Locale.ROOT);
            if (!INSTANCE.grantedPermissions.contains("*") && !INSTANCE.grantedPermissions.contains(normalized)) {
                for (String granted : INSTANCE.grantedPermissions) {
                    if (granted.endsWith(".*") && normalized.startsWith(granted.substring(0, granted.length() - 1))) {
                        return true;
                    }
                }

                return false;
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    public static void reconcileActiveFlightAbilities(List<String> activeAbilities, long acknowledgedSequence) {
        INSTANCE.reconcileActiveFlightAbilities0(activeAbilities, acknowledgedSequence);
    }

    public static void tick(MinecraftClient client) {
        INSTANCE.tick0(client);
    }

    public static void stop(MinecraftClient client) {
        INSTANCE.stop0(client);
    }

    public static boolean isReady() {
        return INSTANCE.ready;
    }

    public static String lastStartFailure() {
        return INSTANCE.lastStartFailure;
    }

    public static boolean supports(String abilityName) {
        return INSTANCE.ready
                && abilityName != null
                && (
                CoreAbility.getAbility(abilityName) != null
                        || abilityName.equalsIgnoreCase("FireBlastCharged") && CoreAbility.getAbility(FireBlastCharged.class) != null
        );
    }

    public static List<String> supportedAbilities() {
        if (!INSTANCE.ready) {
            return List.of();
        }

        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        for (CoreAbility ability : CoreAbility.getAbilities()) {
            if (ability != null && ability.getName() != null && !ability.getName().isBlank()) {
                names.add(ability.getName());
            }
        }

        if (CoreAbility.getAbility(FireBlastCharged.class) != null) {
            names.add("FireBlastCharged");
        }

        return List.copyOf(names);
    }

    public static boolean shouldPredictInput(String abilityName, InputKind kind) {
        return supports(abilityName);
    }

    public static boolean canActivate(String abilityName) {
        return supports(abilityName) && INSTANCE.bendingPlayer != null && !INSTANCE.bendingPlayer.isOnCooldown(abilityName);
    }

    public static boolean isOnLocalCooldown(String abilityName) {
        return supports(abilityName) && INSTANCE.bendingPlayer != null ? INSTANCE.bendingPlayer.isOnCooldown(abilityName) : false;
    }

    public static boolean isInputCooldownActive(String abilityName, InputKind kind) {
        if (!supports(abilityName) || INSTANCE.bendingPlayer == null || abilityName == null || abilityName.isBlank()) {
            return false;
        }

        if (INSTANCE.bendingPlayer.isOnCooldown(abilityName)) {
            return true;
        }

        if (abilityName.equalsIgnoreCase("PhaseChange")) {
            return switch (kind) {
                case LEFT_CLICK -> INSTANCE.bendingPlayer.isOnCooldown("PhaseChangeFreeze");
                case SNEAK_START -> INSTANCE.bendingPlayer.isOnCooldown("PhaseChangeMelt");
                default -> false;
            };
        } else {
            return false;
        }
    }

    public static void removeLocalCooldown(String abilityName) {
        if (INSTANCE.ready && INSTANCE.bendingPlayer != null && abilityName != null && !abilityName.isBlank()) {
            if (INSTANCE.cooldownAuthority.isLocallyPredicted(abilityName)) {
                debug("runtime ignored stale server cooldown removal over newer local generation ability=" + abilityName);
                return;
            }

            INSTANCE.bendingPlayer.removeCooldown(abilityName);
        }
    }

    public static void enforceLocalCooldown(String abilityName, long clientUntilMillis) {
        if ((INSTANCE.ready || INSTANCE.initializing) && INSTANCE.bendingPlayer != null && abilityName != null && !abilityName.isBlank()) {
            long now = System.currentTimeMillis();
            long existing = INSTANCE.bendingPlayer.getCooldown(abilityName);
            if (clientUntilMillis > now && clientUntilMillis > existing) {
                Cooldown current = (Cooldown) INSTANCE.bendingPlayer.getCooldowns().get(abilityName);
                INSTANCE.bendingPlayer.getCooldowns().put(abilityName, new Cooldown(clientUntilMillis, current != null && current.isDatabase()));
                debug(
                        "runtime extended predicted cooldown to Paper expiry ability=" + abilityName + " previous=" + existing + " authoritative=" + clientUntilMillis
                );
            }
        }
    }

    public static void resetLocalAirBlast() {
        if (INSTANCE.ready && INSTANCE.bendingPlayer != null) {
            INSTANCE.bendingPlayer.resetAirBlast();
        }
    }

    public static void setLocalAirBlastDecay(double value) {
        if (INSTANCE.ready && INSTANCE.bendingPlayer != null && Double.isFinite(value)) {
            INSTANCE.bendingPlayer.setAirBlastDecay(Math.max(0.0, Math.min(1.0, value)));
        }
    }

    public static String inputAbilityName(int selectedSlot, String fallback, InputKind kind) {
        return INSTANCE.inputAbilityName0(selectedSlot, fallback, kind);
    }

    public static boolean shouldTrackDrop() {
        return INSTANCE.ready && INSTANCE.bendingPlayer != null && CommonInputHandler.shouldTrackDrop(INSTANCE.bendingPlayer.getPlayer());
    }

    public static void prepareOffHandRightClickEntity() {
        if (INSTANCE.ready && INSTANCE.bendingPlayer != null) {
            CommonInputHandler.prepareRightClickEntity(INSTANCE.bendingPlayer.getPlayer());
        }
    }

    public static boolean input(long sequence, InputKind kind, int selectedSlot, com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose pose) {
        return INSTANCE.input0(sequence, kind, selectedSlot, pose);
    }

    public static void recordNativeOnlyInput(
            long sequence, InputKind kind, int selectedSlot, com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose pose, String ability
    ) {
        INSTANCE.recordNativeOnlyInput0(sequence, kind, selectedSlot, pose, ability);
    }

    public static boolean noteNativeAction(NativeAction action) {
        return INSTANCE.noteNativeAction0(action);
    }

    public static long correlatedLocalActionSequence(long paperSequence) {
        return INSTANCE.localActionSequence(paperSequence);
    }

    static List<String> abilityRemovalReport() {
        return INSTANCE.abilityRemovalReport0();
    }

    static List<String> tempBlockReport() {
        return INSTANCE.tempBlockReport0();
    }

    public static boolean isNativeActionConfirmed(long sequence) {
        com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action = INSTANCE.actions.get(sequence);
        return INSTANCE.ready && action != null && action.nativeConfirmed;
    }

    public static com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose executionPose() {
        return INSTANCE.executionPose0();
    }

    public static void predictMovement(
            MinecraftClient client,
            com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose from,
            com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose to
    ) {
        INSTANCE.predictMovement0(client, from, to);
    }

    public static void reconcile(
            long sequence, Vec3d authoritativeOrigin, String ability, long cooldownUntil, boolean inputHandled, boolean comboRecorded, List<String> createdAbilities
    ) {
        INSTANCE.reconcile0(sequence, authoritativeOrigin, ability, cooldownUntil, inputHandled, comboRecorded, createdAbilities);
    }

    public static BlockState blockState(ClientWorld world, BlockPos pos) {
        return INSTANCE.directBlockAuthority.simulatedState(world, pos.toImmutable());
    }

    public static void setPredictedBlock(ClientWorld world, BlockPos pos, BlockState state) {
        if (!INSTANCE.discardingWorldState) {
            if (TempBlockSync.currentWorldMutation() != null) {
                INSTANCE.tempBlockAuthority.predict(world, pos.toImmutable(), state);
            } else if (INSTANCE.ready) {
                INSTANCE.directBlockAuthority.predict(world, pos.toImmutable(), state);
            }
        }
    }

    public static boolean authoritativeBlock(ClientWorld world, BlockPos pos, BlockState state) {
        return INSTANCE.tempBlockAuthority.acceptBlock(world, pos.toImmutable(), state);
    }

    public static boolean authoritativeBlockBatch(ClientWorld world, List<BlockPos> positions, List<BlockState> states) {
        return INSTANCE.tempBlockAuthority.acceptBatch(world, positions, states);
    }

    public static void acceptAuthoritativeChunk(ClientWorld world, int chunkX, int chunkZ) {
        INSTANCE.tempBlockAuthority.acceptChunk(world, chunkX, chunkZ);
    }

    public static void applyTempBlockBatch(ClientWorld world, TempBlockBatch batch) {
        INSTANCE.tempBlockAuthority.applyAuthoritativeBatch(world, batch);
    }

    public static void noteDirectBlock(Entity localPlayer, DirectBlockReceipt receipt) {
        INSTANCE.noteDirectBlock0(localPlayer, receipt);
    }

    public static List<com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.PredictionDesyncBlock> ownedTempDesyncs(ClientWorld world) {
        return List.of();
    }

    public static void setPredictedVelocity(Entity entity, Vec3d velocity) {
        INSTANCE.setVelocity0(entity, velocity);
    }

    public static void noteVelocityOwner(Entity localPlayer, VelocityOwner owner) {
        INSTANCE.noteVelocityOwner0(localPlayer, owner);
    }

    public static void noteVelocityOwner(Entity localPlayer, VelocityOwnerV2 owner) {
        INSTANCE.noteVelocityOwner0(localPlayer, owner);
    }

    public static void noteAbilityStateOwner(Entity localPlayer, AbilityStateOwner owner) {
        INSTANCE.noteAbilityStateOwner0(localPlayer, owner);
    }

    public static void noteTempFallingBlock(Entity localPlayer, TempFallingBlockReceipt receipt) {
        INSTANCE.noteTempFallingBlock0(localPlayer, receipt);
    }

    public static void noteTempFallingBlockPrepare(Entity localPlayer, TempFallingBlockPrepare prepare) {
        INSTANCE.noteTempFallingBlockPrepare0(localPlayer, prepare);
    }

    public static void removeAuthoritativeAbility(Entity localPlayer, AbilityRemoved removed) {
        INSTANCE.removeAuthoritativeAbility0(localPlayer, removed);
    }

    public static void transferAuthoritativeAbility(Entity localPlayer, AbilityTransfer transfer) {
        INSTANCE.transferAuthoritativeAbility0(localPlayer, transfer);
    }

    static boolean removalReceiptMayResolve(boolean externallyCaused, boolean actionRetained, boolean nativeActionConfirmed) {
        return externallyCaused || actionRetained && nativeActionConfirmed;
    }

    static boolean authoritativeEmptyTypeFenceCoversCandidate(
            boolean externallyCaused, int remainingTypeInstances, long localAcknowledgedSequence, Long candidateLatestSequence
    ) {
        return externallyCaused
                && remainingTypeInstances == 0
                && localAcknowledgedSequence > 0L
                && candidateLatestSequence != null
                && candidateLatestSequence <= localAcknowledgedSequence;
    }

    public static boolean authoritativeVelocity(int entityId, Vec3d velocity) {
        return INSTANCE.authoritativeVelocity0(entityId, velocity);
    }

    public static void notePredictedAbilityState(
            boolean invulnerable, boolean flying, boolean allowFlying, boolean creativeMode, float flySpeed, float walkSpeed
    ) {
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
        return INSTANCE.ready && INSTANCE.entityReconciliation.suppressAuthoritativeData(entityId);
    }

    public static boolean suppressAuthoritativeBreakAnimation(ClientWorld world, BlockPos pos) {
        return INSTANCE.ready && (INSTANCE.directBlockAuthority.suppressBreakAnimation(world, pos)
                || INSTANCE.tempBlockAuthority.suppressBreakAnimation(world, pos));
    }

    public static boolean suppressLocalBlockBreaking(ClientWorld world, BlockPos pos) {
        return INSTANCE.ready && INSTANCE.tempBlockAuthority.suppressLocalBreaking(world, pos);
    }

    public static boolean isPredictedOwned(Entity entity) {
        return INSTANCE.entityReconciliation.isPredictedOwned(entity);
    }

    public static void trackSpawn(Entity entity) {
        INSTANCE.trackSpawn0(entity);
    }

    public static boolean reconcileSpawn(EntitySpawnS2CPacket packet) {
        return INSTANCE.reconcileSpawn0(packet);
    }

    public static Entity aliasedEntity(int serverEntityId) {
        return INSTANCE.entityReconciliation.aliasedEntity(serverEntityId);
    }

    public static boolean hasEntityAlias(int serverEntityId) {
        return INSTANCE.entityReconciliation.hasAlias(serverEntityId);
    }

    public static boolean tracksVelocityEntity(int entityId) {
        return INSTANCE.tracksVelocityEntity0(entityId);
    }

    public static boolean removeHiddenEntity(int serverEntityId) {
        return INSTANCE.entityReconciliation.removeHidden(serverEntityId);
    }

    public static boolean removeAliasedEntity(int serverEntityId) {
        return INSTANCE.entityReconciliation.removeAlias(serverEntityId);
    }

    public static boolean toggleServerTempBlockDebug() {
        return INSTANCE.tempBlockAuthority.toggleDebugView();
    }

    public static boolean showsServerTempBlocks() {
        return INSTANCE.tempBlockAuthority.showsServerLayers();
    }

    public static long captureAction() {
        return INSTANCE.currentAction();
    }

    public static void runWithAction(long action, Runnable task) {
        if (action <= 0L) {
            task.run();
        } else {
            Long previous = INPUT_ACTION.get();
            INPUT_ACTION.set(action);
            com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action correlated = INSTANCE.actions.get(action);
            long deterministicSeed = correlated == null ? action : correlated.deterministicSeed;

            try {
                PredictionDeterminism.run(action, deterministicSeed, task);
            } finally {
                if (previous == null) {
                    INPUT_ACTION.remove();
                } else {
                    INPUT_ACTION.set(previous);
                }
            }
        }
    }

    private boolean start0(
            MinecraftClient client,
            List<ConfigEntry> entries,
            Map<Integer, String> binds,
            Map<String, Long> cooldowns,
            List<String> elements,
            List<String> subElements,
            List<String> permissions,
            double airBlastDecay,
            boolean chiBlocked,
            Snapshot regionProtection
    ) {
        debug(
                "runtime start requested ready="
                        + this.ready
                        + " initializing="
                        + this.initializing
                        + " integratedServer="
                        + (client.getServer() != null)
                        + " player="
                        + (client.player != null)
                        + " world="
                        + (client.world != null)
                        + " configEntries="
                        + entries.size()
                        + " binds="
                        + binds
        );
        if (this.ready) {
            ClientPredictionConfig.apply(entries);
            this.updatePlayerState0(binds, cooldowns, elements, subElements, permissions, airBlastDecay, chiBlocked, regionProtection);
            debug("runtime already ready; state refreshed");
            return true;
        }

        if (client.getServer() == null && client.player != null && client.world != null && !this.initializing) {
            this.initializing = true;
            this.grantedPermissions = ClientPredictionConfig.normalizePermissions(permissions);

            try {
                this.platform = new FabricClientPredictionPlatform(client);
                Platform.install(this.platform);
                this.commonRuntimeInstalled = true;
                ProjectKorra.initCommon();
                Manager.startup();
                this.managersStarted = true;
                ClientPredictionConfig.apply(entries);
                ElementalAbility.clearBendableMaterials();
                ElementalAbility.setupBendableMaterials();
                EarthTunnel.clearBendableMaterials();
                EarthTunnel.setupBendableMaterials();
                Bloodbending.loadBloodlessFromConfig();
                this.bendingManager = new BendingManager();
                Platform.scheduler().runTimer(this.bendingManager, 0L, 1L);
                Platform.scheduler().runTimer(new WaterbendingManager(ProjectKorra.plugin), 0L, 1L);
                Platform.scheduler().runTimer(new EarthbendingManager(ProjectKorra.plugin), 0L, 1L);
                Platform.scheduler().runTimer(new FirebendingManager(ProjectKorra.plugin), 0L, 1L);
                Platform.scheduler().runTimer(new ChiblockingManager(ProjectKorra.plugin), 0L, 1L);
                Platform.scheduler().runTimer(new CooldownDisplayHandler(), 0L, 1L);
                Platform.scheduler().runTimer(new RegenHandler(ProjectKorra.plugin), 0L, 20L);
                Platform.scheduler().runTimer(new TempElementsRunnable(), 20L, 20L);
                ProjectKorra.plugin.revertChecker = Platform.scheduler().runTimerAsync(new RevertChecker(ProjectKorra.plugin), 0L, 200L);
                new MultiAbilityManager();
                new ComboManager();
                if (ProjectKorra.collisionManager != null) {
                    ProjectKorra.collisionManager.stopCollisionDetection();
                }

                ProjectKorra.collisionManager = new CollisionManager();
                ProjectKorra.collisionInitializer = new CollisionInitializer(ProjectKorra.collisionManager);
                CoreAbility.registerAbilities();
                EmbeddedAddonBootstrap.enable();
                ClientPredictionConfig.apply(entries);
                AbilityActivationManager.reload();
                ComboManager.registerCombos();
                FallHandler.loadNoFallDamageAbilities();
                ProjectKorra.collisionInitializer.initializeDefaultCollisions();
                Player player = FabricPredictionMC.player(client.player);
                this.bendingPlayer = new BendingPlayer(player);
                BendingPlayer.getPlayers().put(player.getUniqueId(), this.bendingPlayer);
                BendingPlayer.getOfflinePlayers().put(player.getUniqueId(), this.bendingPlayer);
                CooldownSync.install(this);
                TempBlockSync.install(this.tempBlockAuthority);
                TempFallingBlockSync.install(this);
                PredictedContactSync.install(this);
                this.updatePlayerState0(binds, cooldowns, elements, subElements, permissions, airBlastDecay, chiBlocked, regionProtection);
                this.ready = true;
                this.lastStartFailure = "";
                ProjectKorra.log.info("Exact client prediction enabled with " + CoreAbility.getAbilities().size() + " local abilities");
                debug(
                        "runtime ready abilities="
                                + CoreAbility.getAbilities().size()
                                + " activeInstances="
                                + CoreAbility.getAbilitiesByInstances().size()
                                + " playerElements="
                                + this.bendingPlayer.getElements()
                                + " playerSubElements="
                                + this.bendingPlayer.getSubElements()
                );
                return true;
            } catch (Throwable failure) {
                this.lastStartFailure = failure.getClass().getSimpleName()
                        + (failure.getMessage() != null && !failure.getMessage().isBlank() ? ": " + failure.getMessage() : "");
                ProjectKorra.log.log(Level.SEVERE, "Exact client prediction could not start", failure);
                debug("runtime start failed " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                this.stop0(client);
                return false;
            } finally {
                this.initializing = false;
            }
        } else {
            debug(
                    "runtime start refused integratedServer="
                            + (client.getServer() != null)
                            + " player="
                            + (client.player != null)
                            + " world="
                            + (client.world != null)
                            + " initializing="
                            + this.initializing
            );
            return false;
        }
    }


    private void updatePlayerState0(
            Map<Integer, String> binds,
            Map<String, Long> cooldowns,
            List<String> elements,
            List<String> subElements,
            List<String> permissions,
            double airBlastDecay,
            boolean chiBlocked,
            Snapshot regionProtection
    ) {
        this.grantedPermissions = ClientPredictionConfig.normalizePermissions(permissions);
        if ((this.ready || this.initializing) && this.bendingPlayer != null) {
            if (!MultiAbilityManager.hasMultiAbilityBound(this.bendingPlayer.getPlayer())) {
                this.bendingPlayer.getAbilities().clear();
                this.bendingPlayer.getAbilities().putAll(binds);
            }

            this.bendingPlayer.getElements().clear();

            for (String name : elements) {
                Element element = Element.getElement(name);
                if (element != null && !(element instanceof SubElement)) {
                    this.bendingPlayer.getElements().add(element);
                }
            }

            this.bendingPlayer.getSubElements().clear();

            for (String name : subElements) {
                if (Element.getElement(name) instanceof SubElement subElement) {
                    this.bendingPlayer.getSubElements().add(subElement);
                }
            }

            if (chiBlocked) {
                this.bendingPlayer.blockChi();
            } else {
                this.bendingPlayer.unblockChi();
            }

            RegionProtectionAuthority.install(this.bendingPlayer.getPlayer(), regionProtection);
            PassiveManager.registerPassives(this.bendingPlayer.getPlayer());
            this.reconcileAuthoritativeCooldowns(cooldowns);
            if (this.initializing || !this.ready) {
                this.bendingPlayer.setAirBlastDecay(airBlastDecay);
            }

            debug(
                    "runtime state applied binds="
                            + this.bendingPlayer.getAbilities()
                            + " elements="
                            + this.bendingPlayer.getElements()
                            + " subElements="
                            + this.bendingPlayer.getSubElements()
                            + " cooldowns="
                            + this.bendingPlayer.getCooldowns().keySet()
                            + " airBlastStamina="
                            + this.airBlastStamina()
            );
        } else {
            debug("runtime state ignored ready=" + this.ready + " initializing=" + this.initializing + " hasBendingPlayer=" + (this.bendingPlayer != null));
        }
    }


    private String inputAbilityName0(int selectedSlot, String fallback, InputKind kind) {
        if (this.ready && this.bendingPlayer != null) {
            Player player = this.bendingPlayer.getPlayer();
            if (kind == InputKind.SNEAK_START && this.canStartFastSwim(player)) {
                return "FastSwim";
            } else {
                String multi = MultiAbilityManager.getBoundMultiAbility(player);
                if (multi != null && !multi.isBlank()) {
                    return multi;
                } else {
                    String local = (String) this.bendingPlayer.getAbilities().get(selectedSlot + 1);
                    String selected = local != null && !local.isBlank() ? local : (fallback == null ? "" : fallback);
                    if (!selected.equalsIgnoreCase("FireBlast") || kind != InputKind.SNEAK_START && kind != InputKind.SNEAK_STOP) {
                        return selected.isBlank() && kind == InputKind.LEFT_CLICK && this.bendingPlayer.isToggled() && CoreAbility.getAbility(WallRun.class) != null
                                ? "WallRun"
                                : selected;
                    } else {
                        return "FireBlastCharged";
                    }
                }
            }
        } else {
            return fallback == null ? "" : fallback;
        }
    }

    private boolean canStartFastSwim(Player player) {
        if (player != null && !CoreAbility.hasAbility(player, FastSwim.class)) {
            CoreAbility bound = this.bendingPlayer.getBoundAbility();
            CoreAbility passive = CoreAbility.getAbility(FastSwim.class);
            return (bound == null || !bound.isSneakAbility()) && PassiveManager.hasPassive(player, passive);
        } else {
            return false;
        }
    }

    private void recordNativeOnlyInput0(
            long sequence, InputKind kind, int selectedSlot, com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose pose, String ability
    ) {
        if (this.ready && this.bendingPlayer != null && pose != null && kind != null) {
            String inputAbility = ability != null && !ability.isBlank()
                    ? ability
                    : this.inputAbilityName0(selectedSlot, (String) this.bendingPlayer.getAbilities().get(selectedSlot + 1), kind);
            com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action = new com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action(
                    sequence, this.tick, pose.eyePos(), pose.yaw(), pose.pitch(), pose.eyeHeight(), inputAbility, kind, selectedSlot
            );
            this.actions.put(sequence, action);
            debug("runtime recorded native-only input sequence=" + sequence + " kind=" + kind + " ability=" + inputAbility + " slot=" + (selectedSlot + 1));
        }
    }

    private boolean input0(long sequence, InputKind kind, int selectedSlot, com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose pose) {
        if (this.ready && this.bendingPlayer != null) {
            Set<CoreAbility> before = Collections.newSetFromMap(new IdentityHashMap<>());
            before.addAll(CoreAbility.getAbilitiesByInstances());
            Player player = this.bendingPlayer.getPlayer();
            AbilityInformation comboBefore = latestComboInput(player);
            String boundName = this.inputAbilityName0(selectedSlot, (String) this.bendingPlayer.getAbilities().get(selectedSlot + 1), kind);
            Vec3d origin = pose.eyePos();
            com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action = new com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action(
                    sequence, this.tick, origin, pose.yaw(), pose.pitch(), pose.eyeHeight(), boundName, kind, selectedSlot
            );
            action.executed = true;
            this.actions.put(sequence, action);
            boolean failed = false;
            TrackingResult trackingResult = new TrackingResult(false, List.of());
            debug("runtime input start sequence=" + sequence + " kind=" + kind + " bound=" + boundName + " activeBefore=" + before.size() + " tick=" + this.tick);
            INPUT_ACTION.set(sequence);
            INPUT_EVENT_POSE.set(sequence);
            AbilityActivationManager.beginTracking();

            try {
                com.projectkorra.projectkorra.fabric.client.PredictionClient.withInputSelectedSlot(
                        selectedSlot,
                        () -> PredictionDeterminism.run(
                                sequence,
                                action.deterministicSeed,
                                () -> {
                                    switch (kind) {
                                        case LEFT_CLICK:
                                            CommonInputHandler.handleSwing(player, Set.of(), new HashSet());
                                            com.projectkorra.projectkorra.platform.mc.entity.Entity target = GeneralMethods.getTargetedEntity(player, 3.0);
                                            if (target instanceof LivingEntity living && !target.equals(player)) {
                                                CommonInputHandler.handleEntityLeftClick(player, living);
                                            }
                                            break;
                                        case SNEAK_START:
                                            com.projectkorra.projectkorra.fabric.client.PredictionClient.withInputSneaking(
                                                    false, () -> CommonInputHandler.handleSneak(player, false)
                                            );
                                            break;
                                        case RIGHT_CLICK:
                                            CommonInputHandler.handleRightClick(player, ClickType.RIGHT_CLICK);
                                            break;
                                        case RIGHT_CLICK_BLOCK:
                                            CommonInputHandler.handleRightClick(player, ClickType.RIGHT_CLICK_BLOCK);
                                            break;
                                        case RIGHT_CLICK_ENTITY:
                                            CommonInputHandler.handleRightClickEntity(player);
                                            break;
                                        case SNEAK_STOP:
                                            com.projectkorra.projectkorra.fabric.client.PredictionClient.withInputSneaking(
                                                    true, () -> CommonInputHandler.handleSneak(player, true)
                                            );
                                            break;
                                        case SWAP_HANDS:
                                            CommonInputHandler.handleSwapHands(
                                                    player,
                                                    player.getInventory().getItemInMainHand().getType() == Material.AIR,
                                                    player.getInventory().getItemInOffHand() == null || player.getInventory().getItemInOffHand().getType() == Material.AIR
                                            );
                                    }
                                }
                        )
                );
            } catch (Throwable failure) {
                ProjectKorra.log.warning("Predicted input " + sequence + " failed: " + failure.getMessage());
                debug("runtime input failed sequence=" + sequence + " " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                failed = true;
            } finally {
                trackingResult = AbilityActivationManager.finishTrackingResult();
                INPUT_EVENT_POSE.remove();
                INPUT_ACTION.remove();
                this.directBlockAuthority.rollbackAction(sequence);
            }

            action.inputHandled = trackingResult.handled();
            action.comboInput = latestComboInput(player);
            action.comboRecorded = action.comboInput != comboBefore;
            if (!action.comboRecorded) {
                action.comboInput = null;
            }

            for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
                if (!before.contains(ability)) {
                    this.associateAbility(action, ability);
                    this.abilityCreationActions.putIfAbsent(ability, sequence);
                    action.abilities.add(ability);
                    debug("runtime created ability sequence=" + sequence + " ability=" + ability.getName() + " instance=" + System.identityHashCode(ability));
                }
            }

            debug(
                    "runtime input finish sequence="
                            + sequence
                            + " created="
                            + action.abilities.size()
                            + " activeAfter="
                            + CoreAbility.getAbilitiesByInstances().size()
                            + " failed="
                            + failed
            );
            if (failed) {
                this.abortFailedLocalInput(action);
                return false;
            }

            boolean hasMatchingExistingAbility = this.affectedExistingAbility(before, boundName);
            List<CoreAbility> explicitExisting = trackingResult.affectedAbilities()
                    .stream()
                    .filter(before::contains)
                    .filter(
                            abilityx -> abilityx != null
                                    && !abilityx.isRemoved()
                                    && abilityx.getPlayer() != null
                                    && abilityx.getPlayer().getUniqueId().equals(player.getUniqueId())
                    )
                    .toList();
            boolean createdMatchingAbility = action.abilities.stream().anyMatch(abilityx -> matchesInputAbility(abilityx, boundName));
            boolean affectedExisting = !explicitExisting.isEmpty() || trackingResult.handled() && hasMatchingExistingAbility && !createdMatchingAbility;
            boolean producedTempBlock = this.tempBlockAuthority.hasPredictionForAction(sequence);
            boolean producedDirectBlock = !action.directBlockOrdinals.isEmpty();
            boolean locallyPredicted = !action.abilities.isEmpty()
                    || !action.spawned.isEmpty()
                    || producedTempBlock
                    || producedDirectBlock
                    || affectedExisting
                    || !action.abilityStateOrdinals.isEmpty();
            action.locallyPredicted = locallyPredicted;
            if (affectedExisting) {
                if (!explicitExisting.isEmpty()) {
                    for (CoreAbility ability : explicitExisting) {
                        this.associateAbility(action, ability);
                    }
                } else {
                    for (CoreAbility ability : before) {
                        if (ability != null
                                && !ability.isRemoved()
                                && matchesInputAbility(ability, boundName)
                                && ability.getPlayer() != null
                                && ability.getPlayer().getUniqueId().equals(player.getUniqueId())) {
                            this.associateAbility(action, ability);
                        }
                    }
                }
            }

            debug("runtime input localPrediction sequence=" + sequence + " immediateEffect=" + locallyPredicted + " affectedExisting=" + affectedExisting);
            return locallyPredicted;
        } else {
            debug("runtime input skipped sequence=" + sequence + " kind=" + kind + " ready=" + this.ready + " hasBendingPlayer=" + (this.bendingPlayer != null));
            return false;
        }
    }

    private void associateAbility(com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action, CoreAbility ability) {
        if (action != null && ability != null) {
            Long previousSequence = this.abilityActions.get(ability);
            if (!action.previousAbilityActions.containsKey(ability)) {
                action.previousAbilityActions.put(ability, previousSequence);
            }

            if (previousSequence != null && previousSequence != action.sequence) {
                com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action previous = this.actions.get(previousSequence);
                if (previous != null) {
                    previous.abilities.remove(ability);
                }
            }

            this.abilityActions.put(ability, action.sequence);
            action.abilities.add(ability);
        }
    }

    private boolean affectedExistingAbility(Set<CoreAbility> before, String boundName) {
        if (before != null && !before.isEmpty() && boundName != null && !boundName.isBlank() && this.bendingPlayer != null) {
            Player player = this.bendingPlayer.getPlayer();
            if (player == null) {
                return false;
            }

            for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
                if (before.contains(ability)
                        && !ability.isRemoved()
                        && matchesInputAbility(ability, boundName)
                        && ability.getPlayer() != null
                        && ability.getPlayer().getUniqueId().equals(player.getUniqueId())) {
                    return true;
                }
            }

            return false;
        } else {
            return false;
        }
    }

    private static boolean matchesInputAbility(CoreAbility ability, String inputName) {
        return ability != null
                && inputName != null
                && (inputName.equalsIgnoreCase(ability.getName()) || inputName.equalsIgnoreCase("FireBlastCharged") && ability instanceof FireBlastCharged);
    }

    private static AbilityInformation latestComboInput(Player player) {
        if (player == null) {
            return null;
        }

        List<AbilityInformation> recent = ComboManager.getRecentlyUsedAbilities(player, 1);
        return recent.isEmpty() ? null : recent.get(recent.size() - 1);
    }

    private void predictMovement0(
            MinecraftClient client,
            com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose fromPose,
            com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose toPose
    ) {
        if (this.ready && this.bendingPlayer != null && client != null && client.player != null && client.world != null && fromPose != null && toPose != null) {
            Location from = FabricPredictionMC.location(client.world, new Vec3d(fromPose.x(), fromPose.y(), fromPose.z()), fromPose.yaw(), fromPose.pitch());
            Location to = FabricPredictionMC.location(client.world, new Vec3d(toPose.x(), toPose.y(), toPose.z()), toPose.yaw(), toPose.pitch());
            MovementResult result = CommonPlayerListenerCore.handlePredictedMove(this.bendingPlayer.getPlayer(), from, to, false, false, 0.0);
            if (result.cancelEvent()) {
                debug("runtime movement prediction requested cancel from common listener");
            }
        }
    }

    private void tick0(MinecraftClient client) {
        this.tick++;
        if (this.ready && client.player != null && client.world != null) {
            try {
                this.platform.tick();
                this.cooldownAuthority.retainLocallyActive(Set.copyOf(this.bendingPlayer.getCooldowns().keySet()));
            } catch (Throwable failure) {
                ProjectKorra.log.warning("Predicted ability tick failed: " + failure.getMessage());
                debug("runtime tick failed " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }

            this.velocityAuthority.afterLocalProgress(client.world, this.tick, this::hasLivePredictedVelocityWriter);
            this.tempBlockAuthority.afterLocalProgress(client.world);
            this.directBlockAuthority.clearTransientReads();
            Set<CoreAbility> live = Collections.newSetFromMap(new IdentityHashMap<>());
            live.addAll(CoreAbility.getAbilitiesByInstances());
            this.abilityActions.keySet().removeIf(ability -> !live.contains(ability));
            this.abilityCreationActions.keySet().removeIf(ability -> !live.contains(ability));
            this.authoritativelyEstablishedAbilities.removeIf(ability -> !live.contains(ability));
            this.velocityAuthority.expire(this.tick, 160, 40);
            this.tempBlockAuthority.expire();
            this.playerStateAuthority.expire(this.tick, 160L);
            this.entityReconciliation.expire(this.tick, 160);
            this.directBlockAuthority.expire(client.player.getUuid(), 160, 40);
            Iterator<com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action> iterator = this.actions.values().iterator();

            while (iterator.hasNext()) {
                com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action = iterator.next();
                action.abilities.removeIf(ability -> !live.contains(ability));
                if (this.tick - action.createdTick > 160L && action.abilities.isEmpty()) {
                    this.entityReconciliation.retireAction(action.sequence);
                    iterator.remove();
                }
            }

            if (DEBUG && this.tick % 20L == 0L) {
                debug(
                        "runtime tick="
                                + this.tick
                                + " active="
                                + live.size()
                                + " actions="
                                + this.actions.size()
                                + " predictedBlocks="
                                + this.directBlockAuthority.mutationCount()
                                + " velocities="
                                + this.velocityAuthority.mutationCount()
                );
            }
        } else {
            if (DEBUG && this.tick % 20L == 0L) {
                debug("runtime tick skipped ready=" + this.ready + " player=" + (client.player != null) + " world=" + (client.world != null));
            }
        }
    }

    private void reconcileAuthoritativeCooldowns(Map<String, Long> authoritativeCooldowns) {
        if (this.bendingPlayer != null) {
            if ((this.initializing || !this.ready) && authoritativeCooldowns != null) {
                authoritativeCooldowns.forEach(com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime::enforceLocalCooldown);
            }
        }
    }

    private boolean noteNativeAction0(NativeAction receipt) {
        if (this.ready && receipt != null && receipt.predictable()) {
            final long localSequence = this.nativeActions.correlate(receipt,
                    this.actions.values().stream().map(Action::correlationCandidate).toList());
            final Action action = this.actions.get(localSequence);
            if (action == null) {
                debug(
                        "runtime rejected non-identical native action sequence="
                                + receipt.actionSequence()
                                + " kind="
                                + receipt.kind()
                                + " slot="
                                + receipt.selectedSlot()
                                + " ability="
                                + receipt.ability()
                );
                return false;
            } else {
                action.nativeConfirmed = true;
                debug(
                        "runtime confirmed native action sequence="
                                + receipt.actionSequence()
                                + " localSequence="
                                + action.sequence
                                + " taggedLocalSequence="
                                + receipt.clientActionSequence()
                                + " originDeltaSquared="
                                + new Vec3d(receipt.originX(), receipt.originY(), receipt.originZ()).squaredDistanceTo(action.origin)
                );
                return true;
            }
        } else {
            return false;
        }
    }

    private long localActionSequence(long paperSequence) {
        return this.nativeActions.localSequence(paperSequence);
    }

    private long paperActionSequence(long localSequence) {
        return this.nativeActions.paperSequence(localSequence);
    }

    private long localAcknowledgedSequence(long paperSequence) {
        return this.nativeActions.acknowledgedLocalSequence(paperSequence);
    }

    static long mappedActionSequence(Map<Long, Long> aliases, long paperSequence) {
        return ClientNativeActionCorrelation.mappedActionSequence(aliases, paperSequence);
    }

    static long mappedAcknowledgedSequence(Map<Long, Long> aliases, long paperSequence) {
        return ClientNativeActionCorrelation.mappedAcknowledgedSequence(aliases, paperSequence);
    }

    private void reconcile0(
            long sequence, Vec3d authoritativeOrigin, String ability, long cooldownUntil, boolean inputHandled, boolean comboRecorded, List<String> createdAbilities
    ) {
        long localSequence = this.localActionSequence(sequence);
        com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action = this.actions.get(localSequence);
        if (action != null && action.nativeConfirmed && (ability == null || ability.isBlank() || action.inputAbility.equalsIgnoreCase(ability))) {
            List<String> authoritativeCreated = createdAbilities == null
                    ? List.of()
                    : createdAbilities.stream().filter(name -> name != null && !name.isBlank()).limit(64L).toList();
            if (!action.executed && (inputHandled || comboRecorded || !authoritativeCreated.isEmpty())) {
                action = this.replayNativeOnlyAction(action);
                if (action == null) {
                    debug("runtime failed to recover accepted native input paperSequence=" + sequence + " localSequence=" + localSequence + " ability=" + ability);
                    return;
                }
            }

            if (action.comboRecorded && !comboRecorded && action.comboInput != null && this.bendingPlayer != null) {
                ComboManager.removeRecentAbility(this.bendingPlayer.getPlayer(), action.comboInput);
                action.comboRecorded = false;
                action.comboInput = null;
            }

            this.reconcileCreatedAbilities(action, authoritativeCreated);
            action.reconciled = true;
            if (cooldownUntil > System.currentTimeMillis() && ability != null && !ability.isBlank()) {
                enforceLocalCooldown(ability, cooldownUntil);
            }

            action.previousAbilityActions.clear();
            action.blockConfirmationTicks = Math.max(4, Math.min(40, (int) Math.max(0L, this.tick - action.createdTick) + 2));
            debug(
                    "runtime reconcile confirmed paperSequence="
                            + sequence
                            + " localSequence="
                            + localSequence
                            + " ability="
                            + ability
                            + " recovered="
                            + action.recoveredFromAuthority
                            + " handled="
                            + inputHandled
                            + " comboRecorded="
                            + comboRecorded
                            + " created="
                            + authoritativeCreated
                            + " originDeltaSquared="
                            + authoritativeOrigin.squaredDistanceTo(action.origin)
            );
        } else {
            debug("runtime reconcile missing action paperSequence=" + sequence + " localSequence=" + localSequence + " ability=" + ability);
        }
    }

    private com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action replayNativeOnlyAction(
            com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action recorded
    ) {
        if (recorded != null && !recorded.executed) {
            long sequence = recorded.sequence;
            com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose pose = new com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose(
                    recorded.origin.x, recorded.origin.y - recorded.eyeHeight, recorded.origin.z, recorded.yaw, recorded.pitch, recorded.eyeHeight
            );
            this.actions.remove(sequence, recorded);
            this.input0(sequence, recorded.kind, recorded.selectedSlot, pose);
            com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action replayed = this.actions.get(sequence);
            if (replayed == null) {
                this.actions.put(sequence, recorded);
                return null;
            } else {
                replayed.nativeConfirmed = true;
                replayed.recoveredFromAuthority = true;
                debug("runtime replayed Paper-accepted native input sequence=" + sequence + " kind=" + replayed.kind + " ability=" + replayed.inputAbility);
                return replayed;
            }
        } else {
            return recorded;
        }
    }

    private void reconcileCreatedAbilities(com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action, List<String> authoritativeNames) {
        if (action != null && this.bendingPlayer != null) {
            Map<String, Integer> remaining = abilityNameCounts(authoritativeNames);

            for (CoreAbility local : this.locallyCreatedAbilities(action.sequence)) {
                if (!this.authoritativelyEstablishedAbilities.contains(local)) {
                    String key = normalizedAbilityName(local.getName());
                    int count = remaining.getOrDefault(key, 0);
                    if (count > 0) {
                        remaining.put(key, count - 1);
                    } else {
                        debug("runtime retired client-only input outcome action=" + action.sequence + " ability=" + local.getName());

                        try {
                            this.forceRemoveAbility(local);
                        } catch (Throwable var9) {
                        }

                        this.abilityActions.remove(local);
                        this.abilityCreationActions.remove(local);
                        action.abilities.remove(local);
                    }
                }
            }

            Map<String, Integer> localCounts = abilityNameCounts(this.locallyCreatedAbilities(action.sequence).stream().<String>map(Ability::getName).toList());

            for (String authoritativeName : authoritativeNames) {
                String key = normalizedAbilityName(authoritativeName);
                int count = localCounts.getOrDefault(key, 0);
                if (count > 0) {
                    localCounts.put(key, count - 1);
                } else if (ComboManager.getComboAbility(authoritativeName) != null) {
                    this.recoverMissingCombo(action, authoritativeName);
                }
            }
        }
    }

    private List<CoreAbility> locallyCreatedAbilities(long sequence) {
        return this.abilityCreationActions
                .entrySet()
                .stream()
                .filter(entry -> Objects.equals(entry.getValue(), sequence))
                .map(Entry::getKey)
                .filter(ability -> ability != null && !ability.isRemoved())
                .toList();
    }

    private void recoverMissingCombo(com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action, String abilityName) {
        Long previousAction = INPUT_ACTION.get();
        Long previousPose = INPUT_EVENT_POSE.get();
        INPUT_ACTION.set(action.sequence);
        INPUT_EVENT_POSE.set(action.sequence);
        CoreAbility[] recovered = new CoreAbility[]{null};

        try {
            com.projectkorra.projectkorra.fabric.client.PredictionClient.withInputSelectedSlot(
                    action.selectedSlot,
                    () -> PredictionDeterminism.run(
                            action.sequence, action.deterministicSeed, () -> recovered[0] = ComboManager.createComboAbility(this.bendingPlayer.getPlayer(), abilityName)
                    )
            );
        } finally {
            if (previousAction == null) {
                INPUT_ACTION.remove();
            } else {
                INPUT_ACTION.set(previousAction);
            }

            if (previousPose == null) {
                INPUT_EVENT_POSE.remove();
            } else {
                INPUT_EVENT_POSE.set(previousPose);
            }
        }

        CoreAbility combo = recovered[0];
        if (combo != null && !combo.isRemoved()) {
            this.associateAbility(action, combo);
            this.abilityCreationActions.put(combo, action.sequence);
            action.recoveredFromAuthority = true;
            debug("runtime recovered server-created combo action=" + action.sequence + " ability=" + combo.getName());
        }
    }

    private static Map<String, Integer> abilityNameCounts(List<String> names) {
        Map<String, Integer> counts = new HashMap<>();
        if (names == null) {
            return counts;
        }

        for (String name : names) {
            if (name != null && !name.isBlank()) {
                counts.merge(normalizedAbilityName(name), 1, Integer::sum);
            }
        }

        return counts;
    }

    private static String normalizedAbilityName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private void abortFailedLocalInput(com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action) {
        debug("runtime abort failed local input sequence=" + action.sequence + " abilities=" + action.abilities.size() + " spawned=" + action.spawned.size());

        for (CoreAbility ability : List.copyOf(action.abilities)) {
            try {
                this.forceRemoveAbility(ability);
            } catch (Throwable var5) {
            }
        }

        action.abilities.clear();

        for (Entry<CoreAbility, Long> entry : action.previousAbilityActions.entrySet()) {
            CoreAbility ability = entry.getKey();
            if (Objects.equals(this.abilityActions.get(ability), action.sequence)) {
                if (entry.getValue() == null) {
                    this.abilityActions.remove(ability);
                } else {
                    this.abilityActions.put(ability, entry.getValue());
                }
            }
        }

        action.previousAbilityActions.clear();

        for (Entity entity : action.spawned) {
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
        }

        this.entityReconciliation.rollbackAction(action.sequence, action.spawned);
        action.spawned.clear();
        this.directBlockAuthority.rollbackAction(action.sequence);
        this.velocityAuthority.rollbackAction(action.sequence);
    }

    private int blockConfirmationTicks(long actionSequence) {
        com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action = this.actions.get(actionSequence);
        return action != null && action.reconciled ? action.blockConfirmationTicks : 40;
    }

    private void stop0(MinecraftClient client) {
        if (!this.commonRuntimeInstalled) {
            debug("runtime stop skipped before common startup actions=" + this.actions.size());
            if (this.platform != null) {
                try {
                    this.platform.close();
                } catch (Throwable var25) {
                }

                this.platform = null;
            }

            this.grantedPermissions = Set.of();
        } else {
            debug(
                    "runtime stop ready="
                            + this.ready
                            + " initializing="
                            + this.initializing
                            + " activeInstances="
                            + CoreAbility.getAbilitiesByInstances().size()
                            + " actions="
                            + this.actions.size()
            );
            this.discardingWorldState = true;

            try {
                if (this.ready || this.initializing) {
                    try {
                        GeneralMethods.stopBending();
                    } catch (Throwable var40) {
                    }

                    try {
                        EmbeddedAddonBootstrap.disable();
                    } catch (Throwable var39) {
                    }

                    if (ProjectKorra.collisionManager != null) {
                        try {
                            ProjectKorra.collisionManager.stopCollisionDetection();
                        } catch (Throwable var38) {
                        }

                        ProjectKorra.collisionManager = null;
                        ProjectKorra.collisionInitializer = null;
                    }

                    if (this.managersStarted) {
                        try {
                            Manager.shutdown();
                        } catch (Throwable var37) {
                        }

                        this.managersStarted = false;
                    }
                }

                try {
                    TempBlock.discardAll();
                } catch (Throwable var36) {
                }

                try {
                    TempFallingBlock.discardAll();
                } catch (Throwable var35) {
                }

                try {
                    CoreAbility.discardAllInstances();
                } catch (Throwable var34) {
                }

                try {
                    AirAbility.discardAllAirbendingState();
                } catch (Throwable var33) {
                }

                try {
                    EarthAbility.discardAllEarthbendingState();
                } catch (Throwable var32) {
                }

                try {
                    WaterAbility.discardAllWaterbendingState();
                } catch (Throwable var31) {
                }

                try {
                    FireAbility.discardAllFirebendingState();
                } catch (Throwable var30) {
                }

                try {
                    RevertChecker.discardAll();
                } catch (Throwable var29) {
                }

                try {
                    BlockSource.clearAll();
                } catch (Throwable var28) {
                }

                if (this.bendingPlayer != null) {
                    RegionProtectionAuthority.clear(this.bendingPlayer.getPlayer());
                    BendingPlayer.getPlayers().remove(this.bendingPlayer.getPlayer().getUniqueId());
                    BendingPlayer.getOfflinePlayers().remove(this.bendingPlayer.getPlayer().getUniqueId());
                }

                if (this.platform != null) {
                    try {
                        this.platform.close();
                    } catch (Throwable var27) {
                    }
                }

                for (com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action : this.actions.values()) {
                    for (Entity entity : action.spawned) {
                        if (entity != null && !entity.isRemoved()) {
                            try {
                                entity.discard();
                            } catch (Throwable var26) {
                            }
                        }
                    }
                }

                this.actions.clear();
                this.abilityActions.clear();
                this.abilityCreationActions.clear();
                this.authoritativelyEstablishedAbilities.clear();
                this.abilityRemovalHistory.clear();
                this.nativeActions.clear();
                this.authoritativeFlightAbilities = Set.of();
                this.authoritativeFlightSequence = -1L;
                this.grantedPermissions = Set.of();
                this.directBlockAuthority.clear();
                this.tempBlockAuthority.clear();
                this.velocityAuthority.clear();
                this.entityReconciliation.clear();
                this.playerStateAuthority.clear();
                TempBlockSync.clear(this.tempBlockAuthority);
                TempFallingBlockSync.clear(this);
                PredictedContactSync.clear(this);
                CooldownSync.clear(this);
                this.cooldownAuthority.clear();
                this.platform = null;
                this.bendingManager = null;
                this.bendingPlayer = null;
                this.ready = false;
                this.managersStarted = false;
                this.commonRuntimeInstalled = false;
                debug("runtime stopped");
            } finally {
                this.discardingWorldState = false;
            }
        }
    }

    public boolean isAuthoritative() {
        return false;
    }


    public void onAdded(CoreAbility source, BendingPlayer player, String ability, long expiresAtMillis) {
        if (player == this.bendingPlayer && ability != null && !ability.isBlank()) {
            this.cooldownAuthority.onLocalAdded(ability, expiresAtMillis);
        }
    }

    public void onRemoved(BendingPlayer player, String ability) {
        if (player == this.bendingPlayer && ability != null) {
            this.cooldownAuthority.onLocalRemoved(ability);
        }
    }


    static <T> T completedTempBlockRestoreState(boolean followLiveClientState, T liveState, T finalUnderlay) {
        return ClientTempBlockAuthority.completedRestoreState(
                followLiveClientState, liveState, finalUnderlay
        );
    }


    private void noteDirectBlock0(Entity localPlayer, DirectBlockReceipt receipt) {
        if (this.ready && receipt != null) {
            this.directBlockAuthority.noteReceipt(localPlayer, receipt, this.localActionSequence(receipt.actionSequence()), MinecraftClient.getInstance().world);
        }
    }

    public void onPredictedContact(CoreAbility ability, com.projectkorra.projectkorra.platform.mc.entity.Entity target) {
        if (this.ready && ability != null && target instanceof Player && !target.isDead() && target.isValid()) {
            long sequence = this.abilityActions.getOrDefault(ability, this.currentAction());
            com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action = this.actions.get(sequence);
            if (action != null && action.claimedTargets.add(target.getUniqueId())) {
                Vector contact = target.getBoundingBox().getCenter();
                com.projectkorra.projectkorra.fabric.client.PredictionClient.queueExactHitClaim(
                        sequence,
                        this.paperActionSequence(sequence),
                        action.inputAbility,
                        target.getUniqueId(),
                        target.getEntityId(),
                        contact.getX(),
                        contact.getY(),
                        contact.getZ()
                );
                debug(
                        "runtime queued rewound hit claim action="
                                + sequence
                                + " paperAction="
                                + this.paperActionSequence(sequence)
                                + " ability="
                                + action.inputAbility
                                + " target="
                                + target.getUniqueId()
                );
            }
        }
    }

    private void setVelocity0(Entity entity, Vec3d velocity) {
        if (this.ready && entity != null && velocity != null && finite(velocity)) {
            if (isLocalPlayerEntity(entity.getId()) && this.velocityAuthority.blocksPredictedWrite(entity.getId())) {
                debug(
                        "runtime suppressed late predicted velocity behind external authority entity="
                                + entity.getId()
                                + " ability="
                                + this.currentAbilityName()
                                + " attempted="
                                + velocityString(velocity)
                );
            } else {
                long actionSequence = this.currentAction();
                com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action = this.actions.get(actionSequence);
                int impulseOrdinal = action == null ? 0 : action.velocityOrdinals.merge(entity.getId(), 1, Integer::sum);
                String abilityName = this.currentAbilityName();
                if ("<none>".equals(abilityName) && action != null) {
                    abilityName = action.inputAbility;
                }

                if (isLocalPlayerEntity(entity.getId())) {
                    debug(
                            "runtime predicted velocity local action="
                                    + actionSequence
                                    + " ordinal="
                                    + impulseOrdinal
                                    + " ability="
                                    + abilityName
                                    + " before="
                                    + velocityString(entity.getVelocity())
                                    + " after="
                                    + velocityString(velocity)
                                    + " stamina="
                                    + this.airBlastStamina()
                                    + " activeAirBlasts="
                                    + this.activeAirBlastSummary()
                    );
                }

                this.velocityAuthority.predict(entity, velocity, actionSequence, impulseOrdinal, abilityName, this.tick);
            }
        }
    }

    private boolean authoritativeVelocity0(int entityId, Vec3d velocity) {
        if (this.ready && finite(velocity)) {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            return this.velocityAuthority
                    .acceptAuthoritative(entityId, velocity, this.tick, player == null ? null : player.getUuid(), this::hasLivePredictedVelocityWriter);
        } else {
            return false;
        }
    }

    private boolean hasLivePredictedVelocityWriter(int entityId) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null && player.getId() == entityId) {
            for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
                if (ability != null && !ability.isRemoved() && ability.getPlayer() != null && player.getUuid().equals(ability.getPlayer().getUniqueId())) {
                    Long sequence = this.abilityActions.containsKey(ability) ? this.abilityActions.get(ability) : this.abilityCreationActions.get(ability);
                    if (this.velocityAuthority.hasMutation(entityId, sequence, ability.getName())) {
                        return true;
                    }
                }
            }

            return false;
        } else {
            return false;
        }
    }

    private boolean tracksVelocityEntity0(int entityId) {
        if (this.ready && entityId >= 0) {
            MinecraftClient client = MinecraftClient.getInstance();
            return this.velocityAuthority.tracks(entityId, client.player == null ? -1 : client.player.getId());
        } else {
            return false;
        }
    }

    private void noteVelocityOwner0(Entity localPlayer, VelocityOwner owner) {
        if (this.ready) {
            this.velocityAuthority.recordOwner(localPlayer, owner, this.tick, this::localActionSequence);
        }
    }

    private void noteVelocityOwner0(Entity localPlayer, VelocityOwnerV2 owner) {
        if (this.ready) {
            this.velocityAuthority.recordOwner(localPlayer, owner, this.tick, this::localActionSequence);
        }
    }

    private void removeAuthoritativeAbility0(Entity localPlayer, AbilityRemoved removed) {
        if (this.ready && localPlayer != null && removed != null && localPlayer.getUuid().equals(removed.player())) {
            long localCreationSequence = this.localActionSequence(removed.actionSequence());
            long localAcknowledgement = this.localAcknowledgedSequence(removed.acknowledgedSequence());
            if (removed.actionSequence() > 0L) {
                com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action = this.actions.get(localCreationSequence);
                if (!removalReceiptMayResolve(removed.externallyCaused(), action != null, action != null && action.nativeConfirmed)) {
                    this.recordAbilityRemoval(removed, "IGNORED missing/unconfirmed correlated creation action local=" + localCreationSequence, List.of());
                    return;
                }
            }

            List<CoreAbility> matching = new ArrayList<>();

            for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
                if (ability.getPlayer() != null
                        && ability.getPlayer().getUniqueId().equals(removed.player())
                        && ability.getName().equalsIgnoreCase(removed.ability())
                        && AbilityRemovalSync.isType(ability, removed.abilityType())) {
                    matching.add(ability);
                }
            }

            List<CoreAbility> coveredByEmptyTypeFence = matching.stream()
                    .filter(
                            ability -> authoritativeEmptyTypeFenceCoversCandidate(
                                    removed.externallyCaused(),
                                    removed.remainingTypeInstances(),
                                    localAcknowledgement,
                                    this.abilityActions.getOrDefault(ability, this.abilityCreationActions.get(ability))
                            )
                    )
                    .toList();
            if (!coveredByEmptyTypeFence.isEmpty()) {
                int removedCount = 0;

                try {
                    for (CoreAbility ability : coveredByEmptyTypeFence) {
                        this.forceRemoveAbility(ability);
                        if (ability.isRemoved()) {
                            removedCount++;
                        }
                    }

                    this.recordAbilityRemoval(
                            removed,
                            (removedCount == coveredByEmptyTypeFence.size() ? "APPLIED" : "FAILED")
                                    + " authoritative-empty-type-fence removed="
                                    + removedCount
                                    + "/"
                                    + coveredByEmptyTypeFence.size(),
                            matching
                    );
                } catch (RuntimeException failure) {
                    this.recordAbilityRemoval(
                            removed, "FAILED " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + " authoritative-empty-type-fence", matching
                    );
                    ProjectKorra.log.log(Level.WARNING, "Authoritative empty-type cleanup failed for " + removed.abilityType(), failure);
                }
            } else {
                CoreAbility selected = null;
                if (localCreationSequence > 0L) {
                    for (CoreAbility ability : matching) {
                        if (Objects.equals(this.abilityCreationActions.get(ability), localCreationSequence)) {
                            selected = ability;
                            break;
                        }
                    }
                }

                if (selected == null) {
                    this.recordAbilityRemoval(removed, "NO_MATCH", matching);
                } else {
                    Long creationSequence = this.abilityCreationActions.get(selected);
                    com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action predictedAction = creationSequence == null
                            ? null
                            : this.actions.get(creationSequence);
                    if (!removed.externallyCaused() && predictedAction != null && predictedAction.reconciled && predictedAction.locallyPredicted) {
                        debug(
                                "runtime retained accepted client ability lifecycle after server close ability="
                                        + removed.ability()
                                        + " type="
                                        + removed.abilityType()
                                        + " action="
                                        + removed.actionSequence()
                        );
                        this.recordAbilityRemoval(removed, "RETAINED ordinary predicted lifecycle", matching);
                    } else {
                        debug(
                                "runtime applied authoritative ability removal ability="
                                        + removed.ability()
                                        + " type="
                                        + removed.abilityType()
                                        + " paperAction="
                                        + removed.actionSequence()
                                        + " localCreation="
                                        + localCreationSequence
                                        + " external="
                                        + removed.externallyCaused()
                        );

                        try {
                            this.forceRemoveAbility(selected);
                            this.recordAbilityRemoval(
                                    removed, (selected.isRemoved() ? "APPLIED" : "FAILED instance remained active") + " selectedCreation=" + creationSequence, matching
                            );
                        } catch (RuntimeException failure) {
                            this.recordAbilityRemoval(
                                    removed,
                                    "FAILED " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + " selectedCreation=" + creationSequence,
                                    matching
                            );
                            ProjectKorra.log.log(Level.WARNING, "Authoritative ability cleanup failed for " + removed.abilityType(), failure);
                        }
                    }
                }
            }
            if (removed.predictionRejected()) {
                this.bendingPlayer.removeCooldown(removed.ability());
                this.cooldownAuthority.onLocalRemoved(removed.ability());
                debug("runtime cleared cooldown from authoritative activation rejection ability="
                        + removed.ability());
            }
        }
    }

    private void transferAuthoritativeAbility0(Entity localPlayer, AbilityTransfer transfer) {
        if (this.ready
                && localPlayer != null
                && transfer != null
                && localPlayer.getUuid().equals(transfer.player())
                && this.bendingPlayer != null
                && EarthSmash.class.getName().equals(transfer.abilityType())) {
            ClientWorld world = MinecraftClient.getInstance().world;
            if (world != null && matchesWorld(world.getRegistryKey().getValue().toString(), transfer.world())) {
                long localSequence = this.localActionSequence(transfer.actionSequence());
                com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action = this.actions.get(localSequence);
                if (action == null) {
                    debug(
                            "runtime ignored authoritative ability state without correlated action paperAction="
                                    + transfer.actionSequence() + " type=" + transfer.abilityType()
                    );
                } else {
                    PredictionTransfer state = new PredictionTransfer(
                            transfer.world(),
                            transfer.x(),
                            transfer.y(),
                            transfer.z(),
                            transfer.hasDestination(),
                            transfer.destinationX(),
                            transfer.destinationY(),
                            transfer.destinationZ(),
                            transfer.state(),
                            transfer.grabbedDistance(),
                            transfer.animationCounter(),
                            transfer.progressCounter(),
                            transfer.predictionFrame(),
                            transfer.elapsedMillis(),
                            transfer.flightElapsedMillis(),
                            transfer.delayElapsedMillis(),
                            transfer.blocks().stream().map(block -> new PredictionBlock(block.x(), block.y(), block.z(), block.material())).toList()
                    );
                    EarthSmash selected = null;

                    for (CoreAbility candidate : CoreAbility.getAbilitiesByInstances()) {
                        if (candidate instanceof EarthSmash smash
                                && !smash.isRemoved()
                                && smash.getPlayer() != null
                                && smash.getPlayer().getUniqueId().equals(transfer.player())
                                && (action.abilities.contains(candidate)
                                || action.previousAbilityActions.containsKey(candidate)
                                || Objects.equals(this.abilityCreationActions.get(candidate), localSequence))) {
                            selected = smash;
                            break;
                        }
                    }

                    boolean recoveredFromCheckpoint = false;
                    boolean restoredFromAuthority = false;
                    boolean checkpointSuperseded = false;
                    if (selected == null) {
                        selected = EarthSmash.restorePredictionTransfer(this.bendingPlayer.getPlayer(), state);
                        if (selected == null || selected.isRemoved() || !selected.isStarted()) {
                            return;
                        }
                        restoredFromAuthority = true;
                        recoveredFromCheckpoint = !transfer.ownershipTransfer();
                    } else {
                        final Long latestTransition = this.abilityActions.get(selected);
                        checkpointSuperseded = !transfer.ownershipTransfer()
                                && latestTransition != null && latestTransition > localSequence;
                        if (!checkpointSuperseded && (transfer.ownershipTransfer()
                                || !selected.matchesPredictionCheckpoint(state))) {
                            selected.applyPredictionTransfer(state);
                            recoveredFromCheckpoint = !transfer.ownershipTransfer();
                        }
                    }

                    if (!checkpointSuperseded) {
                        this.associateAbility(action, selected);
                    } else {
                        // A later local grab/throw already owns this instance.
                        // Re-associating a delayed checkpoint would move it
                        // backwards to the older action and can even make the
                        // later checkpoint restore a duplicate EarthSmash.
                        debug("runtime retained newer EarthSmash transition behind checkpoint action="
                                + localSequence + " latest=" + this.abilityActions.get(selected)
                                + " state=" + selected.getState());
                    }
                    // A checkpoint describes the current transition owner; it
                    // does not recreate an already-live ability. Replacing the
                    // original creation action here makes the subsequent
                    // reconcile compare that existing EarthSmash against
                    // Paper's correctly empty created-ability list and remove
                    // it immediately. Only an authority-restored instance has
                    // no creation identity yet.
                    this.abilityCreationActions.putIfAbsent(selected, localSequence);
                    if (transfer.ownershipTransfer()) {
                        // Provisional ownership previews never enter the exact
                        // TempBlock ledger, so this payload is the first shared
                        // ordinal boundary for both runtimes.
                        action.tempBlockOrdinal = Math.max(0, transfer.tempBlockOrdinal());
                        this.authoritativelyEstablishedAbilities.add(selected);
                    } else {
                        action.tempBlockOrdinal = Math.max(action.tempBlockOrdinal,
                                transfer.tempBlockOrdinal());
                        if (restoredFromAuthority) {
                            // A sparse checkpoint is direct proof that Paper has
                            // this live instance. Its transition action quite
                            // correctly reports created=[], so creation-list
                            // reconciliation must not reject the restoration.
                            this.authoritativelyEstablishedAbilities.add(selected);
                        }
                    }
                    action.locallyPredicted = true;
                    if (recoveredFromCheckpoint) action.recoveredFromAuthority = true;
                    debug(
                            "runtime applied authoritative ability "
                                    + (transfer.ownershipTransfer() ? "ownership transfer" : "checkpoint")
                                    + " action="
                                    + localSequence
                                    + " ability="
                                    + selected.getName()
                                    + " state="
                                    + transfer.state()
                                    + " location=("
                                    + transfer.x()
                                    + ", "
                                    + transfer.y()
                                    + ", "
                                    + transfer.z()
                                    + ") blocks="
                                    + state.blocks().size()
                                    + " localShape="
                                    + selected.getCurrentBlocks().size()
                                    + " tracksLayers="
                                    + selected.tracksPredictedTempBlocks()
                    );
                }
            }
        }
    }

    private void recordAbilityRemoval(AbilityRemoved removed, String resolution, List<CoreAbility> matching) {
        if (removed != null) {
            List<String> candidates = new ArrayList<>();
            if (matching != null) {
                for (CoreAbility ability : matching) {
                    candidates.add(
                            ability.getClass().getSimpleName() + "@" + this.abilityCreationActions.get(ability) + (ability.isRemoved() ? "(removed)" : "(active)")
                    );
                }
            }

            this.abilityRemovalHistory
                    .add(
                            "ability="
                                    + removed.ability()
                                    + " type="
                                    + removed.abilityType()
                                    + " action="
                                    + removed.actionSequence()
                                    + " external="
                                    + removed.externallyCaused()
                                    + " ack="
                                    + removed.acknowledgedSequence()
                                    + " remainingType="
                                    + removed.remainingTypeInstances()
                                    + " result="
                                    + resolution
                                    + " candidates="
                                    + candidates
                    );

            while (this.abilityRemovalHistory.size() > 12) {
                this.abilityRemovalHistory.remove(0);
            }
        }
    }

    private List<String> abilityRemovalReport0() {
        List<String> report = new ArrayList<>();
        if (this.abilityRemovalHistory.isEmpty()) {
            report.add("Ability removals: no Paper removal receipt has reached this client session");
        } else {
            report.add("Ability removals (oldest to newest):");
            report.addAll(this.abilityRemovalHistory);
        }

        List<String> active = new ArrayList<>();

        for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (ability.getPlayer() != null
                    && this.bendingPlayer != null
                    && this.bendingPlayer.getPlayer() != null
                    && ability.getPlayer().getUniqueId().equals(this.bendingPlayer.getPlayer().getUniqueId())
                    && (ability.getName().equalsIgnoreCase("WaterSpout") || ability.getName().equalsIgnoreCase("AirSpout"))) {
                active.add(ability.getClass().getSimpleName() + "@" + this.abilityCreationActions.get(ability));
            }
        }

        report.add(
                "Local spout instances=" + active + " Paper flight snapshot=" + this.authoritativeFlightAbilities + " snapshotAck=" + this.authoritativeFlightSequence
        );
        return List.copyOf(report);
    }

    private List<String> tempBlockReport0() {
        final List<String> report = new ArrayList<>(this.tempBlockAuthority.report());
        report.addAll(this.directBlockAuthority.report());
        final List<String> raises = new ArrayList<>();
        for (final CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (!(ability instanceof RaiseEarth raise) || raise.getPlayer() == null
                    || this.bendingPlayer == null || this.bendingPlayer.getPlayer() == null
                    || !raise.getPlayer().getUniqueId().equals(
                    this.bendingPlayer.getPlayer().getUniqueId())) continue;
            final Location location = raise.getLocation();
            raises.add("RaiseEarth@" + this.abilityCreationActions.get(raise)
                    + " transition=" + this.abilityActions.get(raise)
                    + " location=" + (location == null ? "null" : "("
                    + location.getX() + "," + location.getY() + "," + location.getZ() + ")")
                    + " distance=" + raise.getDistance()
                    + " affected=" + raise.getAffectedBlocks().size()
                    + " wall=" + raise.isRaisedByWall());
        }
        report.add(raises.isEmpty() ? "Local RaiseEarth instances=[]"
                : "Local RaiseEarth instances: " + raises);
        final List<String> smashes = new ArrayList<>();
        for (final CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (!(ability instanceof EarthSmash smash) || smash.getPlayer() == null
                    || this.bendingPlayer == null || this.bendingPlayer.getPlayer() == null
                    || !smash.getPlayer().getUniqueId().equals(
                    this.bendingPlayer.getPlayer().getUniqueId())) continue;
            final PredictionTransfer transfer = smash.capturePredictionTransfer();
            final Location location = smash.getLocation();
            smashes.add("EarthSmash@" + this.abilityCreationActions.get(smash)
                    + " state=" + smash.getState()
                    + " location=" + (location == null ? "null" : "("
                    + location.getX() + "," + location.getY() + "," + location.getZ() + ")")
                    + " animation=" + smash.getAnimationCounter()
                    + " progress=" + smash.getProgressCounter()
                    + " frame=" + (transfer == null ? -1L : transfer.predictionFrame())
                    + " shape=" + smash.getCurrentBlocks().size()
                    + " activeLayers=" + smash.getAffectedBlocks().stream()
                    .filter(layer -> layer != null && !layer.isReverted()).count()
                    + " tracksLayers=" + smash.tracksPredictedTempBlocks()
                    + " authoritativeEstablished=" + this.authoritativelyEstablishedAbilities.contains(smash));
        }
        if (smashes.isEmpty()) report.add("Local EarthSmash instances=[]");
        else {
            report.add("Local EarthSmash instances:");
            report.addAll(smashes);
        }
        return List.copyOf(report);
    }


    private void notePredictedAbilityState0(boolean invulnerable, boolean flying,
                                            boolean allowFlying, boolean creativeMode, float flySpeed, float walkSpeed) {
        if (!this.ready) return;
        long actionSequence = this.currentAction();
        Action action = this.actions.get(actionSequence);
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (action == null || player == null) return;
        int ordinal = action.abilityStateOrdinals.merge(player.getId(), 1, Integer::sum);
        this.playerStateAuthority.predictAbilityState(this.tick, actionSequence, ordinal);
    }

    private void noteAbilityStateOwner0(Entity localPlayer, AbilityStateOwner owner) {
        if (!this.ready) return;
        this.playerStateAuthority.recordAbilityOwner(
                localPlayer, owner, this.tick, this::localActionSequence
        );
    }

    private void notePredictedExperience0(float barProgress, int experience, int level) {
        if (!this.ready) return;
        this.playerStateAuthority.predictExperience(
                this.tick, barProgress, experience, level
        );
    }

    private boolean notePredictedSelectedSlot0(int slot) {
        if (slot < 0 || slot > 8) {
            return false;
        } else if (this.ready && this.bendingPlayer != null && this.bendingPlayer.getPlayer() != null) {
            SlotResult result = CommonInputHandler.handleSlotChange(this.bendingPlayer.getPlayer(), slot);
            return result.accepted();
        } else {
            return true;
        }
    }

    private boolean suppressAuthoritativeAbilityState0(PlayerAbilitiesS2CPacket packet) {
        return this.ready && this.playerStateAuthority.suppressAbilityPacket(
                packet, this.tick, this.hasLocalFlightLease()
        );
    }


    private boolean hasLocalFlightLease() {
        if (this.bendingPlayer != null && this.bendingPlayer.getPlayer() != null) {
            try {
                return ((FlightHandler) Manager.getManager(FlightHandler.class)).getInstance(this.bendingPlayer.getPlayer()) != null;
            } catch (RuntimeException unavailable) {
                return false;
            }
        } else {
            return false;
        }
    }


    private void reconcileActiveFlightAbilities0(List<String> activeAbilities, long acknowledgedSequence) {
        if (this.ready && this.bendingPlayer != null && this.bendingPlayer.getPlayer() != null) {
            long latestLocalSequence = this.latestLocalSequence();
            long localAcknowledgement = this.localAcknowledgedSequence(acknowledgedSequence);
            if (!PredictionStateOrdering.snapshotCoversLatestInput(localAcknowledgement, latestLocalSequence)) {
                debug("runtime deferred flight snapshot ack=" + acknowledgedSequence + " localAck=" + localAcknowledgement + " latestLocal=" + latestLocalSequence);
            } else {
                Set<String> next = new HashSet<>();
                if (activeAbilities != null) {
                    for (String ability : activeAbilities) {
                        if (ability != null) {
                            String normalized = ability.toLowerCase(Locale.ROOT);
                            if (PERSISTENT_FLIGHT_ABILITIES.contains(normalized)) {
                                next.add(normalized);
                            }
                        }
                    }
                }

                this.authoritativeFlightAbilities = Set.copyOf(next);
                this.authoritativeFlightSequence = acknowledgedSequence;
                debug(
                        "runtime observed sequence-fenced Paper flight snapshot ack="
                                + acknowledgedSequence
                                + " localAck="
                                + localAcknowledgement
                                + " active="
                                + this.authoritativeFlightAbilities
                );
            }
        }
    }

    private long latestLocalSequence() {
        return this.actions.keySet().stream().mapToLong(Long::longValue).max().orElse(0L);
    }

    private void forceRemoveAbility(CoreAbility ability) {
        if (ability != null && !ability.isRemoved()) {
            try {
                this.tempBlockAuthority.removeAbility(
                        ability,
                        () -> PredictedContactSync.forceRemoval(
                                ability, () -> AbilityExecutionContext.run(ability, ability::remove)
                        )
                );
            } finally {
                this.abilityActions.remove(ability);
                this.abilityCreationActions.remove(ability);
                this.authoritativelyEstablishedAbilities.remove(ability);
            }
        }
    }


    private boolean suppressAuthoritativeExperience0(ExperienceBarUpdateS2CPacket packet) {
        return this.ready
                && this.playerStateAuthority.suppressExperiencePacket(packet, this.tick);
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
        return this.bendingPlayer == null ? "<none>" : String.format(Locale.ROOT, "%.4f", this.bendingPlayer.getAirBlastDecay());
    }

    private String activeAirBlastSummary() {
        if (this.bendingPlayer == null) {
            return "[]";
        }

        List<String> summary = new ArrayList<>();

        for (AirBlast blast : CoreAbility.getAbilities(this.bendingPlayer.getPlayer(), AirBlast.class)) {
            summary.add(
                    "{progressing="
                            + blast.isProgressing()
                            + ",fromOther="
                            + blast.isFromOtherOrigin()
                            + ",ticks="
                            + blast.getTicks()
                            + ",action="
                            + this.abilityActions.get(blast)
                            + ",creation="
                            + this.abilityCreationActions.get(blast)
                            + ",loc="
                            + compactLocation(blast.getLocation())
                            + ",origin="
                            + compactLocation(blast.getOrigin())
                            + ",radius="
                            + String.format(Locale.ROOT, "%.3f", blast.getRadius())
                            + "}"
            );
        }

        return summary.toString();
    }

    private static String velocityString(Vec3d velocity) {
        return velocity == null ? "<null>" : String.format(Locale.ROOT, "(%.4f, %.4f, %.4f)", velocity.x, velocity.y, velocity.z);
    }

    private static String compactLocation(Location location) {
        return location == null ? "<null>" : String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", location.getX(), location.getY(), location.getZ());
    }

    private void trackSpawn0(Entity entity) {
        long action = this.currentAction();
        com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action owner = this.actions.get(action);
        if (owner != null && entity != null) {
            owner.spawned.add(entity);
            this.entityReconciliation.trackSpawn(action, entity);
        }
    }

    public int beforeSpawn(CoreAbility ability, Location location, BlockData blockData) {
        if ((this.ready || this.initializing) && ability != null) {
            long sequence = this.currentAction();
            if (sequence <= 0L) {
                sequence = this.abilityActions.getOrDefault(ability, 0L);
            }

            com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action = this.actions.get(sequence);
            return action == null ? 0 : ++action.tempFallingBlockOrdinal;
        } else {
            return 0;
        }
    }

    public void onSpawn(CoreAbility ability, FallingBlock fallingBlock, int spawnOrdinal) {
        if ((this.ready || this.initializing) && ability != null && fallingBlock != null && spawnOrdinal > 0 && fallingBlock.handle() instanceof Entity entity) {
            long sequence = this.currentAction();
            if (sequence <= 0L) {
                sequence = this.abilityActions.getOrDefault(ability, 0L);
            }

            com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action = this.actions.get(sequence);
            if (action != null) {
                this.entityReconciliation.trackTempFallingBlock(sequence, spawnOrdinal, entity, ability.getName());
            }
        }
    }

    private void noteTempFallingBlockPrepare0(Entity localPlayer, TempFallingBlockPrepare prepare) {
        if (this.ready && localPlayer != null && prepare != null && prepare.actionSequence() > 0L && localPlayer.getUuid().equals(prepare.abilityOwner())) {
            long localSequence = this.localActionSequence(prepare.actionSequence());
            this.entityReconciliation.notePrepare(localPlayer, prepare, localSequence, this.tick);
        }
    }

    private void noteTempFallingBlock0(Entity localPlayer, TempFallingBlockReceipt receipt) {
        if (this.ready && localPlayer != null && receipt != null && receipt.actionSequence() > 0L && localPlayer.getUuid().equals(receipt.abilityOwner())) {
            long localSequence = this.localActionSequence(receipt.actionSequence());
            ClientWorld world = MinecraftClient.getInstance().world;
            this.entityReconciliation.noteReceipt(localPlayer, receipt, localSequence, world);
        }
    }

    private boolean reconcileSpawn0(EntitySpawnS2CPacket packet) {
        return this.ready && this.entityReconciliation.reconcileSpawn(packet, MinecraftClient.getInstance().world, this.tick);
    }

    private long currentAction() {
        Long input = INPUT_ACTION.get();
        if (input != null) {
            return input;
        }

        CoreAbility ability = AbilityExecutionContext.current();
        if (ability == null) {
            return 0L;
        }

        Long associated = this.abilityActions.get(ability);
        if (associated != null && associated > 0L) {
            return associated;
        }

        long inherited = ability.getPredictionActionSequence();
        com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action = inherited <= 0L ? null : this.actions.get(inherited);
        if (action == null) {
            return 0L;
        }

        this.abilityActions.put(ability, inherited);
        this.abilityCreationActions.putIfAbsent(ability, inherited);
        action.abilities.add(ability);
        debug("runtime inherited child action=" + inherited + " ability=" + ability.getName() + " instance=" + System.identityHashCode(ability));
        return inherited;
    }

    private com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose executionPose0() {
        Long eventAction = INPUT_EVENT_POSE.get();
        com.projectkorra.projectkorra.fabric.client.ExactPredictionRuntime.Action action = eventAction == null ? null : this.actions.get(eventAction);
        return action == null
                ? null
                : new com.projectkorra.projectkorra.fabric.client.PredictionClient.ServerPose(
                action.origin.x, action.origin.y - action.eyeHeight, action.origin.z, action.yaw, action.pitch, action.eyeHeight
        );
    }

    private static boolean finite(Vec3d value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static BlockState materialState(String materialName) {
        if (materialName != null && !materialName.contains(";")) {
            return FabricMC.blockState(materialName);
        }

        String[] fields = materialName == null ? new String[0] : materialName.trim().split(";");

        Material material;
        try {
            String key = fields.length == 0 ? "" : fields[0];
            int namespace = key.indexOf(58);
            if (namespace >= 0) {
                key = key.substring(namespace + 1);
            }

            material = key.isBlank() ? Material.AIR : Material.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            material = Material.AIR;
        }

        BlockData data = material.createBlockData();

        for (int i = 1; i < fields.length; i++) {
            int separator = fields[i].indexOf(61);
            if (separator > 0) {
                String name = fields[i].substring(0, separator);
                String value = fields[i].substring(separator + 1);

                try {
                    if (data instanceof Levelled levelled) {
                        if (name.equals("level")) {
                            levelled.setLevel(Integer.parseInt(value));
                        } else if (name.equals("waterlogged")) {
                            levelled.setWaterlogged(value.equals("1"));
                        }
                    } else if (data instanceof Fire fire && name.equals("faces") && !value.isBlank()) {
                        for (String face : value.split(",")) {
                            fire.setFace(BlockFace.valueOf(face), true);
                        }
                    } else if (data instanceof Snow snow && name.equals("layers")) {
                        snow.setLayers(Math.max(1, Math.min(8, Integer.parseInt(value))));
                    }
                } catch (IllegalArgumentException var16) {
                }
            }
        }

        return FabricMC.blockState(data);
    }

    private static boolean matchesWorld(String clientWorld, String serverWorld) {
        if (serverWorld == null || serverWorld.isBlank()) {
            return false;
        } else {
            return clientWorld.equals(serverWorld)
                    ? true
                    : serverWorld.indexOf(58) < 0 && ("minecraft:overworld".equals(clientWorld) || "overworld".equals(clientWorld));
        }
    }

    private static boolean close(Vec3d first, Vec3d second, double tolerance) {
        return first.squaredDistanceTo(second) <= tolerance * tolerance;
    }


    private static void debug(String message) {
        if (DEBUG) {
            System.out.println("[ProjectKorraPrediction] " + message);
        }
    }

    private static final class Action {
        final long sequence;
        final long createdTick;
        final Vec3d origin;
        final float yaw;
        final float pitch;
        final double eyeHeight;
        final String inputAbility;
        final InputKind kind;
        final int selectedSlot;
        final long deterministicSeed;
        final Set<CoreAbility> abilities = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Entity> spawned = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<CoreAbility, Long> previousAbilityActions = new IdentityHashMap<>();
        final Map<Integer, Integer> velocityOrdinals = new HashMap<>();
        final Map<Integer, Integer> abilityStateOrdinals = new HashMap<>();
        final Map<String, Integer> directBlockOrdinals = new HashMap<>();
        final Set<UUID> claimedTargets = new HashSet<>();
        int tempFallingBlockOrdinal;
        int tempBlockOrdinal;
        boolean reconciled;
        boolean nativeConfirmed;
        boolean locallyPredicted;
        boolean executed;
        boolean inputHandled;
        boolean comboRecorded;
        boolean recoveredFromAuthority;
        AbilityInformation comboInput;
        int blockConfirmationTicks = 40;

        private Action(
                long sequence, long createdTick, Vec3d origin, float yaw, float pitch, double eyeHeight, String inputAbility, InputKind kind, int selectedSlot
        ) {
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
                    kind == null ? "" : kind.name(), selectedSlot, this.inputAbility, origin.x, origin.y, origin.z, yaw, pitch
            );
        }

        private ClientNativeActionCorrelation.Candidate correlationCandidate() {
            return new ClientNativeActionCorrelation.Candidate(sequence, kind, selectedSlot,
                    inputAbility, origin.x, origin.y, origin.z, yaw, pitch);
        }
    }

    public record PredictionDesyncBlock(BlockPos pos, BlockState predicted, BlockState authoritative) {
    }

}
