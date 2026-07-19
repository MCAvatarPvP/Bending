package com.projectkorra.projectkorra.fabric.prediction;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.ConfirmedHitEffects;
import com.projectkorra.projectkorra.prediction.CooldownSync;
import com.projectkorra.projectkorra.prediction.DirectBlockSync;
import com.projectkorra.projectkorra.prediction.PredictionActionSeed;
import com.projectkorra.projectkorra.prediction.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.RegionProtectionAuthority;
import com.projectkorra.projectkorra.fabric.FabricGameplayBridge;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.prediction.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.AbilityStateSync;
import com.projectkorra.projectkorra.prediction.PredictionVisibility;
import com.projectkorra.projectkorra.prediction.TempBlockSync;
import com.projectkorra.projectkorra.prediction.TempBlockDeliveryTracker;
import com.projectkorra.projectkorra.prediction.TempFallingBlockSync;
import com.projectkorra.projectkorra.prediction.VelocitySync;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.firebending.FireBlastCharged;
import com.jedk1.jedcore.ability.passive.WallRun;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Server authority for client prediction. Client coordinates are evidence, not
 * state: origin, cooldown, damage, source selection and final blocks always
 * come from the server runtime.
 */
public final class PredictionServer implements TempBlockSync.Listener,
        TempFallingBlockSync.Listener, CooldownSync.Listener, VelocitySync.Listener,
        AbilityRemovalSync.Listener, DirectBlockSync.Listener,
        AbilityStateSync.Listener {
    public static final int MAX_REWIND_TICKS = 12;
    private static final int CAPABILITY_EXACT = 8;
    private static final int TEMP_BLOCK_OPS_PER_PACKET = 4;
    private static final int CLAIMS_PER_SECOND = 48;
    private static final double CLAIM_CONTACT_TOLERANCE = 0.75;
    private static final double CLAIM_QUERY_TOLERANCE = 1.0;
    private static final double MAX_CLAIM_DISTANCE_SQUARED = 160.0 * 160.0;
    private static final List<String> FABRIC_GAMEPLAY_PERMISSION_CANDIDATES = List.of(
            "bending.avatar",
            "bending.air.passive", "bending.water.passive", "bending.earth.passive",
            "bending.fire.passive", "bending.chi.passive",
            "bending.air.flightbending", "bending.air.spiritualprojection",
            "bending.water.bloodbending", "bending.water.healing", "bending.water.icebending",
            "bending.water.plantbending", "bending.earth.metalbending",
            "bending.earth.lavabending", "bending.earth.sandbending",
            "bending.fire.combustionbending", "bending.fire.lightningbending");
    private static PredictionServer active;
    private static boolean receiversRegistered;
    private static final ThreadLocal<UUID> INPUT_OWNER = new ThreadLocal<>();
    private static final ThreadLocal<Long> INPUT_SEQUENCE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> INPUT_LOCALLY_PREDICTED = new ThreadLocal<>();

    private final MinecraftServer server;
    private final FabricGameplayBridge gameplay;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, ArrayDeque<EntityFrame>> playerHistory = new HashMap<>();
    private final Map<CoreAbility, Action> abilityActions = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<CoreAbility, Action> abilityCreationActions = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<Long, Action> tempLayerActions = new HashMap<>();
    private final Map<Long, TempEffectIdentity> tempLayerEffects = new HashMap<>();
    private final Set<Long> serverOwnedTempLayers = new HashSet<>();
    private final List<PendingTempBlock> pendingTempBlocks = new ArrayList<>();
    private final List<PendingAbilityRemoval> pendingAbilityRemovals = new ArrayList<>();
    private final Map<UUID, Integer> uncorrelatedExternalVelocityOrdinals = new HashMap<>();
    private volatile List<PredictionPayloads.ConfigEntry> publicConfig = List.of();
    private volatile List<PredictionPayloads.AbilityProfile> profiles = List.of();
    private volatile Map<String, PredictionPayloads.AbilityProfile> profilesByName = Map.of();
    private volatile long configEpoch;
    private volatile boolean snapshotReady;
    private final AtomicBoolean snapshotBuildRunning = new AtomicBoolean();
    private long tick;

    private PredictionServer(MinecraftServer server, FabricGameplayBridge gameplay) {
        this.server = server;
        this.gameplay = gameplay;
    }

    public static synchronized PredictionServer start(MinecraftServer server, FabricGameplayBridge gameplay) {
        PredictionPayloads.registerTypes();
        registerReceivers();
        PredictionServer prediction = new PredictionServer(server, gameplay);
        active = prediction;
        TempBlockSync.install(prediction);
        DirectBlockSync.install(prediction);
        TempFallingBlockSync.install(prediction);
        VelocitySync.install(prediction);
        AbilityStateSync.install(prediction);
        AbilityRemovalSync.install(prediction);
        CooldownSync.install(prediction);
        prediction.requestPublicSnapshotRebuild(false);
        return prediction;
    }

    public void stop() {
        TempBlockSync.clear(this);
        DirectBlockSync.clear(this);
        TempFallingBlockSync.clear(this);
        VelocitySync.clear(this);
        AbilityStateSync.clear(this);
        AbilityRemovalSync.clear(this);
        CooldownSync.clear(this);
        sessions.clear();
        abilityActions.clear();
        abilityCreationActions.clear();
        tempLayerActions.clear();
        tempLayerEffects.clear();
        serverOwnedTempLayers.clear();
        pendingTempBlocks.clear();
        pendingAbilityRemovals.clear();
        playerHistory.clear();
        if (active == this) active = null;
    }

    /** Sends the complete destination-world TempBlock ledger at the world boundary. */
    public static void synchronizeWorld(final ServerPlayerEntity player) {
        final PredictionServer prediction = active;
        if (prediction == null || player == null || prediction.server != player.getEntityWorld().getServer()) return;
        final Session session = prediction.sessions.get(player.getUuid());
        if (session != null && session.ready) {
            prediction.sendWorldState(player, session);
            prediction.sendTempBlockSnapshot(player, session);
        }
    }

    @Override
    public void onAdded(CoreAbility source, BendingPlayer player, String ability, long expiresAtMillis) {
        ServerPlayerEntity predictedOwner = predictedEffectOwner();
        final UUID playerId = player == null || player.getPlayer() == null
                ? null : player.getPlayer().getUniqueId();
        final Action lifecycleAction = source == null ? null : actionForEffect(source);
        final boolean predictedInputWrite = predictedOwner != null && playerId != null
                && predictedOwner.getUuid().equals(playerId);
        final boolean predictedLifecycleWrite = lifecycleAction != null && playerId != null
                && lifecycleAction.locallyPredicted && lifecycleAction.owner.equals(playerId);
        if (predictedInputWrite || predictedLifecycleWrite) {
            final Session session = sessions.get(playerId);
            if (session != null && ability != null && !ability.isBlank()) {
                session.predictedCooldowns.add(ability.toLowerCase(Locale.ROOT));
            }
            return;
        }
        sendDirective(player, "", ability == null ? "" : ability, expiresAtMillis, false, Double.NaN);
    }

    @Override
    public void onRemoved(BendingPlayer player, String ability) {
        final UUID playerId = player == null || player.getPlayer() == null
                ? null : player.getPlayer().getUniqueId();
        final Session session = playerId == null ? null : sessions.get(playerId);
        if (session != null && ability != null
                && session.predictedCooldowns.remove(ability.toLowerCase(Locale.ROOT))) return;
        ServerPlayerEntity predictedOwner = predictedEffectOwner();
        if (predictedOwner != null && player != null && player.getPlayer() != null
                && predictedOwner.getUuid().equals(player.getPlayer().getUniqueId())) return;
        sendDirective(player, ability == null ? "" : ability, "", 0L, false, Double.NaN);
    }

    @Override
    public void onAirBlastReset(BendingPlayer player) {
        ServerPlayerEntity predictedOwner = predictedEffectOwner();
        if (predictedOwner != null && player != null && player.getPlayer() != null
                && predictedOwner.getUuid().equals(player.getPlayer().getUniqueId())) return;
        sendDirective(player, "", "", 0L, true, Double.NaN);
    }

    @Override
    public void onAirBlastRegenerated(BendingPlayer player) {
        sendDirective(player, "", "", 0L, false, player == null ? Double.NaN : player.getAirBlastDecay());
    }

    private void sendDirective(BendingPlayer bending, String removedCooldown, String addedCooldown,
                               long cooldownUntil, boolean resetAirBlast, double airBlastDecay) {
        if (bending == null || bending.getPlayer() == null) return;
        Session session = sessions.get(bending.getPlayer().getUniqueId());
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(bending.getPlayer().getUniqueId());
        if (session != null && player != null && ServerPlayNetworking.canSend(player, PredictionPayloads.StateDirective.ID)) {
            ServerPlayNetworking.send(player, new PredictionPayloads.StateDirective(session.sessionId,
                    removedCooldown, addedCooldown, cooldownUntil, System.currentTimeMillis(), resetAirBlast, airBlastDecay));
        }
    }

    public void tick() {
        tick++;
        recordPlayerHistory();
        uncorrelatedExternalVelocityOrdinals.clear();
        flushTempBlocks();
        flushAbilityRemovals();
        sessions.entrySet().removeIf(entry -> {
            if (server.getPlayerManager().getPlayer(entry.getKey()) != null) return false;
            return true;
        });
        abilityActions.entrySet().removeIf(entry -> entry.getKey().isRemoved() || !sessions.containsKey(entry.getValue().owner));
        abilityCreationActions.entrySet().removeIf(entry -> entry.getKey().isRemoved()
                || !sessions.containsKey(entry.getValue().owner));

        if (tick % 20 == 0) {
            syncPlayerStateChanges();
            for (Session session : sessions.values()) {
                final ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.playerId);
                if (player != null) {
                    sendWorldState(player, session);
                    sendTempBlockSnapshot(player, session);
                }
            }
        }
        if (tick % 100 == 0) {
            requestPublicSnapshotRebuild(true);
        }
    }

    /**
     * Returns the modded owner who already rendered the currently executing
     * predicted effect locally. Server adapters exclude only this viewer;
     * everybody else still receives the normal particle/sound packet.
     */
    public static ServerPlayerEntity predictedEffectOwner() {
        PredictionServer prediction = active;
        if (prediction == null) return null;
        UUID owner = INPUT_OWNER.get();
        if (owner == null) {
            CoreAbility ability = AbilityExecutionContext.current();
            Action action = ability == null ? null : prediction.actionForEffect(ability);
            if (action != null && !action.locallyPredicted) return null;
            owner = action == null ? null : action.owner;
        } else if (!Boolean.TRUE.equals(INPUT_LOCALLY_PREDICTED.get())) {
            return null;
        }
        Session session = owner == null ? null : prediction.sessions.get(owner);
        return session != null && (session.capabilities & 8) != 0
                ? prediction.server.getPlayerManager().getPlayer(owner) : null;
    }

    public static ServerPlayerEntity predictedSoundEffectOwner() {
        if (ConfirmedHitEffects.isBroadcastingAuthoritativeSound()) return null;
        return predictedEffectOwner();
    }

    /** Adds only server-validated historical player boxes to a real ability query. */
    public static void augmentNearbyPlayers(
            final ServerWorld world, final Box query, final CoreAbility ability,
            final Predicate<com.projectkorra.projectkorra.platform.mc.entity.Entity> filter,
            final Map<Integer, com.projectkorra.projectkorra.platform.mc.entity.Entity> result) {
        final PredictionServer prediction = active;
        if (prediction == null || world == null || query == null || result == null) return;
        final Action action = ability == null ? null : prediction.actionForEffect(ability);
        if (action == null) return;
        final var claims = action.claims.values().iterator();
        while (claims.hasNext()) {
            final Claim claim = claims.next();
            if (claim.expiresTick < prediction.tick) {
                claims.remove();
                continue;
            }
            final ServerPlayerEntity target = prediction.server.getPlayerManager().getPlayer(claim.target);
            if (target == null || target.isDead() || target.getEntityWorld() != world) continue;
            if (!query.expand(CLAIM_QUERY_TOLERANCE).intersects(claim.rewoundBox)) continue;
            final com.projectkorra.projectkorra.platform.mc.entity.Entity wrapped = FabricMC.player(target);
            if (wrapped == null || filter != null && !filter.test(wrapped)) continue;
            result.put(target.getId(), wrapped);
            // A claim may extend one real query, once. Consuming it here keeps
            // abilities with several entity scans in one progress pass from
            // applying the same damage/velocity impulse repeatedly.
            claims.remove();
        }
    }

    private Action actionForEffect(CoreAbility ability) {
        if (ability == null || ability.getPlayer() == null) return null;
        final UUID ownerId = ability.getPlayer().getUniqueId();
        Action action = currentInputAction(ownerId);
        if (action != null) return action;
        action = abilityActions.get(ability);
        if (action != null) return action;
        Session session = sessions.get(ownerId);
        if (session == null) return null;
        final long inherited = ability.getPredictionActionSequence();
        action = inherited <= 0L ? null : session.actions.get(inherited);
        if (action != null) {
            abilityActions.put(ability, action);
            abilityCreationActions.putIfAbsent(ability, action);
            return action;
        }
        List<Action> recent = new ArrayList<>(session.actions.values());
        for (int i = recent.size() - 1; i >= 0; i--) {
            Action candidate = recent.get(i);
            if (candidate.locallyPredicted
                    && candidate.abilityName.equalsIgnoreCase(ability.getName())) {
                abilityActions.put(ability, candidate);
                return candidate;
            }
        }
        for (int i = recent.size() - 1; i >= 0; i--) {
            Action candidate = recent.get(i);
            if (candidate.locallyPredicted && tick - candidate.acceptedServerTick <= 4) {
                abilityActions.put(ability, candidate);
                return candidate;
            }
        }
        return null;
    }

    /** Mirrors the client's INPUT_ACTION precedence for synchronous effects. */
    private Action currentInputAction(final UUID ownerId) {
        final Long sequence = INPUT_SEQUENCE.get();
        if (ownerId == null || sequence == null) return null;
        final Session session = sessions.get(ownerId);
        return session == null ? null : session.actions.get(sequence);
    }

    @Override
    public void beforeChange(final CoreAbility ability, final Block block,
                             final BlockData replacement, final boolean packetExpected) {
        if (block == null || block.getWorld() == null || replacement == null) return;
        final DirectBlockSync.EarthLifecycle lifecycle = DirectBlockSync.currentEarthLifecycle();
        final UUID ownerId = ability != null && ability.getPlayer() != null
                ? ability.getPlayer().getUniqueId()
                : lifecycle != null && lifecycle.valid() ? lifecycle.ownerId() : INPUT_OWNER.get();
        final Session session = ownerId == null ? null : sessions.get(ownerId);
        Action action = currentInputAction(ownerId);
        if (action == null && ability != null) action = actionForEffect(ability);
        if (action != null && !action.owner.equals(ownerId)) return;
        final long actionSequence = action != null ? action.sequence
                : lifecycle != null && lifecycle.valid() ? lifecycle.actionSequence() : 0L;
        final String abilityName = ability != null ? ability.getName()
                : lifecycle != null && lifecycle.valid() ? lifecycle.ability()
                : action == null ? "" : action.abilityName;
        if (!DirectBlockSync.isPredictable(ability, abilityName)) return;
        final ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerId);
        if (actionSequence <= 0L || session == null || owner == null || (session.capabilities & 8) == 0
                || !ServerPlayNetworking.canSend(owner, PredictionPayloads.DirectBlockReceipt.ID)) return;
        final DirectBlockCause cause = new DirectBlockCause(actionSequence,
                abilityName.toLowerCase(Locale.ROOT));
        final int ordinal;
        synchronized (session.directBlockOrdinals) {
            ordinal = session.directBlockOrdinals.merge(cause, 1, Integer::sum);
            while (session.directBlockOrdinals.size() > 4_096) {
                session.directBlockOrdinals.remove(session.directBlockOrdinals.keySet().iterator().next());
            }
        }
        if (action != null) action.directBlockOrdinals.put(cause.ability, ordinal);
        if (!packetExpected) return;
        ServerPlayNetworking.send(owner, new PredictionPayloads.DirectBlockReceipt(
                tick, actionSequence, ordinal, ownerId, abilityName, block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), TempBlockSync.encode(replacement),
                lifecycle != null && lifecycle.valid()));
    }

    /** Captures prediction ownership for delayed tasks created by an ability. */
    public static UUID captureEffectOwner() {
        ServerPlayerEntity owner = predictedEffectOwner();
        return owner == null ? null : owner.getUuid();
    }

    /** Captures both halves of delayed-effect causality. */
    public static EffectContext captureEffectContext() {
        PredictionServer prediction = active;
        ServerPlayerEntity owner = predictedEffectOwner();
        if (prediction == null || owner == null) return null;
        final UUID ownerId = owner.getUuid();
        Long sequence = INPUT_SEQUENCE.get();
        long deterministicSeed = 0L;
        final Session session = prediction.sessions.get(ownerId);
        final Action currentAction = sequence == null || session == null ? null : session.actions.get(sequence);
        if (currentAction != null) deterministicSeed = currentAction.deterministicSeed;
        if (currentAction == null) {
            final CoreAbility ability = AbilityExecutionContext.current();
            final Action action = ability == null ? null : prediction.actionForEffect(ability);
            sequence = action != null && ownerId.equals(action.owner) ? action.sequence : null;
            if (action != null && ownerId.equals(action.owner)) deterministicSeed = action.deterministicSeed;
        }
        return new EffectContext(ownerId, sequence == null ? 0L : sequence, deterministicSeed);
    }

    /** Restores captured ownership while a delayed ability task emits effects. */
    public static void runWithEffectOwner(UUID owner, Runnable task) {
        runWithEffectContext(owner == null ? null : new EffectContext(owner, 0L, 0L), task);
    }

    /** Restores captured owner and native-action identity for a delayed task. */
    public static void runWithEffectContext(final EffectContext context, final Runnable task) {
        if (context == null || context.owner == null) {
            task.run();
            return;
        }
        UUID previous = INPUT_OWNER.get();
        Boolean previousPredicted = INPUT_LOCALLY_PREDICTED.get();
        Long previousSequence = INPUT_SEQUENCE.get();
        INPUT_OWNER.set(context.owner);
        INPUT_LOCALLY_PREDICTED.set(Boolean.TRUE);
        if (context.actionSequence > 0L) INPUT_SEQUENCE.set(context.actionSequence);
        else INPUT_SEQUENCE.remove();
        try {
            if (context.actionSequence > 0L) PredictionDeterminism.run(context.actionSequence,
                    context.deterministicSeed > 0L ? context.deterministicSeed : context.actionSequence, task);
            else task.run();
        } finally {
            if (previous == null) INPUT_OWNER.remove(); else INPUT_OWNER.set(previous);
            if (previousPredicted == null) INPUT_LOCALLY_PREDICTED.remove(); else INPUT_LOCALLY_PREDICTED.set(previousPredicted);
            if (previousSequence == null) INPUT_SEQUENCE.remove(); else INPUT_SEQUENCE.set(previousSequence);
        }
    }

    public record EffectContext(UUID owner, long actionSequence, long deterministicSeed) {
    }

    public static boolean handleVanillaInput(ServerPlayerEntity player, PredictionPayloads.InputKind kind,
                                             BooleanSupplier nativeInput) {
        PredictionServer prediction = active;
        if (prediction == null || player == null || kind == null || nativeInput == null) {
            return nativeInput != null && nativeInput.getAsBoolean();
        }
        return prediction.handleVanilla0(player, kind, nativeInput);
    }

    private boolean handleVanilla0(final ServerPlayerEntity player,
                                   final PredictionPayloads.InputKind kind,
                                   final BooleanSupplier nativeInput) {
        final Session session = sessions.get(player.getUuid());
        if (session == null || !session.ready) return nativeInput.getAsBoolean();
        return processInput(player, session, kind, nativeInput);
    }

    @Override
    public void beforeWorldChange(final TempBlockSync.Change change) {
        queueTempBlock(change);
        flushTempBlocks();
    }

    @Override
    public void onChange(TempBlockSync.Change change) {
        if (change.packetExpected()) return;
        queueTempBlock(change);
    }

    private void queueTempBlock(final TempBlockSync.Change change) {
        PredictionPayloads.TempOperation wireOperation = switch (change.operation()) {
            case CREATE -> PredictionPayloads.TempOperation.CREATE;
            case UPDATE_EXPIRY -> PredictionPayloads.TempOperation.UPDATE_EXPIRY;
            case REVERT -> PredictionPayloads.TempOperation.REVERT;
            case DISCARD -> PredictionPayloads.TempOperation.DISCARD;
        };
        Block block = change.block();
        CoreAbility effectiveAbility = change.ability() == null ? AbilityExecutionContext.current() : change.ability();
        final UUID effectOwner = change.ownerId() == null ? INPUT_OWNER.get() : change.ownerId();
        Action currentAction = currentInputAction(effectOwner);
        if (currentAction == null && effectiveAbility != null) currentAction = actionForEffect(effectiveAbility);
        Action action = tempLayerActions.get(change.layerId());
        if (action == null && !serverOwnedTempLayers.contains(change.layerId())
                && change.operation() != TempBlockSync.Operation.REVERT
                && change.operation() != TempBlockSync.Operation.DISCARD) {
            if (currentAction != null && currentAction.owner.equals(effectOwner)) {
                tempLayerActions.put(change.layerId(), currentAction);
                action = currentAction;
            } else {
                serverOwnedTempLayers.add(change.layerId());
            }
        }
        TempEffectIdentity effect = tempLayerEffects.get(change.layerId());
        if (effect == null && action != null
                && change.operation() != TempBlockSync.Operation.REVERT
                && change.operation() != TempBlockSync.Operation.DISCARD) {
            final String semanticAbility = change.effectAbility() == null || change.effectAbility().isBlank()
                    ? effectiveAbility == null ? action.abilityName : effectiveAbility.getName()
                    : change.effectAbility();
            effect = new TempEffectIdentity(semanticAbility, 0L, ++action.tempBlockOrdinal);
            tempLayerEffects.put(change.layerId(), effect);
        }
        final String effectAbility = effect == null ? change.effectAbility() : effect.ability;
        final long effectStep = effect == null ? change.effectStep() : effect.step;
        final int effectOrdinal = effect == null ? change.effectOrdinal() : effect.ordinal;
        final UUID predictedOwner = predictedTempBlockOwner(change.ownerId(), action, effectAbility);
        final Map<UUID, String> ownerViews = predictedOwnerViews(block, predictedOwner, change.data());
        pendingTempBlocks.add(new PendingTempBlock(new PredictionPayloads.TempBlockOp(wireOperation, block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), TempBlockSync.encode(change.data()),
                (change.operation() == TempBlockSync.Operation.REVERT
                        || change.operation() == TempBlockSync.Operation.DISCARD) ? 0L : change.revertAtMillis(),
                action == null ? 0L : action.sequence,
                effectAbility, effectStep, effectOrdinal,
                change.layerId(), change.revision(), predictedOwner,
                TempBlockSync.encode(change.data()), change.packetExpected()), Map.copyOf(ownerViews)));
        if (change.operation() == TempBlockSync.Operation.REVERT
                || change.operation() == TempBlockSync.Operation.DISCARD) {
            tempLayerActions.remove(change.layerId());
            tempLayerEffects.remove(change.layerId());
            serverOwnedTempLayers.remove(change.layerId());
        }
    }

    private UUID predictedTempBlockOwner(final UUID layerOwner, final Action action,
                                         final String abilityName) {
        final UUID candidate = layerOwner != null ? layerOwner : action == null ? null : action.owner;
        if (candidate == null) return null;
        final Session session = sessions.get(candidate);
        if (session == null || !session.ready || (session.capabilities & CAPABILITY_EXACT) == 0) return null;
        if (action != null && candidate.equals(action.owner)) return candidate;
        return abilityName != null && session.supportedAbilities.contains(abilityName.toLowerCase(Locale.ROOT))
                ? candidate : null;
    }

    private Map<UUID, String> predictedOwnerViews(final Block block, final UUID closingOwner,
                                                  final BlockData fallbackData) {
        final Map<UUID, String> views = new HashMap<>();
        TempBlock.getOwnerViews(block, closingOwner).forEach((owner, data) ->
                views.put(owner, TempBlockSync.encode(data)));
        if (closingOwner != null) {
            views.put(closingOwner, TempBlockSync.encode(
                    predictedViewerData(block, closingOwner, fallbackData)));
        }
        return views;
    }

    private BlockData predictedViewerData(final Block block, final UUID viewer,
                                          final BlockData fallbackData) {
        final BlockData visible = TempBlock.getVisibleData(block, viewer);
        return visible != null ? visible
                : fallbackData == null ? block.getBlockData().clone() : fallbackData.clone();
    }

    @Override
    public int beforeSpawn(final CoreAbility ability,
                           final com.projectkorra.projectkorra.platform.mc.Location location,
                           final BlockData blockData) {
        if (ability.getPlayer() == null || location == null || location.getWorld() == null
                || blockData == null) return 0;
        final UUID ownerId = ability.getPlayer().getUniqueId();
        final Session ownerSession = sessions.get(ownerId);
        final ServerPlayerEntity nativeOwner = server.getPlayerManager().getPlayer(ownerId);
        if (ownerSession == null || nativeOwner == null || (ownerSession.capabilities & 8) == 0
                || !ServerPlayNetworking.canSend(nativeOwner, PredictionPayloads.TempFallingBlockPrepare.ID)) return 0;

        Action action = currentInputAction(ownerId);
        if (action == null) action = actionForEffect(ability);
        if (action == null || !ownerId.equals(action.owner)) return 0;

        final int ordinal = ++action.tempFallingBlockOrdinal;
        ServerPlayNetworking.send(nativeOwner, new PredictionPayloads.TempFallingBlockPrepare(
                tick, action.sequence, ordinal, ownerId, ability.getName(), location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), TempBlockSync.encode(blockData)));
        return ordinal;
    }

    @Override
    public void onSpawn(final CoreAbility ability,
                        final com.projectkorra.projectkorra.platform.mc.entity.FallingBlock fallingBlock,
                        final int spawnOrdinal) {
        if (ability.getPlayer() == null || fallingBlock.getEntityId() <= 0 || spawnOrdinal <= 0) return;
        final UUID ownerId = ability.getPlayer().getUniqueId();
        final Session ownerSession = sessions.get(ownerId);
        final ServerPlayerEntity nativeOwner = server.getPlayerManager().getPlayer(ownerId);
        if (ownerSession == null || nativeOwner == null || (ownerSession.capabilities & 8) == 0
                || !ServerPlayNetworking.canSend(nativeOwner, PredictionPayloads.TempFallingBlockReceipt.ID)) return;
        Action action = currentInputAction(ownerId);
        if (action == null) action = actionForEffect(ability);
        if (action == null || !ownerId.equals(action.owner)) return;
        ServerPlayNetworking.send(nativeOwner, new PredictionPayloads.TempFallingBlockReceipt(
                tick, action.sequence, spawnOrdinal, ownerId, fallingBlock.getEntityId(), ability.getName()));
    }

    @Override
    public void onVelocity(Ability ability,
                           com.projectkorra.projectkorra.platform.mc.entity.Entity target,
                           Vector velocity) {
        if (!(ability instanceof CoreAbility coreAbility) || ability.getPlayer() == null || target == null) return;
        UUID ownerId = ability.getPlayer().getUniqueId();
        UUID targetId = target.getUniqueId();
        Session ownerSession = sessions.get(ownerId);
        Session targetSession = sessions.get(targetId);
        ServerPlayerEntity nativeTarget = server.getPlayerManager().getPlayer(targetId);
        ServerPlayerEntity nativeOwner = server.getPlayerManager().getPlayer(ownerId);
        if (nativeTarget == null) return;

        // Do not use the recent-action fallback for velocity ownership.
        Action action = currentInputAction(ownerId);
        if (action == null) action = abilityActions.get(coreAbility);
        if (action == null) {
            final Session session = sessions.get(ownerId);
            final long inherited = coreAbility.getPredictionActionSequence();
            action = session == null || inherited <= 0L ? null : session.actions.get(inherited);
            if (action != null) abilityActions.put(coreAbility, action);
        }
        if (action == null || !action.owner.equals(ownerId)) {
            if (!ownerId.equals(targetId) && targetSession != null
                    && (targetSession.capabilities & 8) != 0
                    && ServerPlayNetworking.canSend(nativeTarget, PredictionPayloads.VelocityOwnerV2.ID)) {
                final int ordinal = uncorrelatedExternalVelocityOrdinals.merge(
                        targetId, 1, Integer::sum);
                flushAbilityRemovals();
                ServerPlayNetworking.send(nativeTarget, new PredictionPayloads.VelocityOwnerV2(
                        tick, 0L, ordinal, ownerId, targetId, nativeTarget.getId(), ability.getName(),
                        velocity.getX(), velocity.getY(), velocity.getZ()));
            }
            return;
        }

        int ordinal = action.velocityOrdinals.merge(targetId, 1, Integer::sum);
        flushAbilityRemovals();
        PredictionPayloads.VelocityOwnerV2 receipt = new PredictionPayloads.VelocityOwnerV2(tick, action.sequence, ordinal,
                ownerId, targetId, nativeTarget.getId(), ability.getName(),
                velocity.getX(), velocity.getY(), velocity.getZ());
        if (ownerSession != null && nativeOwner != null && (ownerSession.capabilities & 8) != 0
                && ServerPlayNetworking.canSend(nativeOwner, PredictionPayloads.VelocityOwnerV2.ID)) {
            ServerPlayNetworking.send(nativeOwner, receipt);
        }
        if (!ownerId.equals(targetId) && targetSession != null && (targetSession.capabilities & 8) != 0
                && ServerPlayNetworking.canSend(nativeTarget, PredictionPayloads.VelocityOwnerV2.ID)) {
            ServerPlayNetworking.send(nativeTarget, receipt);
        }
    }

    @Override
    public void beforeWrite(final CoreAbility ability, final Player target,
                            final AbilityStateSync.FlightState resultingState) {
        if (target == null) return;
        final UUID targetId = target.getUniqueId();
        final UUID contextualOwner = INPUT_OWNER.get();
        final UUID ownerId = ability != null && ability.getPlayer() != null
                ? ability.getPlayer().getUniqueId()
                : contextualOwner == null ? targetId : contextualOwner;
        Action action = currentInputAction(ownerId);
        if (action == null && ability != null) action = abilityActions.get(ability);
        if (action == null && ability != null) {
            final Session session = sessions.get(ownerId);
            final long inherited = ability.getPredictionActionSequence();
            action = session == null || inherited <= 0L ? null : session.actions.get(inherited);
            if (action != null) abilityActions.put(ability, action);
        }
        if (action == null || !action.owner.equals(ownerId)) return;
        final Session targetSession = sessions.get(targetId);
        final ServerPlayerEntity nativeTarget = server.getPlayerManager().getPlayer(targetId);
        if (targetSession == null || nativeTarget == null
                || (targetSession.capabilities & 8) == 0
                || !ServerPlayNetworking.canSend(nativeTarget, PredictionPayloads.AbilityStateOwner.ID)) return;
        final int ordinal = action.abilityStateOrdinals.merge(targetId, 1, Integer::sum);
        ServerPlayNetworking.send(nativeTarget, new PredictionPayloads.AbilityStateOwner(
                tick, action.sequence, ordinal, ownerId, targetId,
                ability == null ? action.abilityName : ability.getName(),
                resultingState.flying(), resultingState.allowFlight(), resultingState.flySpeed()));
    }

    @Override
    public void onRemoved(CoreAbility ability, boolean externallyCaused) {
        if (ability.getPlayer() == null || !ability.isStarted()) return;
        // Removal identifies the instance that was created, not the most
        // recent input that happened to mutate an ability of the same name.
        // AirBlast can have several live instances; using its latest action
        // lets an old projectile delete a newly staged source on the client.
        Action action = abilityCreationActions.get(ability);
        UUID playerId = ability.getPlayer().getUniqueId();
        pendingAbilityRemovals.add(new PendingAbilityRemoval(playerId, ability.getName(),
                AbilityRemovalSync.typeId(ability),
                action != null && action.locallyPredicted ? action.sequence : 0L,
                externallyCaused, ability));
    }

    private void flushAbilityRemovals() {
        if (pendingAbilityRemovals.isEmpty()) return;
        final List<PendingAbilityRemoval> removals = List.copyOf(pendingAbilityRemovals);
        pendingAbilityRemovals.clear();
        for (PendingAbilityRemoval removal : removals) {
            abilityActions.remove(removal.instance);
            abilityCreationActions.remove(removal.instance);
            final Session session = sessions.get(removal.playerId);
            final ServerPlayerEntity player = server.getPlayerManager().getPlayer(removal.playerId);
            if (session == null || player == null
                    || !ServerPlayNetworking.canSend(player, PredictionPayloads.AbilityRemoved.ID)) continue;
            final int remainingTypeInstances = AbilityRemovalSync.activeTypeCount(
                    removal.playerId, removal.abilityType);
            ServerPlayNetworking.send(player, new PredictionPayloads.AbilityRemoved(
                    removal.playerId, removal.ability, removal.abilityType, removal.actionSequence,
                    removal.externallyCaused, session.lastSequence, remainingTypeInstances));
        }
    }

    private static synchronized void registerReceivers() {
        if (receiversRegistered) return;
        receiversRegistered = true;
        ServerPlayNetworking.registerGlobalReceiver(PredictionPayloads.ClientHello.ID, (payload, context) -> {
            PredictionServer prediction = active;
            if (prediction != null && prediction.server == context.server()) prediction.onHello(context.player(), payload);
        });
        ServerPlayNetworking.registerGlobalReceiver(PredictionPayloads.ClientReady.ID, (payload, context) -> {
            PredictionServer prediction = active;
            if (prediction != null && prediction.server == context.server()) prediction.onReady(context.player(), payload);
        });
        ServerPlayNetworking.registerGlobalReceiver(PredictionPayloads.InputVeto.ID, (payload, context) -> {
            PredictionServer prediction = active;
            if (prediction != null && prediction.server == context.server()) prediction.onInputVeto(context.player(), payload);
        });
        ServerPlayNetworking.registerGlobalReceiver(PredictionPayloads.ActionTag.ID, (payload, context) -> {
            PredictionServer prediction = active;
            if (prediction != null && prediction.server == context.server()) prediction.onActionTag(context.player(), payload);
        });
        ServerPlayNetworking.registerGlobalReceiver(PredictionPayloads.HitClaim.ID, (payload, context) -> {
            PredictionServer prediction = active;
            if (prediction != null && prediction.server == context.server()) prediction.onHitClaim(context.player(), payload);
        });
    }

    private void onHello(ServerPlayerEntity player, PredictionPayloads.ClientHello hello) {
        if (hello.protocolVersion() != PredictionPayloads.PROTOCOL_VERSION) return;
        Session session = new Session(player.getUuid(), UUID.randomUUID(), hello.capabilities(),
                hello.clientTick(), tick);
        sessions.put(player.getUuid(), session);
        if (snapshotReady) sendSnapshot(session); else requestPublicSnapshotRebuild(false);
    }

    private void onReady(ServerPlayerEntity player, PredictionPayloads.ClientReady ready) {
        final Session session = validSession(player, ready.sessionId());
        if (session == null) return;
        final boolean wasReady = session.ready;
        final Set<String> supported = new HashSet<>();
        for (String ability : ready.supportedAbilities()) {
            if (ability != null && !ability.isBlank()) supported.add(ability.toLowerCase(Locale.ROOT));
        }
        session.supportedAbilities = Set.copyOf(supported);
        if (!session.ready) {
            session.actions.clear();
            session.inputVetoes.clear();
            session.actionTags.clear();
            synchronized (session.directBlockOrdinals) {
                session.directBlockOrdinals.clear();
            }
            session.predictedCooldowns.clear();
            session.lastSequence = 0L;
            session.ready = true;
        }
        if (wasReady) {
            sendWorldState(player, session);
            sendTempBlockSnapshot(player, session);
        }
    }

    private void onInputVeto(ServerPlayerEntity player, PredictionPayloads.InputVeto veto) {
        final Session session = validSession(player, veto.sessionId());
        if (session == null || !session.ready || veto.kind() == null
                || veto.ability() == null || veto.ability().isBlank()
                || veto.actionSequence() <= 0L || session.inputVetoes.size() >= 128) return;
        session.inputVetoes.addLast(veto);
    }

    private void onActionTag(final ServerPlayerEntity player, final PredictionPayloads.ActionTag tag) {
        final Session session = validSession(player, tag.sessionId());
        if (session == null || !session.ready || tag.clientActionSequence() <= 0L
                || tag.kind() == null || tag.selectedSlot() < 0 || tag.selectedSlot() > 8
                || tag.ability() == null || tag.ability().isBlank()) return;
        if (!attachActionTag(session, tag) && session.actionTags.size() < 128) {
            session.actionTags.addLast(tag);
        }
    }

    private boolean attachActionTag(final Session session, final PredictionPayloads.ActionTag tag) {
        final List<Action> actions = new ArrayList<>(session.actions.values());
        for (int index = actions.size() - 1; index >= 0; index--) {
            final Action action = actions.get(index);
            if (action.clientSequence == 0L && tick - action.acceptedServerTick <= 4L
                    && action.kind == tag.kind() && action.selectedSlot == tag.selectedSlot()
                    && action.abilityName.equalsIgnoreCase(tag.ability())) {
                action.clientSequence = tag.clientActionSequence();
                return true;
            }
        }
        return false;
    }

    private void attachPendingActionTag(final Session session, final Action action) {
        final var tags = session.actionTags.iterator();
        while (tags.hasNext()) {
            final PredictionPayloads.ActionTag tag = tags.next();
            if (tag.kind() != action.kind || tag.selectedSlot() != action.selectedSlot
                    || !action.abilityName.equalsIgnoreCase(tag.ability())) continue;
            action.clientSequence = tag.clientActionSequence();
            tags.remove();
            return;
        }
    }

    private void onHitClaim(final ServerPlayerEntity player, final PredictionPayloads.HitClaim hit) {
        final Session session = validSession(player, hit.sessionId());
        if (session == null || !session.ready
                || !session.claimLimiter.allow(tick, CLAIMS_PER_SECOND)
                || hit.clientActionSequence() <= 0L || hit.clientTick() < 0L
                || hit.targetUuid() == null || hit.ability() == null || hit.ability().isBlank()
                || !finite(hit.contactX(), hit.contactY(), hit.contactZ())) return;
        final Action action = findClaimAction(session, hit);
        if (action == null || !action.locallyPredicted
                || tick - action.acceptedServerTick > 200L
                || action.claims.containsKey(hit.targetUuid())) return;
        final ServerPlayerEntity target = server.getPlayerManager().getPlayer(hit.targetUuid());
        if (target == null || target == player || target.isDead() || target.isSpectator()
                || target.getId() != hit.targetEntityId()
                || target.getEntityWorld() != player.getEntityWorld()) return;

        final int defenderPing = target.networkHandler.getLatency();
        final long rewindTick = session.mapClientTick(hit.clientTick(), tick,
                player.networkHandler.getLatency(), defenderPing);
        final EntityFrame frame = frameAt(target.getUuid(), rewindTick);
        if (frame == null || !frame.world.equals(
                target.getEntityWorld().getRegistryKey().getValue().toString())) return;
        final Vec3d contact = new Vec3d(hit.contactX(), hit.contactY(), hit.contactZ());
        if (!frame.box.expand(CLAIM_CONTACT_TOLERANCE).contains(contact)
                || contact.squaredDistanceTo(new Vec3d(action.eyeX, action.eyeY, action.eyeZ))
                > MAX_CLAIM_DISTANCE_SQUARED) return;
        final int rewindTicks = com.projectkorra.projectkorra.prediction.HitRewind.combinedRewindTicks(
                player.networkHandler.getLatency(), defenderPing, MAX_REWIND_TICKS);
        action.claims.put(target.getUuid(), new Claim(target.getUuid(), rewindTick,
                tick + Math.max(4, rewindTicks), contact, frame.box));
    }

    private Action findClaimAction(final Session session, final PredictionPayloads.HitClaim hit) {
        if (hit.serverActionSequence() > 0L) {
            final Action exact = session.actions.get(hit.serverActionSequence());
            if (matchesClaimAction(exact, hit)) return exact;
        }
        final List<Action> actions = new ArrayList<>(session.actions.values());
        for (int index = actions.size() - 1; index >= 0; index--) {
            final Action candidate = actions.get(index);
            if (candidate.clientSequence == hit.clientActionSequence()
                    && matchesClaimAction(candidate, hit)) return candidate;
        }
        for (int index = actions.size() - 1; index >= 0; index--) {
            final Action candidate = actions.get(index);
            if (tick - candidate.acceptedServerTick <= 4L
                    && matchesClaimAction(candidate, hit)) return candidate;
        }
        return null;
    }

    private static boolean matchesClaimAction(final Action action,
                                              final PredictionPayloads.HitClaim hit) {
        return action != null && hit != null
                && action.abilityName.equalsIgnoreCase(hit.ability());
    }

    private void recordPlayerHistory() {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            final ArrayDeque<EntityFrame> frames = playerHistory.computeIfAbsent(
                    player.getUuid(), ignored -> new ArrayDeque<>());
            frames.addLast(new EntityFrame(tick,
                    player.getEntityWorld().getRegistryKey().getValue().toString(),
                    player.getBoundingBox()));
            while (!frames.isEmpty()
                    && tick - frames.getFirst().serverTick > MAX_REWIND_TICKS + 4L) {
                frames.removeFirst();
            }
        }
        playerHistory.keySet().removeIf(uuid -> server.getPlayerManager().getPlayer(uuid) == null);
    }

    private EntityFrame frameAt(final UUID playerId, final long wantedTick) {
        final ArrayDeque<EntityFrame> frames = playerHistory.get(playerId);
        if (frames == null || frames.isEmpty()) return null;
        EntityFrame best = frames.getFirst();
        long bestDistance = Math.abs(best.serverTick - wantedTick);
        for (EntityFrame frame : frames) {
            final long distance = Math.abs(frame.serverTick - wantedTick);
            if (distance < bestDistance) {
                best = frame;
                bestDistance = distance;
            }
        }
        return best;
    }

    private boolean processInput(ServerPlayerEntity player, Session session,
                                 PredictionPayloads.InputKind kind,
                                 BooleanSupplier nativeInput) {
        if (player == null || player.isRemoved()
                || server.getPlayerManager().getPlayer(player.getUuid()) != player
                || sessions.get(player.getUuid()) != session) {
            return nativeInput.getAsBoolean();
        }
        final long sequence = ++session.lastSequence;
        final Vec3d origin = player.getEyePos();
        final BendingPlayer bending = BendingPlayer.getBendingPlayer(FabricMC.player(player));
        final Player commonPlayer = FabricMC.player(player);
        final int selectedSlot = player.getInventory().getSelectedSlot();
        final String fallback = bending == null ? ""
                : bending.getAbilities().get(selectedSlot + 1);
        final String abilityName = logicalInputAbility(commonPlayer, bending, kind, fallback);
        final boolean predictable = !abilityName.isBlank()
                && session.supportedAbilities.contains(abilityName.toLowerCase(Locale.ROOT));
        if (ServerPlayNetworking.canSend(player, PredictionPayloads.NativeAction.ID)) {
            ServerPlayNetworking.send(player, new PredictionPayloads.NativeAction(
                    session.sessionId, sequence, tick, kind, selectedSlot, abilityName,
                    origin.x, origin.y, origin.z, player.getYaw(), player.getPitch(), predictable));
        }
        final PredictionPayloads.InputVeto veto = session.inputVetoes.pollFirst();
        final boolean locallyRejectedOnCooldown = predictable && veto != null
                && veto.kind() == kind && abilityName.equalsIgnoreCase(veto.ability());
        if (!predictable) return nativeInput.getAsBoolean();

        final Action action = new Action(player.getUuid(), sequence, tick, kind, selectedSlot, abilityName,
                origin.x, origin.y, origin.z, player.getYaw(), player.getPitch(),
                profile(abilityName), PredictionActionSeed.from(kind.name(), selectedSlot, abilityName,
                origin.x, origin.y, origin.z, player.getYaw(), player.getPitch()), true);
        session.actions.put(sequence, action);
        attachPendingActionTag(session, action);
        final Set<CoreAbility> before = identitySet(CoreAbility.getAbilitiesByInstances());
        final ComboManager.AbilityInformation comboBefore = latestComboInput(commonPlayer);
        final boolean hadExistingMatchingAbility = before.stream().anyMatch(candidate -> candidate.getPlayer() != null
                && candidate.getPlayer().getUniqueId().equals(player.getUuid())
                && matchesInputAbility(candidate, abilityName));
        final boolean[] nativeResult = {false};
        final UUID previousOwner = INPUT_OWNER.get();
        final Long previousSequence = INPUT_SEQUENCE.get();
        final Boolean previousPredicted = INPUT_LOCALLY_PREDICTED.get();
        AbilityActivationManager.TrackingResult trackingResult;
        INPUT_OWNER.set(player.getUuid());
        INPUT_SEQUENCE.set(sequence);
        INPUT_LOCALLY_PREDICTED.set(Boolean.TRUE);
        AbilityActivationManager.beginTracking();
        try {
            PredictionDeterminism.run(sequence, action.deterministicSeed,
                    () -> nativeResult[0] = locallyRejectedOnCooldown
                            ? CooldownSync.runInputVeto(player.getUuid(),
                            inputVetoCooldowns(abilityName, kind), nativeInput::getAsBoolean)
                            : nativeInput.getAsBoolean());
        } finally {
            trackingResult = AbilityActivationManager.finishTrackingResult();
            if (previousOwner == null) INPUT_OWNER.remove(); else INPUT_OWNER.set(previousOwner);
            if (previousSequence == null) INPUT_SEQUENCE.remove(); else INPUT_SEQUENCE.set(previousSequence);
            if (previousPredicted == null) INPUT_LOCALLY_PREDICTED.remove();
            else INPUT_LOCALLY_PREDICTED.set(previousPredicted);
        }
        final boolean comboRecorded = latestComboInput(commonPlayer) != comboBefore;

        while (session.actions.size() > 128) {
            session.actions.remove(session.actions.keySet().iterator().next());
        }

        boolean createdAnyAbility = false;
        boolean createdMatchingAbility = false;
        final List<String> createdAbilities = new ArrayList<>();
        for (CoreAbility candidate : CoreAbility.getAbilitiesByInstances()) {
            if (candidate.getPlayer() == null
                    || !candidate.getPlayer().getUniqueId().equals(player.getUuid())) continue;
            if (!before.contains(candidate)) {
                createdAnyAbility = true;
                createdAbilities.add(candidate.getName());
                abilityCreationActions.putIfAbsent(candidate, action);
                abilityActions.put(candidate, action);
                if (matchesInputAbility(candidate, abilityName)) createdMatchingAbility = true;
            }
        }
        boolean explicitlyMappedExisting = false;
        for (CoreAbility candidate : trackingResult.affectedAbilities()) {
            if (!before.contains(candidate) || candidate.isRemoved() || candidate.getPlayer() == null
                    || !candidate.getPlayer().getUniqueId().equals(player.getUuid())) continue;
            abilityActions.put(candidate, action);
            explicitlyMappedExisting = true;
        }
        final boolean implicitExistingTransition = trackingResult.handled() && hadExistingMatchingAbility;
        if (!explicitlyMappedExisting && implicitExistingTransition && !createdMatchingAbility) {
            for (CoreAbility candidate : CoreAbility.getAbilitiesByInstances()) {
                if (before.contains(candidate) && !candidate.isRemoved() && candidate.getPlayer() != null
                        && candidate.getPlayer().getUniqueId().equals(player.getUuid())
                        && matchesInputAbility(candidate, abilityName)) {
                    abilityActions.put(candidate, action);
                }
            }
        }

        action.locallyPredicted = createdAnyAbility || trackingResult.handled()
                || action.tempBlockOrdinal > 0 || action.tempFallingBlockOrdinal > 0
                || !action.directBlockOrdinals.isEmpty() || !action.velocityOrdinals.isEmpty()
                || !action.abilityStateOrdinals.isEmpty();

        flushTempBlocks();
        final boolean accepted = !locallyRejectedOnCooldown || action.locallyPredicted;
        final String reason = locallyRejectedOnCooldown
                ? action.locallyPredicted ? "accepted_combo" : "client_cooldown"
                : "accepted";
        createdAbilities.sort(String.CASE_INSENSITIVE_ORDER);
        reconcile(player, session, sequence, accepted, reason, abilityName, origin, 0L,
                trackingResult.handled(), comboRecorded, List.copyOf(createdAbilities));
        return nativeResult[0];
    }

    private void flushTempBlocks() {
        if (pendingTempBlocks.isEmpty()) return;
        List<PendingTempBlock> batch = List.copyOf(pendingTempBlocks);
        pendingTempBlocks.clear();
        for (Session session : sessions.values()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.playerId);
            if (player != null && ServerPlayNetworking.canSend(player, PredictionPayloads.TempBlockBatch.ID)) {
                final WorldScope scope = refreshWorldScope(player, session);
                String viewerWorld = scope.identity();
                List<PredictionPayloads.TempBlockOp> visible = new ArrayList<>();
                for (PendingTempBlock pending : batch) {
                    final long layerId = pending.operation.layerId();
                    final boolean inView = PredictionVisibility.tracksBlock(viewerWorld, pending.operation.world(),
                            (int) Math.floor(player.getX()), (int) Math.floor(player.getZ()),
                            pending.operation.x(), pending.operation.z(), server.getPlayerManager().getViewDistance());
                    final boolean closesLayer = pending.operation.operation() == PredictionPayloads.TempOperation.REVERT
                            || pending.operation.operation() == PredictionPayloads.TempOperation.DISCARD;
                    if (!session.tempLayers.route(layerId, closesLayer, inView)) continue;
                    visible.add(pending.forViewer(session.playerId));
                }
                if (!visible.isEmpty()) {
                    sendTempBlockOperations(player, session, visible, false);
                }
            }
        }
    }

    private void sendTempBlockOperations(final ServerPlayerEntity player, final Session session,
                                          final List<PredictionPayloads.TempBlockOp> operations,
                                          final boolean snapshot) {
        final long now = System.currentTimeMillis();
        final WorldScope scope = refreshWorldScope(player, session);
        if (operations.isEmpty()) {
            ServerPlayNetworking.send(player, new PredictionPayloads.TempBlockBatch(
                    session.sessionId, scope.generation(), scope.identity(), snapshot, tick, now, List.of()));
            return;
        }
        for (int start = 0; start < operations.size(); start += TEMP_BLOCK_OPS_PER_PACKET) {
            ServerPlayNetworking.send(player, new PredictionPayloads.TempBlockBatch(
                    session.sessionId, scope.generation(), scope.identity(), snapshot, tick, now,
                    operations.subList(start, Math.min(start + TEMP_BLOCK_OPS_PER_PACKET, operations.size()))));
        }
    }

    private void syncPlayerStateChanges() {
        for (Session session : sessions.values()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.playerId);
            if (player == null) continue;
            BendingPlayer bending = BendingPlayer.getBendingPlayer(FabricMC.player(player));
            Map<Integer, String> binds = PredictionSnapshotBuilder.binds(bending);
            Map<String, Long> cooldowns = PredictionSnapshotBuilder.cooldowns(bending);
            List<String> elements = PredictionSnapshotBuilder.elements(bending);
            List<String> subElements = PredictionSnapshotBuilder.subElements(bending);
            List<String> permissions = predictionPermissions(FabricMC.player(player));
            double airBlastDecay = bending == null ? 1.0 : bending.getAirBlastDecay();
            boolean chiBlocked = bending != null && bending.isChiBlocked();
            RegionProtectionAuthority.Snapshot regionProtection =
                    regionProtectionSnapshot(player, binds);
            List<String> activeFlights = activeFlightAbilities(player.getUuid());
            int digest = 31 * binds.hashCode() + cooldowns.hashCode();
            digest = 31 * digest + elements.hashCode();
            digest = 31 * digest + subElements.hashCode();
            digest = 31 * digest + permissions.hashCode();
            digest = 31 * digest + Double.hashCode(airBlastDecay);
            digest = 31 * digest + Boolean.hashCode(chiBlocked);
            digest = 31 * digest + regionProtection.hashCode();
            digest = 31 * digest + activeFlights.hashCode();
            digest = 31 * digest + Long.hashCode(session.lastSequence);
            if (digest == session.playerStateDigest) continue;
            session.playerStateDigest = digest;
            if (ServerPlayNetworking.canSend(player, PredictionPayloads.PlayerState.ID)) {
                ServerPlayNetworking.send(player, new PredictionPayloads.PlayerState(session.sessionId, tick,
                        System.currentTimeMillis(), session.lastSequence, binds, cooldowns, elements, subElements,
                        permissions, airBlastDecay, chiBlocked, regionProtection, activeFlights));
            }
        }
    }

    private static List<String> activeFlightAbilities(UUID playerId) {
        return CoreAbility.getAbilitiesByInstances().stream()
                .filter(ability -> !ability.isRemoved() && ability.getPlayer() != null
                        && playerId.equals(ability.getPlayer().getUniqueId()))
                .map(CoreAbility::getName)
                .filter(name -> name.equalsIgnoreCase("AirScooter") || name.equalsIgnoreCase("AirSpout")
                        || name.equalsIgnoreCase("WaterSpout") || name.equalsIgnoreCase("FireJet")
                        || name.equalsIgnoreCase("Flight"))
                .map(name -> name.toLowerCase(Locale.ROOT)).distinct().sorted().toList();
    }

    private static RegionProtectionAuthority.Snapshot regionProtectionSnapshot(
            final ServerPlayerEntity player, final Map<Integer, String> binds) {
        if (player == null) return RegionProtectionAuthority.Snapshot.empty();
        final List<String> relevant = new ArrayList<>();
        if (binds != null) relevant.addAll(binds.values());
        for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (ability == null || ability.isRemoved() || ability.getPlayer() == null
                    || !player.getUuid().equals(ability.getPlayer().getUniqueId())) continue;
            relevant.add(ability.getName());
        }
        return RegionProtectionAuthority.currentPoint(FabricMC.player(player),
                player.getEntityWorld().getRegistryKey().getValue().toString(), relevant);
    }

    private void requestPublicSnapshotRebuild(boolean broadcastChanges) {
        if (!snapshotBuildRunning.compareAndSet(false, true)) return;
        CompletableFuture.supplyAsync(() -> {
            List<PredictionPayloads.ConfigEntry> config = PredictionSnapshotBuilder.publicConfig();
            List<PredictionPayloads.AbilityProfile> abilityProfiles = PredictionSnapshotBuilder.profiles(config);
            return new PublicSnapshot(config, abilityProfiles,
                    Integer.toUnsignedLong(31 * config.hashCode() + abilityProfiles.hashCode()));
        }).whenComplete((snapshot, failure) -> server.execute(() -> {
            snapshotBuildRunning.set(false);
            if (active != this || failure != null || snapshot == null) {
                if (failure != null) System.err.println("[ProjectKorraPrediction] async config snapshot failed: " + failure.getMessage());
                return;
            }
            boolean first = !snapshotReady;
            boolean changed = snapshot.epoch != configEpoch;
            publicConfig = snapshot.config;
            profiles = snapshot.profiles;
            Map<String, PredictionPayloads.AbilityProfile> byName = new LinkedHashMap<>();
            for (PredictionPayloads.AbilityProfile profile : profiles) byName.put(profile.name().toLowerCase(Locale.ROOT), profile);
            profilesByName = Map.copyOf(byName);
            configEpoch = snapshot.epoch;
            snapshotReady = true;
            if (first || broadcastChanges && changed) for (Session session : sessions.values()) sendSnapshot(session);
        }));
    }

    private void sendSnapshot(Session session) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.playerId);
        if (player == null || !ServerPlayNetworking.canSend(player, PredictionPayloads.ServerSnapshot.ID)) return;
        BendingPlayer bending = BendingPlayer.getBendingPlayer(FabricMC.player(player));
        Map<Integer, String> binds = PredictionSnapshotBuilder.binds(bending);
        Map<String, Long> cooldowns = PredictionSnapshotBuilder.cooldowns(bending);
        List<String> elements = PredictionSnapshotBuilder.elements(bending);
        List<String> subElements = PredictionSnapshotBuilder.subElements(bending);
        List<String> permissions = predictionPermissions(FabricMC.player(player));
        double airBlastDecay = bending == null ? 1.0 : bending.getAirBlastDecay();
        boolean chiBlocked = bending != null && bending.isChiBlocked();
        RegionProtectionAuthority.Snapshot regionProtection =
                regionProtectionSnapshot(player, binds);
        session.playerStateDigest = 31 * (31 * (31 * (31 * (31 * (31 * (31 * binds.hashCode()
                + cooldowns.hashCode()) + elements.hashCode()) + subElements.hashCode())
                + permissions.hashCode()) + Double.hashCode(airBlastDecay))
                + Boolean.hashCode(chiBlocked)) + regionProtection.hashCode();
        ServerPlayNetworking.send(player, new PredictionPayloads.ServerSnapshot(PredictionPayloads.PROTOCOL_VERSION,
                session.sessionId, tick, System.currentTimeMillis(), configEpoch, MAX_REWIND_TICKS,
                publicConfig, profiles, binds, cooldowns, elements, subElements, permissions, airBlastDecay,
                chiBlocked, regionProtection));
        sendWorldState(player, session);
        sendTempBlockSnapshot(player, session);
    }

    private static List<String> predictionPermissions(final Player player) {
        if (player == null) return List.of();
        if (player.hasPermission("bending.admin")) return List.of("*");
        final Set<String> permissions = new java.util.TreeSet<>();
        // These prefixes exactly describe FabricMC's non-admin permission
        // policy; Paper never receives or emits synthetic wildcards.
        permissions.add("bending.ability.*");
        permissions.add("bending.element.*");
        permissions.add("bending.message.*");
        for (String candidate : FABRIC_GAMEPLAY_PERMISSION_CANDIDATES) {
            if (player.hasPermission(candidate)) permissions.add(candidate);
        }
        return List.copyOf(permissions);
    }

    private void sendTempBlockSnapshot(final ServerPlayerEntity player, final Session session) {
        if (!ServerPlayNetworking.canSend(player, PredictionPayloads.TempBlockBatch.ID)) return;
        final WorldScope scope = refreshWorldScope(player, session);
        final String viewerWorld = scope.identity();
        final List<PredictionPayloads.TempBlockOp> operations = new ArrayList<>();
        for (TempBlock layer : TempBlock.getActiveLayers()) {
            final Block block = layer.getBlock();
            if (block.getWorld() == null
                    || !PredictionVisibility.tracksBlock(viewerWorld, block.getWorld().getName(),
                    (int) Math.floor(player.getX()), (int) Math.floor(player.getZ()),
                    block.getX(), block.getZ(), server.getPlayerManager().getViewDistance())) continue;
            final Action action = tempLayerActions.get(layer.getLayerId());
            final String effectAbility = tempLayerEffects.containsKey(layer.getLayerId())
                    ? tempLayerEffects.get(layer.getLayerId()).ability : layer.getEffectAbility();
            final UUID predictedOwner = predictedTempBlockOwner(
                    layer.getOwnerId().orElse(null), action, effectAbility);
            final BlockData viewerData = predictedViewerData(block, session.playerId, block.getBlockData());
            operations.add(new PredictionPayloads.TempBlockOp(PredictionPayloads.TempOperation.CREATE,
                    block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                    TempBlockSync.encode(layer.getBlockData()), layer.getRevertTime(),
                    action == null ? 0L : action.sequence,
                    effectAbility,
                    tempLayerEffects.containsKey(layer.getLayerId())
                            ? tempLayerEffects.get(layer.getLayerId()).step : layer.getEffectStep(),
                    tempLayerEffects.containsKey(layer.getLayerId())
                            ? tempLayerEffects.get(layer.getLayerId()).ordinal : layer.getEffectOrdinal(),
                    layer.getLayerId(), layer.getRevision(),
                    predictedOwner, TempBlockSync.encode(viewerData), false));
            session.tempLayers.markActive(layer.getLayerId());
        }
        sendTempBlockOperations(player, session, operations, true);
    }

    private void sendWorldState(final ServerPlayerEntity player, final Session session) {
        if (!ServerPlayNetworking.canSend(player, PredictionPayloads.ServerWorldState.ID)) return;
        final WorldScope scope = refreshWorldScope(player, session);
        ServerPlayNetworking.send(player, new PredictionPayloads.ServerWorldState(
                session.sessionId, scope.generation(), scope.identity()));
    }

    private WorldScope refreshWorldScope(final ServerPlayerEntity player, final Session session) {
        final String identity = player.getEntityWorld().getRegistryKey().getValue().toString();
        if (!identity.equals(session.worldIdentity)) {
            session.worldIdentity = identity;
            session.worldGeneration++;
            session.tempLayers.clear();
        }
        return new WorldScope(session.worldGeneration, session.worldIdentity);
    }

    private void reconcile(ServerPlayerEntity player, Session session, long sequence, boolean accepted, String reason,
                           String ability, Vec3d origin, long cooldownUntil, boolean inputHandled,
                           boolean comboRecorded, List<String> createdAbilities) {
        if (ServerPlayNetworking.canSend(player, PredictionPayloads.Reconcile.ID)) {
            ServerPlayNetworking.send(player, new PredictionPayloads.Reconcile(session.sessionId, sequence, accepted,
                    reason, tick, System.currentTimeMillis(), ability, origin.x, origin.y, origin.z, cooldownUntil,
                    inputHandled, comboRecorded, createdAbilities));
        }
        if (isPersistentFlightAbility(ability)) {
            session.playerStateDigest = Integer.MIN_VALUE;
            syncPlayerStateChanges();
        }
    }

    private static boolean isPersistentFlightAbility(final String ability) {
        return ability != null && (ability.equalsIgnoreCase("AirScooter")
                || ability.equalsIgnoreCase("AirSpout")
                || ability.equalsIgnoreCase("WaterSpout")
                || ability.equalsIgnoreCase("FireJet")
                || ability.equalsIgnoreCase("Flight"));
    }

    private static ComboManager.AbilityInformation latestComboInput(final Player player) {
        if (player == null) return null;
        final List<ComboManager.AbilityInformation> recent = ComboManager.getRecentlyUsedAbilities(player, 1);
        return recent.isEmpty() ? null : recent.get(recent.size() - 1);
    }

    private Session validSession(ServerPlayerEntity player, UUID sessionId) {
        Session session = sessions.get(player.getUuid());
        return session != null && session.sessionId.equals(sessionId) ? session : null;
    }

    private PredictionPayloads.AbilityProfile profile(String abilityName) {
        return profilesByName.getOrDefault(abilityName.toLowerCase(Locale.ROOT),
                new PredictionPayloads.AbilityProfile(abilityName, "Unknown", PredictionPayloads.VisualKind.CAST,
                        0.0, 8.0, 0.75, 0L, 0L, "minecraft:air", false, false));
    }

    private static Set<CoreAbility> identitySet(Iterable<CoreAbility> abilities) {
        Set<CoreAbility> result = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CoreAbility ability : abilities) result.add(ability);
        return result;
    }

    private static String materialName(Material material) {
        return "minecraft:" + material.name().toLowerCase(Locale.ROOT);
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static boolean isSneakTransition(PredictionPayloads.InputKind kind) {
        return kind == PredictionPayloads.InputKind.SNEAK_START || kind == PredictionPayloads.InputKind.SNEAK_STOP;
    }

    private static List<String> inputVetoCooldowns(final String ability,
                                                   final PredictionPayloads.InputKind kind) {
        if (ability == null || ability.isBlank()) return List.of();
        if (ability.equalsIgnoreCase("PhaseChange")) {
            if (kind == PredictionPayloads.InputKind.LEFT_CLICK) {
                return List.of(ability, "PhaseChangeFreeze");
            }
            if (kind == PredictionPayloads.InputKind.SNEAK_START) {
                return List.of(ability, "PhaseChangeMelt");
            }
        }
        return List.of(ability);
    }

    private static String logicalInputAbility(Player player,
                                              BendingPlayer bending, PredictionPayloads.InputKind kind,
                                              String fallback) {
        if (player == null || bending == null) return safe(fallback);
        if (kind == PredictionPayloads.InputKind.SNEAK_START && !CoreAbility.hasAbility(player, FastSwim.class)) {
            CoreAbility bound = bending.getBoundAbility();
            CoreAbility passive = CoreAbility.getAbility(FastSwim.class);
            if ((bound == null || !bound.isSneakAbility()) && PassiveManager.hasPassive(player, passive)) {
                return "FastSwim";
            }
        }
        String multi = MultiAbilityManager.getBoundMultiAbility(player);
        if (multi != null && !multi.isBlank()) return multi;
        String selected = safe(fallback);
        if (selected.equalsIgnoreCase("FireBlast") && isSneakTransition(kind)) {
            return "FireBlastCharged";
        }
        if (selected.isBlank() && kind == PredictionPayloads.InputKind.LEFT_CLICK
                && bending.isToggled() && CoreAbility.getAbility(WallRun.class) != null) {
            return "WallRun";
        }
        return selected;
    }

    private static boolean matchesInputAbility(CoreAbility ability, String inputName) {
        return ability != null && inputName != null
                && (inputName.equalsIgnoreCase(ability.getName())
                || inputName.equalsIgnoreCase("FireBlastCharged") && ability instanceof FireBlastCharged);
    }

    private static final class Session {
        final UUID playerId;
        final UUID sessionId;
        final int capabilities;
        final long helloClientTick;
        final long helloServerTick;
        final LinkedHashMap<Long, Action> actions = new LinkedHashMap<>();
        final ArrayDeque<PredictionPayloads.InputVeto> inputVetoes = new ArrayDeque<>();
        final ArrayDeque<PredictionPayloads.ActionTag> actionTags = new ArrayDeque<>();
        final RateLimiter claimLimiter = new RateLimiter();
        final LinkedHashMap<DirectBlockCause, Integer> directBlockOrdinals = new LinkedHashMap<>();
        final Set<String> predictedCooldowns = new HashSet<>();
        final TempBlockDeliveryTracker tempLayers = new TempBlockDeliveryTracker();
        Set<String> supportedAbilities = Set.of();
        long lastSequence;
        long worldGeneration;
        String worldIdentity = "";
        int playerStateDigest;
        boolean ready;

        Session(UUID playerId, UUID sessionId, int capabilities,
                long helloClientTick, long helloServerTick) {
            this.playerId = playerId;
            this.sessionId = sessionId;
            this.capabilities = capabilities;
            this.helloClientTick = helloClientTick;
            this.helloServerTick = helloServerTick;
        }

        long mapClientTick(final long clientTick, final long currentServerTick,
                           final int attackerPing, final int defenderPing) {
            return com.projectkorra.projectkorra.prediction.HitRewind.mapClientTick(
                    helloClientTick, helloServerTick, clientTick, currentServerTick,
                    attackerPing, defenderPing, MAX_REWIND_TICKS);
        }
    }

    private record PublicSnapshot(List<PredictionPayloads.ConfigEntry> config,
                                  List<PredictionPayloads.AbilityProfile> profiles, long epoch) { }

    private record WorldScope(long generation, String identity) { }

    private record TempEffectIdentity(String ability, long step, int ordinal) { }

    private record PendingTempBlock(PredictionPayloads.TempBlockOp operation,
                                    Map<UUID, String> ownerViews) {
        private PredictionPayloads.TempBlockOp forViewer(final UUID viewer) {
            return new PredictionPayloads.TempBlockOp(operation.operation(), operation.world(),
                    operation.x(), operation.y(), operation.z(), operation.material(), operation.revertAtMillis(),
                    operation.actionSequence(), operation.effectAbility(), operation.effectStep(),
                    operation.effectOrdinal(), operation.layerId(), operation.revision(), operation.ownerId(),
                    ownerViews.getOrDefault(viewer, operation.material()), operation.packetExpected());
        }
    }

    private record PendingAbilityRemoval(UUID playerId, String ability, String abilityType, long actionSequence,
                                         boolean externallyCaused,
                                         CoreAbility instance) {
    }

    private static final class Action {
        final UUID owner;
        final long sequence;
        final long acceptedServerTick;
        final PredictionPayloads.InputKind kind;
        final int selectedSlot;
        final String abilityName;
        final double eyeX;
        final double eyeY;
        final double eyeZ;
        final float yaw;
        final float pitch;
        final PredictionPayloads.AbilityProfile profile;
        final long deterministicSeed;
        boolean locallyPredicted;
        long clientSequence;
        final Map<UUID, Claim> claims = new HashMap<>();
        final Map<UUID, Integer> velocityOrdinals = new HashMap<>();
        final Map<UUID, Integer> abilityStateOrdinals = new HashMap<>();
        final Map<String, Integer> directBlockOrdinals = new HashMap<>();
        int tempFallingBlockOrdinal;
        int tempBlockOrdinal;
        Action(UUID owner, long sequence, long acceptedServerTick,
               PredictionPayloads.InputKind kind, int selectedSlot,
               String abilityName, double eyeX, double eyeY, double eyeZ, float yaw, float pitch,
               PredictionPayloads.AbilityProfile profile, long deterministicSeed, boolean locallyPredicted) {
            this.owner = owner; this.sequence = sequence;
            this.acceptedServerTick = acceptedServerTick; this.abilityName = abilityName;
            this.kind = kind; this.selectedSlot = selectedSlot;
            this.eyeX = eyeX; this.eyeY = eyeY; this.eyeZ = eyeZ; this.yaw = yaw;
            this.pitch = pitch; this.profile = profile; this.deterministicSeed = deterministicSeed;
            this.locallyPredicted = locallyPredicted;
        }
    }

    private static final class Claim {
        final UUID target;
        final long rewindTick;
        final long expiresTick;
        final Vec3d contact;
        final Box rewoundBox;

        Claim(UUID target, long rewindTick, long expiresTick, Vec3d contact, Box rewoundBox) {
            this.target = target;
            this.rewindTick = rewindTick;
            this.expiresTick = expiresTick;
            this.contact = contact;
            this.rewoundBox = rewoundBox;
        }
    }

    private record EntityFrame(long serverTick, String world, Box box) {
    }

    private static final class RateLimiter {
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

    private record DirectBlockCause(long sequence, String ability) { }
}
