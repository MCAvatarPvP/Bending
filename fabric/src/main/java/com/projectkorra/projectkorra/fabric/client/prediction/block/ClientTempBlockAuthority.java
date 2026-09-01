package com.projectkorra.projectkorra.fabric.client.prediction.block;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.earthbending.EarthSmash;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.fabric.FabricPredictionMC;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.block.ClientTempBlockLedger;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;
import com.projectkorra.projectkorra.util.TempBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Owns local TempBlock prediction, Paper lifecycle pairing, and the TEMP layer
 * in the render-only client block overlay. Vanilla remains the sole writer of
 * the backing {@link ClientWorld}.
 *
 * <p>Pairing uses action + ability + semantic step/ordinal. Coordinates are a
 * rendered consequence and may legitimately differ between the two runtimes;
 * they are never used as lifecycle identity.</p>
 */
public final class ClientTempBlockAuthority implements TempBlockSync.Listener {
    private static final int ACTION_RETENTION_TICKS = 160;
    /**
     * A local close can lead Paper's matching close by a few network ticks. Do
     * not let that bridge turn into permanent concealment when Paper instead
     * keeps the layer alive.
     */
    private static final int CLOSED_PAIR_GRACE_TICKS = 20;
    private static final int HISTORY_LIMIT = 24;
    private static final int MAX_STAGED_SNAPSHOT_OPERATIONS = 65_536;

    public enum BatchResult {
        APPLIED,
        STAGED,
        RESYNC_REQUIRED
    }

    /** Action identity supplied by the runtime without exposing its model. */
    public interface Context {
        boolean ready();
        long tick();
        long currentAction();
        long actionForAbility(CoreAbility ability);
        String inputAbility(long actionSequence);
        int nextTempBlockOrdinal(long actionSequence);
        long localActionSequence(long paperSequence);
        int confirmationTicks(long actionSequence);
    }

    private final Context context;
    private final ClientDirectBlockAuthority directBlocks;
    private final ClientBlockVisualOverlay visualOverlay;
    private final Function<String, BlockState> blockStateDecoder;
    private final Consumer<String> debug;
    private final ClientTempBlockLedger<BlockKey, BlockState> serverLayers =
            new ClientTempBlockLedger<>();
    private final Map<Long, LocalLayer> localLayers = new LinkedHashMap<>();
    private final Map<BlockKey, Set<Long>> localLayersByCoordinate = new HashMap<>();
    private final Map<Long, BlockState> pendingUnderlays = new HashMap<>();
    private final Map<EffectKey, Long> localEffects = new HashMap<>();
    private final Map<Long, ServerLayer> authoritativeLayers = new HashMap<>();
    private final Map<BlockKey, NavigableMap<Long, ServerLayer>> authoritativeByCoordinate =
            new HashMap<>();
    private final Map<EffectKey, Long> authoritativeEffects = new HashMap<>();
    private final Map<Long, Long> pairedServerLayers = new HashMap<>();
    private final Map<BlockKey, Set<Long>> pairedCoordinates = new HashMap<>();
    private final Map<BlockKey, CompletedRestore> completedRestores = new HashMap<>();
    private final Set<Long> concealedLocalActions = new HashSet<>();
    private final List<String> teardownHistory = new ArrayList<>();
    private final List<String> authoritativeHistory = new ArrayList<>();
    private SnapshotAssembly stagedSnapshot;
    private long lastStreamSequence;
    private long lastCommittedSnapshotId;
    private boolean showServerLayers = Boolean.parseBoolean(
            System.getProperty("projectkorra.prediction.debug.server-temp-blocks", "false"));

    public ClientTempBlockAuthority(final Context context,
                                    final ClientDirectBlockAuthority directBlocks,
                                    final ClientBlockVisualOverlay visualOverlay,
                                    final Function<String, BlockState> blockStateDecoder,
                                    final Consumer<String> debug) {
        this.context = context;
        this.directBlocks = directBlocks;
        this.visualOverlay = visualOverlay;
        this.blockStateDecoder = blockStateDecoder;
        this.debug = debug == null ? ignored -> { } : debug;
    }

    @Override
    public void onChange(final TempBlockSync.Change change) {
        if (change == null || change.block() == null) return;
        if (change.ability() != null
                && !change.ability().tracksPredictedTempBlocks()) return;
        final BlockKey key = clientKey(change.block());
        if (change.operation() == TempBlockSync.Operation.REVERT
                || change.operation() == TempBlockSync.Operation.DISCARD) {
            pendingUnderlays.remove(change.layerId());
            final LocalLayer local = localLayers.get(change.layerId());
            if (local != null) {
                if (local.serverClosed) {
                    final BlockState finalState = decode(TempBlockSync.encode(change.data()));
                    updateCompletedRestores(change.layerId(), local.key, finalState);
                    final BlockKey closedKey = local.key;
                    detachLocalLayer(change.layerId());
                    refreshVisual(closedKey);
                    return;
                }
                // Retain a tombstone even when CREATE metadata has not yet
                // arrived, so a short-lived local layer cannot reconsolidate.
                local.closed = true;
                local.closedTick = context.tick();
                local.concealmentGraceReleased = false;
                local.closedRevision = change.revision();
                local.closedState = decode(TempBlockSync.encode(change.data()));
                updateCompletedRestores(change.layerId(), local.key, local.closedState);
                log("runtime retained predicted TempBlock close layer=" + change.layerId()
                        + " effect=" + local.effect + " pos=" + local.key.pos);
                refreshVisual(local.key);
                reconcileActionConcealment(local.actionSequence);
            }
            return;
        }

        long actionSequence = context.currentAction();
        if (actionSequence <= 0L && change.ability() != null) {
            actionSequence = context.actionForAbility(change.ability());
        }
        if (actionSequence <= 0L || key == null) return;

        LocalLayer local = localLayers.get(change.layerId());
        final BlockState createdState = decode(TempBlockSync.encode(change.data()));
        final BlockState pendingUnderlay = pendingUnderlays.remove(change.layerId());
        final BlockState capturedUnderlay = change.underlayData() == null
                ? null : decode(TempBlockSync.encode(change.underlayData()));
        final BlockState initialUnderlay = capturedUnderlay == null
                ? pendingUnderlay : capturedUnderlay;
        if (local == null) {
            String effectAbility = change.effectAbility();
            long effectStep = change.effectStep();
            int effectOrdinal = change.effectOrdinal();
            if (effectAbility == null || effectAbility.isBlank()) {
                effectAbility = change.ability() == null
                        ? context.inputAbility(actionSequence) : change.ability().getName();
            }
            final boolean stableEarthSmashSlot = change.ability() instanceof EarthSmash
                    && effectStep > 0L && effectOrdinal > 0;
            if (!stableEarthSmashSlot) {
                effectStep = 0L;
                effectOrdinal = context.nextTempBlockOrdinal(actionSequence);
            }
            final EffectKey effect = effectKey(actionSequence, effectAbility,
                    effectStep, effectOrdinal);
            local = new LocalLayer(actionSequence, key, effect, context.tick(),
                    createdState, initialUnderlay, change.ability());
            localLayers.put(change.layerId(), local);
            localLayersByCoordinate.computeIfAbsent(key, ignored -> new HashSet<>())
                    .add(change.layerId());
            if (effect != null) localEffects.putIfAbsent(effect, change.layerId());
        } else if (createdState != null) {
            local.createdStates.add(createdState);
        }
        tryMatchLocal(change.layerId(), local);
        refreshVisual(local.key);
        reconcileActionConcealment(local.actionSequence);
    }

    @Override
    public void beforeWorldChange(final TempBlockSync.Change change) {
        if (change == null || change.block() == null) return;
        if (change.ability() != null
                && !change.ability().tracksPredictedTempBlocks()) return;
        final BlockKey key = clientKey(change.block());
        if (key == null) return;
        if (change.operation() == TempBlockSync.Operation.CREATE) {
            pendingUnderlays.putIfAbsent(change.layerId(),
                    composedUnderlay(key, key.world.getBlockState(key.pos)));
            return;
        }
        if (change.operation() != TempBlockSync.Operation.DISCARD) return;

        final LocalLayer local = localLayers.get(change.layerId());
        if (local == null) {
            pendingUnderlays.remove(change.layerId());
            return;
        }
        local.closed = true;
        local.closedTick = context.tick();
        local.concealmentGraceReleased = false;
        local.closedRevision = change.revision();
        final BlockState capturedUnderlay = change.underlayData() == null
                ? null : decode(TempBlockSync.encode(change.underlayData()));
        local.closedState = local.authoritativeUnderlay != null
                ? local.authoritativeUnderlay
                : capturedUnderlay != null ? capturedUnderlay : local.initialUnderlay;
        updateCompletedRestores(change.layerId(), local.key, local.closedState);
        refreshVisual(local.key);
        reconcileActionConcealment(local.actionSequence);
        log("runtime closed external TempBlock handoff layer=" + change.layerId()
                + " clientPos=" + local.key.pos);
    }

    @Override
    public boolean hasAuthoritativeLayer(
            final com.projectkorra.projectkorra.platform.mc.block.Block block) {
        return topAuthoritative(clientKey(block)) != null;
    }

    @Override
    public String authoritativeEffectAbility(
            final com.projectkorra.projectkorra.platform.mc.block.Block block) {
        final ServerLayer top = topAuthoritative(clientKey(block));
        return top == null ? "" : top.effectAbility;
    }

    @Override
    public String authoritativeEffectState(
            final com.projectkorra.projectkorra.platform.mc.block.Block block) {
        final ServerLayer top = topAuthoritative(clientKey(block));
        return top == null ? "" : top.effectState;
    }

    @Override
    public UUID authoritativeOwnerId(
            final com.projectkorra.projectkorra.platform.mc.block.Block block) {
        final ServerLayer top = topAuthoritative(clientKey(block));
        return top == null ? null : top.ownerId;
    }

    @Override
    public BlockData authoritativeData(
            final com.projectkorra.projectkorra.platform.mc.block.Block block) {
        final ServerLayer top = topAuthoritative(clientKey(block));
        return top == null ? null : FabricMC.blockData(top.physicalState);
    }

    public boolean hasPredictionForAction(final long actionSequence) {
        return localLayers.values().stream()
                .anyMatch(local -> local.actionSequence == actionSequence);
    }

    /** Logical state seen by the common prediction runtime, if overridden. */
    public BlockState simulatedState(final ClientWorld world, final BlockPos pos) {
        if (world == null || pos == null) return null;
        return tempVisualState(new BlockKey(world, pos.toImmutable()));
    }

    /** Applies a common-client TempBlock while preserving composed authority. */
    public void predict(final ClientWorld world, final BlockPos pos, final BlockState state) {
        if (world == null || pos == null || state == null) return;
        final BlockKey key = new BlockKey(world, pos.toImmutable());
        directBlocks.removeMutation(world, pos);
        final TempBlockSync.WorldMutation mutation = TempBlockSync.currentWorldMutation();
        if (mutation != null && mutation.operation() == TempBlockSync.Operation.REVERT
                && clientState(world, pos) == null) {
            directBlocks.updateServerViewer(world, pos, state);
        }
        refreshVisual(key);
        log("runtime applied render-only client TempBlock pos=" + pos + " state=" + state);
    }

    public boolean toggleDebugView() {
        showServerLayers = !showServerLayers;
        repaintAll();
        return showServerLayers;
    }

    public boolean showsServerLayers() {
        return showServerLayers;
    }

    /**
     * A held/thrown EarthSmash is a client TempBlock model, not a block the
     * local interaction manager should begin mining. The arm swing is still
     * sent normally and remains the native ProjectKorra input.
     */
    public boolean suppressLocalBreaking(final ClientWorld world, final BlockPos pos) {
        if (!context.ready() || world == null || pos == null) return false;
        final com.projectkorra.projectkorra.platform.mc.block.Block block =
                FabricPredictionMC.block(world, pos);
        final List<TempBlock> layers = TempBlock.getAll(block);
        if (layers == null) return false;
        for (final TempBlock layer : layers) {
            if (layer != null && !layer.isReverted()
                    && layer.getAbility().orElse(null) instanceof EarthSmash) return true;
        }
        return false;
    }

    /** Suppresses a server crack overlay for the same locally rendered model. */
    public boolean suppressBreakAnimation(final ClientWorld world, final BlockPos pos) {
        if (suppressLocalBreaking(world, pos)) return true;
        final BlockKey key = world == null || pos == null
                ? null : new BlockKey(world, pos.toImmutable());
        final ServerLayer server = topAuthoritative(key);
        return server != null && "EarthSmash".equalsIgnoreCase(server.effectAbility)
                && hidesServerLayer(key);
    }

    /**
     * Observes an incoming vanilla state without ever cancelling its install.
     * The backing ClientWorld is authority; concealment is render-only.
     */
    public boolean acceptBlock(final ClientWorld world, final BlockPos pos,
                               final BlockState state) {
        if (!context.ready() || world == null || pos == null || state == null) return false;
        final BlockKey key = new BlockKey(world, pos.toImmutable());
        takeCompletedRestore(key, state);
        final boolean serverTempPhysical = serverLayers.physicalState(key)
                .filter(state::equals).isPresent();
        final ClientDirectBlockAuthority.DirectView directMask = serverTempPhysical
                ? null : directBlocks.maskForIncoming(world, pos, state);
        final BlockState directViewer = directMask == null ? null : directMask.viewerState();
        final BlockState trustedUnderlay = serverTempPhysical
                ? composedUnderlay(key, serverLayers.viewerState(key).orElse(state))
                : directViewer == null ? state : directViewer;
        if (preserveLocalAuthority(key, trustedUnderlay)) {
            directBlocks.takeConfirmed(world, pos, state);
            directBlocks.removeMutation(world, pos);
            refreshVisual(key);
            log("runtime rebased visual TempBlock underlay pos=" + pos
                    + " serverState=" + state + " viewerState="
                    + (directViewer == null ? state : directViewer));
            return false;
        }
        final ClientDirectBlockAuthority.ConfirmedWrite confirmed =
                directBlocks.takeConfirmed(world, pos, state);
        if (confirmed != null) {
            directBlocks.removeMutation(world, pos);
        } else if (directViewer == null) {
            directBlocks.confirmFromVanilla(world, pos, state);
        }
        directBlocks.removeMutation(world, pos);
        refreshVisual(key);
        return false;
    }

    /** Observes a vanilla chunk delta; vanilla still installs every entry. */
    public boolean acceptBatch(final ClientWorld world, final List<BlockPos> positions,
                               final List<BlockState> states) {
        if (!context.ready() || world == null || positions == null || states == null
                || positions.isEmpty() || positions.size() != states.size()) return false;
        for (int index = 0; index < positions.size(); index++) {
            acceptBlock(world, positions.get(index), states.get(index));
        }
        return false;
    }

    public void acceptChunk(final ClientWorld world, final int chunkX, final int chunkZ) {
        if (!context.ready() || world == null) return;
        final Set<BlockPos> preserved = new HashSet<>();
        final String worldName = FabricPredictionMC.world(world).getName();
        preserved.addAll(directBlocks.restoreChunk(world, chunkX, chunkZ,
                (pos, chunkState) -> serverLayers.physicalState(new BlockKey(world, pos))
                        .filter(chunkState::equals).isPresent()));
        final Set<BlockPos> localCoordinates = new HashSet<>();
        for (TempBlock layer : TempBlock.getActiveLayers()) {
            final com.projectkorra.projectkorra.platform.mc.block.Block block = layer.getBlock();
            if (block.getWorld() == null || !block.getWorld().getName().equals(worldName)
                    || block.getX() >> 4 != chunkX || block.getZ() >> 4 != chunkZ) continue;
            final BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ()).toImmutable();
            if (!localCoordinates.add(pos)) continue;
            final BlockKey key = new BlockKey(world, pos);
            preserved.add(pos);
            refreshVisual(key);
        }
        for (BlockKey key : List.copyOf(authoritativeByCoordinate.keySet())) {
            if (key.world != world || key.pos.getX() >> 4 != chunkX
                    || key.pos.getZ() >> 4 != chunkZ || preserved.contains(key.pos)
                    || topAuthoritative(key) == null) continue;
            refreshVisual(key);
            preserved.add(key.pos);
        }
        directBlocks.removeChunkMutationsExcept(world, chunkX, chunkZ, preserved);
    }

    private CompletedRestore takeCompletedRestore(final BlockKey key,
                                                   final BlockState receivedState) {
        final CompletedRestore completed = completedRestores.get(key);
        if (completed == null) return null;
        if (completed.expectedState.equals(receivedState)) {
            completedRestores.remove(key, completed);
            final BlockState liveState = completed.followLiveClientState
                    ? clientState(key.world, key.pos) : null;
            final BlockState retained = completedRestoreState(
                    completed.followLiveClientState, liveState, completed.state);
            return retained == completed.state ? completed
                    : new CompletedRestore(completed.expectedState, retained,
                    completed.followLiveClientState, completed.tick, completed.localLayerId);
        }
        // Several vanilla writes for the same coordinate can be delivered
        // between close metadata and Paper's physical restore (fluid levels
        // are a common example). Only the state named by the close operation
        // consumes this short-lived fence; expire() remains its hard bound.
        log("runtime retained completed TempBlock fence through intermediate update pos=" + key.pos
                + " expected=" + completed.expectedState + " received=" + receivedState);
        return null;
    }

    public static <T> T completedRestoreState(final boolean followLiveClientState,
                                               final T liveState, final T finalUnderlay) {
        return followLiveClientState && liveState != null ? liveState : finalUnderlay;
    }

    private void updateCompletedRestores(final long localLayerId, final BlockKey localKey,
                                         final BlockState finalState) {
        if (localLayerId <= 0L || localKey == null || finalState == null
                || completedRestores.isEmpty()) return;
        completedRestores.replaceAll((key, completed) ->
                completed.localLayerId == localLayerId && key.equals(localKey)
                        ? new CompletedRestore(completed.expectedState, finalState,
                        completed.followLiveClientState, completed.tick, completed.localLayerId)
                        : completed);
    }

    public BatchResult applyAuthoritativeBatch(final ClientWorld world,
                                               final PredictionPayloads.TempBlockBatch batch) {
        if (!context.ready() || world == null || batch == null) {
            return BatchResult.RESYNC_REQUIRED;
        }
        if (!batch.snapshot()) {
            if (batch.snapshotId() != 0L || batch.snapshotIndex() != 0
                    || batch.snapshotParts() != 1 || stagedSnapshot != null
                    || !advanceStream(batch.streamSequence(), false)) {
                return requireAuthoritativeResync("invalid or gapped incremental stream");
            }
            applyAuthoritativeOperations(world, batch, batch.operations());
            return BatchResult.APPLIED;
        }

        if (batch.snapshotId() <= 0L || batch.snapshotParts() <= 0
                || batch.snapshotIndex() < 0
                || batch.snapshotIndex() >= batch.snapshotParts()) {
            return requireAuthoritativeResync("invalid snapshot framing");
        }
        if (batch.snapshotIndex() == 0) {
            if (batch.snapshotId() <= lastCommittedSnapshotId
                    || !advanceStream(batch.streamSequence(), true)) {
                return requireAuthoritativeResync("stale snapshot start");
            }
            stagedSnapshot = new SnapshotAssembly(batch.snapshotId(), batch.snapshotParts());
        } else {
            if (stagedSnapshot == null
                    || stagedSnapshot.id != batch.snapshotId()
                    || stagedSnapshot.parts != batch.snapshotParts()
                    || stagedSnapshot.nextIndex != batch.snapshotIndex()
                    || !advanceStream(batch.streamSequence(), false)) {
                return requireAuthoritativeResync("snapshot fragment gap");
            }
        }
        if (stagedSnapshot.operations.size() + batch.operations().size()
                > MAX_STAGED_SNAPSHOT_OPERATIONS) {
            return requireAuthoritativeResync("snapshot operation limit exceeded");
        }
        stagedSnapshot.operations.addAll(batch.operations());
        stagedSnapshot.nextIndex++;
        if (stagedSnapshot.nextIndex < stagedSnapshot.parts) return BatchResult.STAGED;

        final SnapshotAssembly committed = stagedSnapshot;
        stagedSnapshot = null;
        applyAuthoritativeOperations(world, batch, committed.operations);
        lastCommittedSnapshotId = committed.id;
        pruneAbsentAuthoritativeLayers(world, committed.operations);
        log("runtime committed TempBlock snapshot id=" + committed.id
                + " parts=" + committed.parts + " ops=" + committed.operations.size());
        return BatchResult.APPLIED;
    }

    private void applyAuthoritativeOperations(final ClientWorld world,
                                              final PredictionPayloads.TempBlockBatch batch,
                                              final List<PredictionPayloads.TempBlockOp> operations) {
        log("runtime temp-block batch serverTick=" + batch.serverTick()
                + " ops=" + operations.size());
        final String worldName = world.getRegistryKey().getValue().toString();
        final ClientPlayerEntity localPlayer = MinecraftClient.getInstance().player;
        final UUID viewerId = localPlayer == null ? null : localPlayer.getUuid();
        for (PredictionPayloads.TempBlockOp operation : operations) {
            if (!matchesWorld(worldName, operation.world())) {
                recordAuthoritative("SKIP_WORLD snapshot=" + batch.snapshot()
                        + " operation=" + operation.operation() + " layer=" + operation.layerId()
                        + " operationWorld=" + operation.world() + " clientWorld=" + worldName);
                continue;
            }
            final BlockPos pos = new BlockPos(
                    operation.x(), operation.y(), operation.z()).toImmutable();
            final BlockKey key = new BlockKey(world, pos);
            final BlockState worldBefore = world.getBlockState(pos);
            final TempBlockSync.Operation commonOperation = switch (operation.operation()) {
                case CREATE -> TempBlockSync.Operation.CREATE;
                case UPDATE_EXPIRY -> TempBlockSync.Operation.UPDATE_EXPIRY;
                case REVERT -> TempBlockSync.Operation.REVERT;
                case DISCARD -> TempBlockSync.Operation.DISCARD;
            };
            final BlockState physicalState = decode(operation.material());
            final BlockState viewerState = decode(operation.viewerMaterial());
            final boolean hiddenBefore = hidesServerLayer(key);
            final Long pairedLocalLayer = pairedServerLayers.get(operation.layerId());
            final boolean locallyOwned = viewerId != null && viewerId.equals(operation.ownerId());
            final long causalSequence = locallyOwned
                    ? context.localActionSequence(operation.actionSequence()) : 0L;
            final boolean advanced = batch.snapshot()
                    && commonOperation == TempBlockSync.Operation.CREATE
                    ? serverLayers.applySnapshot(key, operation.actionSequence(),
                    operation.layerId(), operation.revision(), operation.ownerId(),
                    physicalState, viewerState)
                    : serverLayers.apply(key, commonOperation, operation.actionSequence(),
                    operation.layerId(), operation.revision(), operation.ownerId(),
                    physicalState, viewerState);
            if (!advanced) continue;

            if (commonOperation == TempBlockSync.Operation.REVERT
                    || commonOperation == TempBlockSync.Operation.DISCARD) {
                final ServerLayer server = authoritativeLayers.get(operation.layerId());
                // Only a proven semantic pair was ever concealed. Preserve its
                // live local view until vanilla installs Paper's physical close.
                // Owner metadata is emitted only for an authenticated exact
                // client and a supported predicted ability. Preserve the
                // concealment fence even when coordinate drift prevented the
                // optional semantic pair from being formed.
                final boolean hiddenClosingLayer = hiddenBefore;
                if (server != null && server.effect != null) {
                    authoritativeEffects.remove(server.effect, operation.layerId());
                }
                unpairServer(operation.layerId());
                removeAuthoritative(operation.layerId());
                BlockState completedRestore = null;
                long completedLocalLayer = 0L;
                boolean followLiveClientState = false;
                if (pairedLocalLayer != null) {
                    final LocalLayer local = localLayers.get(pairedLocalLayer);
                    final TempBlock localLayer = findActiveLayer(pairedLocalLayer);
                    if (local != null && operation.packetExpected()) {
                        BlockState restore = local.key.equals(key)
                                ? clientState(key.world, key.pos) : viewerState;
                        if (restore == null && local.key.equals(key)) {
                            restore = closedClientState(key);
                            if (restore == null) {
                                restore = local.closedState != null
                                        ? local.closedState : viewerState;
                            }
                        }
                        if (restore != null) {
                            completedRestore = restore;
                            completedLocalLayer = pairedLocalLayer;
                            followLiveClientState = local.key.equals(key) && localLayer != null;
                        }
                    }
                    if (local != null && localLayer != null) {
                        // Paper closing its physical counterpart cannot cut
                        // short the common-client lifecycle.
                        local.serverClosed = true;
                    } else {
                        detachLocalLayer(pairedLocalLayer);
                    }
                }
                if (operation.packetExpected() && hiddenClosingLayer
                        && completedRestore == null) {
                    final BlockState activeLocal = clientState(key.world, key.pos);
                    followLiveClientState = activeLocal != null;
                    completedRestore = followLiveClientState
                            ? viewerState
                            : hidesServerLayer(key) ? desiredState(key) : viewerState;
                }
                if (operation.packetExpected() && completedRestore != null) {
                    completedRestores.put(key, new CompletedRestore(
                            physicalState, completedRestore, followLiveClientState,
                            context.tick(), completedLocalLayer));
                }
                repaint(key, viewerState);
                recordAuthoritative("CLOSE snapshot=" + batch.snapshot()
                        + " operation=" + commonOperation + " layer=" + operation.layerId()
                        + " revision=" + operation.revision() + " owner=" + operation.ownerId()
                        + " localOwner=" + locallyOwned + " hidden=" + hiddenClosingLayer
                        + " pairedLocal=" + pairedLocalLayer
                        + " packetExpected=" + operation.packetExpected()
                        + " effect=" + operation.effectAbility() + " pos=" + pos
                        + " physical=" + physicalState + " viewer=" + viewerState
                        + " world=" + worldBefore + "->" + world.getBlockState(pos));
                continue;
            }

            final EffectKey effect = effectKey(causalSequence, operation.effectAbility(),
                    operation.effectStep(), operation.effectOrdinal());
            final ServerLayer previous = authoritativeLayers.get(operation.layerId());
            if (previous != null && !Objects.equals(previous.effect, effect)) {
                if (previous.effect != null) {
                    authoritativeEffects.remove(previous.effect, operation.layerId());
                }
                unpairServer(operation.layerId());
            }
            final ServerLayer server = new ServerLayer(causalSequence, key, effect,
                    operation.effectAbility(), operation.effectState(), operation.ownerId(),
                    physicalState);
            indexAuthoritative(operation.layerId(), server);
            if (effect != null && locallyOwned) {
                authoritativeEffects.put(effect, operation.layerId());
                tryMatchServer(operation.layerId(), server);
            }

            final boolean hiddenAfter = hidesServerLayer(key);
            if (hiddenAfter) {
                directBlocks.removeMutation(world, pos);
            } else if (!operation.packetExpected()) {
                final BlockState authoritativeUnderlay = serverLayers.viewerState(key)
                        .map(state -> composedUnderlay(key, state))
                        .orElseGet(() -> composedUnderlay(key, physicalState));
                if (preserveLocalAuthority(key, authoritativeUnderlay)) {
                    refreshVisual(key);
                }
            }
            // Metadata is enough to establish visual authority. Do this even
            // when a vanilla block packet is expected so packet loss or delay
            // cannot leave a DIRECT prediction covering Paper's TempBlock.
            refreshVisual(key);
            recordAuthoritative("OPEN snapshot=" + batch.snapshot()
                    + " operation=" + commonOperation + " layer=" + operation.layerId()
                    + " revision=" + operation.revision() + " owner=" + operation.ownerId()
                    + " localOwner=" + locallyOwned + " causal=" + causalSequence
                    + " hidden=" + hiddenAfter + " pairedLocal="
                    + pairedServerLayers.get(operation.layerId())
                    + " packetExpected=" + operation.packetExpected()
                    + " effect=" + operation.effectAbility() + " semantic=" + effect + " pos=" + pos
                    + " physical=" + physicalState + " viewer=" + viewerState
                    + " world=" + worldBefore + "->" + world.getBlockState(pos));
            log("runtime recorded server TempBlock operation=" + commonOperation
                    + " layer=" + operation.layerId() + " revision=" + operation.revision()
                    + " effect=" + effect + " pos=" + pos + " paired=" + hiddenAfter
                    + " wasPaired=" + hiddenBefore);
        }
    }

    private boolean advanceStream(final long sequence, final boolean snapshotCanRepairGap) {
        if (sequence <= 0L) return false;
        if (lastStreamSequence > 0L) {
            if (snapshotCanRepairGap) {
                if (sequence <= lastStreamSequence) return false;
            } else if (sequence != lastStreamSequence + 1L) {
                return false;
            }
        }
        lastStreamSequence = sequence;
        return true;
    }

    private void discardStagedSnapshot(final String reason) {
        if (stagedSnapshot != null) {
            log("runtime discarded TempBlock snapshot id=" + stagedSnapshot.id
                    + " next=" + stagedSnapshot.nextIndex + "/" + stagedSnapshot.parts
                    + " reason=" + reason);
        } else {
            log("runtime rejected TempBlock stream reason=" + reason);
        }
        stagedSnapshot = null;
    }

    /**
     * Fails open to vanilla authority while a replacement snapshot is owed.
     * Local prediction remains rendered, but no stale server-pair metadata can
     * keep a physical block concealed after a stream gap.
     */
    private BatchResult requireAuthoritativeResync(final String reason) {
        discardStagedSnapshot(reason);
        for (LocalLayer local : localLayers.values()) {
            if (local != null) local.serverLayerId = 0L;
        }
        serverLayers.clear();
        authoritativeLayers.clear();
        authoritativeByCoordinate.clear();
        authoritativeEffects.clear();
        pairedServerLayers.clear();
        pairedCoordinates.clear();
        completedRestores.clear();
        repaintAll();
        recordAuthoritative("STREAM_INVALIDATED reason=" + reason);
        return BatchResult.RESYNC_REQUIRED;
    }

    /** Commits snapshot membership only after every fragment has arrived. */
    private void pruneAbsentAuthoritativeLayers(
            final ClientWorld world,
            final List<PredictionPayloads.TempBlockOp> operations) {
        final String worldName = world.getRegistryKey().getValue().toString();
        final Set<Long> snapshotLayers = new HashSet<>();
        for (PredictionPayloads.TempBlockOp operation : operations) {
            if (!matchesWorld(worldName, operation.world())) continue;
            if (operation.operation() != PredictionPayloads.TempOperation.REVERT
                    && operation.operation() != PredictionPayloads.TempOperation.DISCARD) {
                snapshotLayers.add(operation.layerId());
            }
        }

        final Set<BlockKey> affected = new HashSet<>(
                serverLayers.pruneAbsentFromSnapshot(snapshotLayers));
        for (long layerId : List.copyOf(authoritativeLayers.keySet())) {
            if (snapshotLayers.contains(layerId)) continue;
            final ServerLayer stale = authoritativeLayers.get(layerId);
            if (stale != null && stale.effect != null) {
                authoritativeEffects.remove(stale.effect, layerId);
            }
            unpairServer(layerId);
            final ServerLayer removed = removeAuthoritative(layerId);
            if (removed != null && removed.key != null) affected.add(removed.key);
        }
        authoritativeEffects.values().removeIf(layerId -> !snapshotLayers.contains(layerId));
        for (BlockKey key : affected) refreshVisual(key);
        recordAuthoritative("SNAPSHOT_COMMIT id=" + lastCommittedSnapshotId
                + " retained=" + snapshotLayers.size() + " refreshed=" + affected.size());
    }

    /** Runs an authoritative ability removal with exact TempBlock cleanup. */
    public void removeAbility(final CoreAbility ability, final Runnable removal) {
        final Map<BlockKey, CapturedLifecycle> captured = captureAbility(ability);
        try {
            if (removal != null) removal.run();
        } finally {
            finalizeAbilityRemoval(ability, captured);
        }
    }

    public void afterLocalProgress(final ClientWorld world) {
        // Visual overlays do not need to repair ClientWorld after progress.
    }

    public void expire() {
        final long tick = context.tick();
        final List<BlockKey> expiredRestores = new ArrayList<>();
        completedRestores.entrySet().removeIf(entry -> {
            if (tick - entry.getValue().tick <= 2L) return false;
            expiredRestores.add(entry.getKey());
            return true;
        });
        for (BlockKey key : expiredRestores) refreshVisual(key);
        expireUnconfirmedLayers();
    }

    public List<String> report() {
        final List<String> report = new ArrayList<>();
        report.add("TempBlocks: localRecords=" + localLayers.size()
                + " localActive=" + TempBlock.getActiveLayers().size()
                + " serverLayers=" + authoritativeLayers.size()
                + " serverCoordinates=" + serverLayers.coordinateCount()
                + " renderOverrides="
                + visualOverlay.size(ClientBlockVisualOverlay.Layer.TEMP)
                + " closeOverlays=" + completedRestores.size()
                + " serverDebugVisible=" + showServerLayers);
        if (teardownHistory.isEmpty()) {
            report.add("Authoritative teardown: no ability teardown has captured TempBlock coordinates");
        } else {
            report.add("Recent authoritative TempBlock teardowns:");
            report.addAll(teardownHistory);
        }
        if (authoritativeHistory.isEmpty()) {
            report.add("Authoritative TempBlock wire history: no lifecycle operation advanced this runtime");
        } else {
            report.add("Authoritative TempBlock operations (oldest to newest):");
            report.addAll(authoritativeHistory);
        }
        int details = 0;
        final List<Map.Entry<Long, LocalLayer>> localDetails =
                new ArrayList<>(localLayers.entrySet());
        Collections.reverse(localDetails);
        for (Map.Entry<Long, LocalLayer> entry : localDetails) {
            final LocalLayer local = entry.getValue();
            if (local == null || local.owner == null
                    || !local.owner.getName().equalsIgnoreCase("WaterSpout")
                    && !local.owner.getName().equalsIgnoreCase("EarthSmash")) continue;
            report.add("local layer=" + entry.getKey() + " pos=" + local.key.pos
                    + " owner=" + local.owner.getName() + " effect=" + local.effect
                    + " active=" + (findActiveLayer(entry.getKey()) != null)
                    + " closed=" + local.closed + " serverClosed=" + local.serverClosed
                    + " serverLayer=" + local.serverLayerId
                    + " world=" + local.key.world.getBlockState(local.key.pos));
            if (++details >= 24) break;
        }
        return List.copyOf(report);
    }

    public void clear() {
        visualOverlay.clear(ClientBlockVisualOverlay.Layer.TEMP);
        serverLayers.clear();
        localLayers.clear();
        localLayersByCoordinate.clear();
        pendingUnderlays.clear();
        localEffects.clear();
        authoritativeLayers.clear();
        authoritativeByCoordinate.clear();
        authoritativeEffects.clear();
        pairedServerLayers.clear();
        pairedCoordinates.clear();
        completedRestores.clear();
        concealedLocalActions.clear();
        teardownHistory.clear();
        authoritativeHistory.clear();
        stagedSnapshot = null;
        lastStreamSequence = 0L;
        lastCommittedSnapshotId = 0L;
    }

    private Map<BlockKey, CapturedLifecycle> captureAbility(final CoreAbility ability) {
        if (ability == null) return Map.of();
        final Map<BlockKey, CapturedLifecycle> captured = new LinkedHashMap<>();
        for (LocalLayer local : localLayers.values()) {
            if (local == null || local.owner != ability || local.key == null) continue;
            final CapturedLifecycle lifecycle = captured.computeIfAbsent(
                    local.key, ignored -> new CapturedLifecycle());
            lifecycle.staleStates.addAll(local.createdStates);
            lifecycle.addStale(local.initialUnderlay);
            lifecycle.addStale(local.authoritativeUnderlay);
            lifecycle.addStale(local.closedState);
        }
        for (TempBlock layer : TempBlock.getActiveLayers()) {
            if (layer == null || layer.isReverted()
                    || layer.getAbility().orElse(null) != ability) continue;
            final BlockKey key = clientKey(layer.getBlock());
            if (key == null) continue;
            final BlockState underlay = decode(TempBlockSync.encode(
                    layer.getState().getBlockData()));
            final BlockState created = decode(TempBlockSync.encode(layer.getBlockData()));
            final CapturedLifecycle lifecycle = captured.computeIfAbsent(
                    key, ignored -> new CapturedLifecycle());
            lifecycle.addStale(created);
            lifecycle.addStale(underlay);
        }
        return captured;
    }

    private void finalizeAbilityRemoval(final CoreAbility ability,
                                        final Map<BlockKey, CapturedLifecycle> captured) {
        if (captured == null || captured.isEmpty()) return;
        int repainted = 0;
        int remainingLocal = 0;
        int hiddenServer = 0;
        final List<String> samples = new ArrayList<>();
        final ClientPlayerEntity player = MinecraftClient.getInstance().player;
        for (Map.Entry<BlockKey, CapturedLifecycle> entry : captured.entrySet()) {
            final BlockKey key = entry.getKey();
            if (key == null || key.world == null || key.pos == null) continue;
            final CapturedLifecycle lifecycle = entry.getValue();
            final boolean hidden = hidesServerLayer(key);
            BlockState local = clientState(key.world, key.pos);
            if (hidden && player != null) {
                local = serverLayers.overlayState(key, player.getUuid()).orElse(local);
            }
            final BlockState before = key.world.getBlockState(key.pos);
            final BlockState selected = tempVisualState(key);
            if (local != null) remainingLocal++;
            if (hidden) hiddenServer++;
            refreshVisual(key);
            if (selected != null && !selected.equals(before)) repainted++;
            directBlocks.removeMutation(key.world, key.pos);
            if (samples.size() < 6) {
                samples.add(key.pos + ":" + before + "->" + selected
                        + (hidden ? "(server-hidden)" : "")
                        + " stale=" + (lifecycle == null ? 0 : lifecycle.staleStates.size()));
            }
        }
        teardownHistory.add("ability=" + (ability == null ? "<null>" : ability.getName())
                + " captured=" + captured.size() + " repainted=" + repainted
                + " remainingLocal=" + remainingLocal
                + " hiddenServer=" + hiddenServer + " samples=" + samples);
        while (teardownHistory.size() > 12) teardownHistory.remove(0);
        log("runtime finalized authoritative TempBlock teardown "
                + teardownHistory.get(teardownHistory.size() - 1));
    }

    private ServerLayer topAuthoritative(final BlockKey key) {
        final Map.Entry<Long, ServerLayer> top = topAuthoritativeEntry(key);
        return top == null ? null : top.getValue();
    }

    private Map.Entry<Long, ServerLayer> topAuthoritativeEntry(final BlockKey key) {
        if (key == null) return null;
        final OptionalLong topLayerId = serverLayers.topLayerId(key);
        if (topLayerId.isEmpty()) return null;
        final long layerId = topLayerId.getAsLong();
        final ServerLayer server = authoritativeLayers.get(layerId);
        return server != null && key.equals(server.key)
                ? Map.entry(layerId, server) : null;
    }

    private boolean hidesServerLayer(final BlockKey key) {
        if (showServerLayers || key == null || key.world == null || key.pos == null) return false;
        final ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || !serverLayers.hidesServerWorld(key, player.getUuid())) return false;

        // Owner metadata alone is not a perpetual hiding grant. It is emitted
        // only for an authenticated exact client, but a lost close/snapshot or
        // a reused server action must still fail open. Coordinates deliberately
        // do not participate: a latency-shifted Paper copy is hidden only while
        // its mapped local action owns an active TempBlock, plus a short close
        // grace for packet ordering.
        final NavigableMap<Long, ServerLayer> atCoordinate =
                authoritativeByCoordinate.get(key);
        if (atCoordinate == null || atCoordinate.isEmpty()) return false;
        final UUID viewerId = player.getUuid();
        boolean foundOwnedLayer = false;
        for (ServerLayer server : atCoordinate.values()) {
            if (server == null || !viewerId.equals(server.ownerId)) continue;
            foundOwnedLayer = true;
            // Paper's viewerState excludes every layer owned by this viewer.
            // Therefore one valid lease cannot safely hide a second stale
            // owned layer in the same stack; the whole coordinate fails open.
            if (!hasLocalActionConcealment(server.actionSequence)) return false;
        }
        return foundOwnedLayer;
    }

    private boolean hasLocalActionConcealment(final long actionSequence) {
        return actionSequence > 0L && concealedLocalActions.contains(actionSequence);
    }

    private boolean computesLocalActionConcealment(final long actionSequence) {
        if (actionSequence <= 0L) return false;
        for (Map.Entry<Long, LocalLayer> entry : localLayers.entrySet()) {
            final LocalLayer local = entry.getValue();
            if (local == null || local.actionSequence != actionSequence) continue;
            if (!local.closed && findActiveLayer(entry.getKey()) != null) return true;
            if (local.closed && !closedPairGraceExpired(local)) return true;
        }
        return false;
    }

    /** Repaints drifted coordinates only when an action lease changes state. */
    private void reconcileActionConcealment(final long actionSequence) {
        if (actionSequence <= 0L) return;
        final boolean concealed = computesLocalActionConcealment(actionSequence);
        final boolean changed = concealed
                ? concealedLocalActions.add(actionSequence)
                : concealedLocalActions.remove(actionSequence);
        if (changed) refreshAuthoritativeForAction(actionSequence);
    }

    /** Re-evaluates every drifted Paper coordinate tied to one local action. */
    private void refreshAuthoritativeForAction(final long actionSequence) {
        if (actionSequence <= 0L || authoritativeLayers.isEmpty()) return;
        final Set<BlockKey> affected = new HashSet<>();
        for (ServerLayer server : authoritativeLayers.values()) {
            if (server != null && server.actionSequence == actionSequence
                    && server.key != null) affected.add(server.key);
        }
        for (BlockKey key : affected) refreshVisual(key);
    }

    private void indexAuthoritative(final long layerId, final ServerLayer server) {
        final ServerLayer previous = authoritativeLayers.put(layerId, server);
        if (previous != null && previous.key != null && !previous.key.equals(server.key)) {
            final NavigableMap<Long, ServerLayer> old =
                    authoritativeByCoordinate.get(previous.key);
            if (old != null) {
                old.remove(layerId);
                if (old.isEmpty()) authoritativeByCoordinate.remove(previous.key);
            }
        }
        authoritativeByCoordinate.computeIfAbsent(server.key,
                ignored -> new TreeMap<>()).put(layerId, server);
    }

    private ServerLayer removeAuthoritative(final long layerId) {
        final ServerLayer removed = authoritativeLayers.remove(layerId);
        if (removed == null || removed.key == null) return removed;
        final NavigableMap<Long, ServerLayer> atCoordinate =
                authoritativeByCoordinate.get(removed.key);
        if (atCoordinate != null) {
            atCoordinate.remove(layerId);
            if (atCoordinate.isEmpty()) authoritativeByCoordinate.remove(removed.key);
        }
        return removed;
    }

    private boolean hasSemanticPair(final BlockKey key) {
        if (key == null || key.world == null || key.pos == null) return false;
        final Set<Long> paired = pairedCoordinates.get(key);
        if (paired == null || paired.isEmpty()) return false;
        boolean invalidated = false;
        for (long serverLayer : List.copyOf(paired)) {
            final Long localLayer = pairedServerLayers.get(serverLayer);
            final LocalLayer local = localLayer == null ? null : localLayers.get(localLayer);
            final boolean invalid = local == null || !eligibleForPair(local)
                    || (!local.closed && findActiveLayer(localLayer) == null)
                    || !serverLayers.containsLayer(key, serverLayer);
            if (!invalid) continue;
            invalidated = true;
            paired.remove(serverLayer);
            pairedServerLayers.remove(serverLayer, localLayer);
            if (local != null && local.serverLayerId == serverLayer) {
                local.serverLayerId = 0L;
                if (!local.key.equals(key)) refreshVisual(local.key);
            }
        }
        if (invalidated) refreshVisual(key);
        if (paired.isEmpty()) {
            pairedCoordinates.remove(key);
            return false;
        }
        // Concealment is stack-top-specific. A valid pair below a newer
        // unpaired Paper layer must not hide that newer physical layer.
        final Map.Entry<Long, ServerLayer> top = topAuthoritativeEntry(key);
        return top != null && paired.contains(top.getKey());
    }

    private boolean hasActiveServerPair(final LocalLayer local) {
        if (local == null || local.serverLayerId == 0L) return false;
        final ServerLayer server = authoritativeLayers.get(local.serverLayerId);
        return server != null && serverLayers.containsLayer(server.key, local.serverLayerId);
    }

    private static EffectKey effectKey(final long actionSequence, final String ability,
                                       final long step, final int ordinal) {
        if (actionSequence <= 0L || ability == null || ability.isBlank() || ordinal <= 0) return null;
        return new EffectKey(actionSequence, ability.toLowerCase(java.util.Locale.ROOT), step, ordinal);
    }

    private void tryMatchLocal(final long localLayerId, final LocalLayer local) {
        if (local == null || local.effect == null || local.serverLayerId != 0L
                || !eligibleForPair(local)) return;
        final Long serverLayerId = authoritativeEffects.get(local.effect);
        if (serverLayerId == null) return;
        final ServerLayer server = authoritativeLayers.get(serverLayerId);
        if (server != null) reconcilePair(serverLayerId, server, localLayerId, local);
    }

    private void tryMatchServer(final long serverLayerId, final ServerLayer server) {
        if (server == null || server.effect == null
                || pairedServerLayers.containsKey(serverLayerId)) return;
        final Long localLayerId = localEffects.get(server.effect);
        if (localLayerId == null) return;
        final LocalLayer local = localLayers.get(localLayerId);
        if (eligibleForPair(local)) reconcilePair(serverLayerId, server, localLayerId, local);
    }

    private void reconcilePair(final long serverLayerId, final ServerLayer server,
                               final long localLayerId, final LocalLayer local) {
        if (server == null || !eligibleForPair(local)
                || !Objects.equals(server.effect, local.effect)) return;
        if (local.serverLayerId != 0L && local.serverLayerId != serverLayerId) {
            unpairServer(local.serverLayerId);
        }
        final Long oldLocal = pairedServerLayers.put(serverLayerId, localLayerId);
        if (oldLocal != null && oldLocal != localLayerId) {
            final LocalLayer old = localLayers.get(oldLocal);
            if (old != null) old.serverLayerId = 0L;
        }
        local.serverLayerId = serverLayerId;
        pairedCoordinates.computeIfAbsent(server.key, ignored -> new HashSet<>()).add(serverLayerId);
        // Metadata can arrive before local progress. Once the exact pair is
        // established, rebase an equal-coordinate local layer to Paper's true
        // viewer underlay and immediately remove Paper's duplicate visual.
        if (server.key.equals(local.key)) {
            serverLayers.viewerState(server.key).ifPresent(viewer ->
                    rebaseUnderlay(local.key, composedUnderlay(local.key, viewer)));
        }
        repaint(server.key, serverLayers.viewerState(server.key).orElse(server.physicalState));
        log("runtime paired semantic TempBlock effect=" + server.effect
                + " serverLayer=" + serverLayerId + " localLayer=" + localLayerId
                + " clientPos=" + local.key.pos + " serverPos=" + server.key.pos
                + " shifted=" + !server.key.equals(local.key));
    }

    private LocalLayer detachLocalLayer(final long localLayerId) {
        final LocalLayer local = localLayers.remove(localLayerId);
        if (local == null) return null;
        final Set<Long> atCoordinate = localLayersByCoordinate.get(local.key);
        if (atCoordinate != null) {
            atCoordinate.remove(localLayerId);
            if (atCoordinate.isEmpty()) localLayersByCoordinate.remove(local.key);
        }
        if (local.effect != null) localEffects.remove(local.effect, localLayerId);
        if (local.serverLayerId != 0L) {
            pairedServerLayers.remove(local.serverLayerId, localLayerId);
            final ServerLayer server = authoritativeLayers.get(local.serverLayerId);
            final BlockKey serverKey = server == null ? local.key : server.key;
            final Set<Long> paired = pairedCoordinates.get(serverKey);
            if (paired != null) {
                paired.remove(local.serverLayerId);
                if (paired.isEmpty()) pairedCoordinates.remove(serverKey);
            }
        }
        refreshVisual(local.key);
        reconcileActionConcealment(local.actionSequence);
        return local;
    }

    private void unpairServer(final long serverLayerId) {
        final Long localLayerId = pairedServerLayers.remove(serverLayerId);
        final ServerLayer server = authoritativeLayers.get(serverLayerId);
        if (server != null) {
            final Set<Long> atCoordinate = pairedCoordinates.get(server.key);
            if (atCoordinate != null) {
                atCoordinate.remove(serverLayerId);
                if (atCoordinate.isEmpty()) pairedCoordinates.remove(server.key);
            }
        }
        if (localLayerId != null) {
            final LocalLayer local = localLayers.get(localLayerId);
            if (local != null && local.serverLayerId == serverLayerId) {
                local.serverLayerId = 0L;
                refreshVisual(local.key);
            }
        }
        if (server != null) refreshVisual(server.key);
    }

    private void repaint(final BlockKey key, final BlockState fallback) {
        refreshVisual(key);
    }

    private void expireUnconfirmedLayers() {
        List<Long> expired = null;
        Set<Long> releasedConcealment = null;
        for (Map.Entry<Long, LocalLayer> entry : localLayers.entrySet()) {
            final LocalLayer local = entry.getValue();
            if (closedPairGraceExpired(local) && !local.concealmentGraceReleased) {
                local.concealmentGraceReleased = true;
                if (releasedConcealment == null) releasedConcealment = new HashSet<>();
                releasedConcealment.add(local.actionSequence);
            }
            if (local.serverLayerId != 0L && closedPairGraceExpired(local)) {
                final long serverLayerId = local.serverLayerId;
                unpairServer(serverLayerId);
                log("runtime released closed TempBlock pair after grace layer="
                        + entry.getKey() + " serverLayer=" + serverLayerId
                        + " effect=" + local.effect);
            }
            if (!local.closed && findActiveLayer(entry.getKey()) == null) {
                if (expired == null) expired = new ArrayList<>();
                expired.add(entry.getKey());
                continue;
            }
            final long confirmationStart = local.closed ? local.closedTick : local.createdTick;
            if (local.serverLayerId != 0L || local.serverClosed || !local.closed
                    || context.tick() - confirmationStart <= ACTION_RETENTION_TICKS) continue;
            if (expired == null) expired = new ArrayList<>();
            expired.add(entry.getKey());
        }
        if (expired != null) {
            for (long layerId : expired) {
                final LocalLayer detached = detachLocalLayer(layerId);
                if (detached != null) refreshVisual(detached.key);
                if (detached != null && detached.closed) {
                    log("runtime expired unconfirmed TempBlock lifecycle layer=" + layerId
                            + " closed=true effect=" + detached.effect);
                }
            }
        }
        if (releasedConcealment != null) {
            for (long actionSequence : releasedConcealment) {
                reconcileActionConcealment(actionSequence);
            }
        }
    }

    private boolean eligibleForPair(final LocalLayer local) {
        return local != null && !local.serverClosed && !closedPairGraceExpired(local);
    }

    private boolean closedPairGraceExpired(final LocalLayer local) {
        return local != null && local.closed
                && context.tick() - local.closedTick > closeGraceTicks(local.actionSequence);
    }

    private int closeGraceTicks(final long actionSequence) {
        return Math.min(ACTION_RETENTION_TICKS, Math.max(CLOSED_PAIR_GRACE_TICKS,
                context.confirmationTicks(actionSequence)));
    }

    private boolean preserveLocalAuthority(final BlockKey key,
                                           final BlockState authoritativeState) {
        if (key == null || key.world == null || key.pos == null) return false;
        if (clientState(key.world, key.pos) == null) return false;
        rebaseUnderlay(key, authoritativeState);
        directBlocks.removeMutation(key.world, key.pos);
        return true;
    }

    private void rebaseUnderlay(final BlockKey key, final BlockState authoritativeState) {
        if (key == null || authoritativeState == null) return;
        final Set<Long> localAtCoordinate = localLayersByCoordinate.get(key);
        if (localAtCoordinate != null) {
            for (long layerId : List.copyOf(localAtCoordinate)) {
                final LocalLayer local = localLayers.get(layerId);
                if (local == null) continue;
                local.authoritativeUnderlay = authoritativeState;
                if (local.closed) {
                    local.closedState = authoritativeState;
                    updateCompletedRestores(layerId, key, authoritativeState);
                }
            }
        }
        final com.projectkorra.projectkorra.platform.mc.block.Block block =
                FabricPredictionMC.block(key.world, key.pos);
        final TempBlock layer = TempBlock.get(block);
        if (layer == null) return;
        final com.projectkorra.projectkorra.platform.mc.block.BlockState snapshot =
                FabricPredictionMC.blockStateSnapshot(key.world, key.pos, authoritativeState);
        if (snapshot == null) return;
        layer.setState(snapshot);
    }

    private LocalLayer newestClosedLocal(final BlockKey key) {
        long newestRevision = Long.MIN_VALUE;
        long newestTick = Long.MIN_VALUE;
        long newestLayer = Long.MIN_VALUE;
        LocalLayer newest = null;
        final Set<Long> atCoordinate = localLayersByCoordinate.get(key);
        if (atCoordinate == null) return null;
        for (long layerId : atCoordinate) {
            final LocalLayer local = localLayers.get(layerId);
            if (local == null || !local.closed || !key.equals(local.key)) continue;
            if (local.closedRevision < newestRevision
                    || local.closedRevision == newestRevision && local.closedTick < newestTick
                    || local.closedRevision == newestRevision && local.closedTick == newestTick
                    && layerId < newestLayer) continue;
            newestRevision = local.closedRevision;
            newestTick = local.closedTick;
            newestLayer = layerId;
            newest = local;
        }
        return newest;
    }

    private BlockState desiredState(final BlockKey key) {
        final BlockState temp = tempVisualState(key);
        if (temp != null) return temp;
        if (key == null || key.world == null || key.pos == null) return null;
        final BlockState direct = directBlocks.viewerState(key.world, key.pos);
        return direct == null ? key.world.getBlockState(key.pos) : direct;
    }

    private BlockState composedUnderlay(final BlockKey key,
                                         final BlockState authoritativeState) {
        if (key == null) return authoritativeState;
        final BlockState direct = directBlocks.viewerState(key.world, key.pos);
        return direct == null ? authoritativeState : direct;
    }

    /** Returns the logical TEMP-over-DIRECT state seen by common prediction. */
    private BlockState tempVisualState(final BlockKey key) {
        final TempVisual visual = tempVisual(key);
        if (visual.provenance == TempVisualProvenance.SERVER_UNDERLAY
                || visual.provenance == TempVisualProvenance.LOCAL_HANDOFF) {
            final BlockState direct = directBlocks.viewerState(key.world, key.pos);
            if (direct != null) return direct;
        }
        return visual.state;
    }

    /**
     * Selects both the composed TEMP state and its provenance. The foreground
     * renderer may draw only states produced by this client's common ability
     * simulation; Paper physical/debug/stack-overlay states must remain on the
     * ordinary terrain path even when their {@link BlockState} happens to equal
     * a local prediction.
     */
    private TempVisual tempVisual(final BlockKey key) {
        if (key == null || key.world == null || key.pos == null) return TempVisual.NONE;
        final Optional<BlockState> physical = serverLayers.physicalState(key);
        if (showServerLayers) return physical.map(TempVisual::server).orElse(TempVisual.NONE);
        final boolean hiddenServerLayer = hidesServerLayer(key);
        // Known Paper layers remain authoritative when the authenticated owner
        // has no bounded local-action lease. Keeping that state in TEMP also
        // places it above DIRECT without ever modifying ClientWorld.
        if (!hiddenServerLayer && physical.isPresent()) return TempVisual.server(physical.get());
        final ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (hiddenServerLayer && player != null) {
            final Optional<BlockState> overlay = serverLayers.overlayState(key, player.getUuid());
            if (overlay.isPresent()) return TempVisual.server(overlay.get());
        }
        final BlockState local = clientState(key.world, key.pos);
        if (local != null) return TempVisual.active(local);
        final CompletedRestore completed = completedRestores.get(key);
        if (completed != null) {
            return completed.localLayerId > 0L
                    ? TempVisual.handoff(completed.state)
                    : TempVisual.underlay(completed.state);
        }
        if (hiddenServerLayer) {
            final BlockState closed = closedClientState(key);
            if (closed != null) return TempVisual.handoff(closed);
            final Optional<BlockState> viewer = serverLayers.viewerState(key);
            if (viewer.isPresent()) return TempVisual.underlay(viewer.get());
        }
        return TempVisual.NONE;
    }

    private void refreshVisual(final BlockKey key) {
        if (key == null || key.world == null || key.pos == null) return;
        final TempVisual visual = tempVisual(key);
        if (visual.state == null) {
            visualOverlay.remove(ClientBlockVisualOverlay.Layer.TEMP, key.world, key.pos);
        } else if (visual.provenance == TempVisualProvenance.ACTIVE_LOCAL) {
            visualOverlay.setImmediateTemp(key.world, key.pos, visual.state);
        } else if (visual.provenance == TempVisualProvenance.LOCAL_HANDOFF) {
            visualOverlay.beginTempHandoff(key.world, key.pos, visual.state);
        } else if (visual.provenance == TempVisualProvenance.SERVER_UNDERLAY) {
            visualOverlay.setTempUnderlay(key.world, key.pos, visual.state);
        } else {
            visualOverlay.set(ClientBlockVisualOverlay.Layer.TEMP, key.world, key.pos, visual.state);
        }
    }

    private BlockState closedClientState(final BlockKey key) {
        final LocalLayer local = newestClosedLocal(key);
        return local == null ? null : local.closedState;
    }

    private BlockState clientState(final ClientWorld world, final BlockPos pos) {
        if (world == null || pos == null) return null;
        final TempBlock layer = TempBlock.get(FabricPredictionMC.block(world, pos));
        return layer == null ? null : decode(TempBlockSync.encode(layer.getBlockData()));
    }

    private void repaintAll() {
        visualOverlay.clear(ClientBlockVisualOverlay.Layer.TEMP);
        final Set<BlockKey> coordinates = new HashSet<>(authoritativeByCoordinate.keySet());
        for (LocalLayer local : localLayers.values()) {
            if (local != null && local.key != null) coordinates.add(local.key);
        }
        coordinates.addAll(completedRestores.keySet());
        for (BlockKey key : coordinates) {
            if (key.world == null || key.pos == null) continue;
            refreshVisual(key);
        }
        log("runtime server TempBlock debug=" + showServerLayers
                + " repainted=" + coordinates.size());
    }

    private void recordAuthoritative(final String event) {
        if (event == null || event.isBlank()) return;
        authoritativeHistory.add("tick=" + context.tick() + " " + event);
        while (authoritativeHistory.size() > HISTORY_LIMIT) authoritativeHistory.remove(0);
    }

    private BlockKey clientKey(
            final com.projectkorra.projectkorra.platform.mc.block.Block block) {
        final ClientWorld world = MinecraftClient.getInstance().world;
        if (block == null || block.getWorld() == null || world == null
                || !matchesWorld(world.getRegistryKey().getValue().toString(),
                block.getWorld().getName())) return null;
        return new BlockKey(world,
                new BlockPos(block.getX(), block.getY(), block.getZ()).toImmutable());
    }

    private BlockState decode(final String material) {
        return blockStateDecoder.apply(material);
    }

    private static TempBlock findActiveLayer(final long layerId) {
        return TempBlock.getActiveLayer(layerId);
    }

    private static boolean matchesWorld(final String clientWorld, final String serverWorld) {
        if (serverWorld == null || serverWorld.isBlank()) return false;
        if (clientWorld.equals(serverWorld)) return true;
        return serverWorld.indexOf(':') < 0
                && ("minecraft:overworld".equals(clientWorld) || "overworld".equals(clientWorld));
    }

    private void log(final String message) {
        debug.accept(message);
    }

    private record BlockKey(ClientWorld world, BlockPos pos) { }
    private record EffectKey(long actionSequence, String ability, long step, int ordinal) { }
    private enum TempVisualProvenance {
        SERVER,
        SERVER_UNDERLAY,
        ACTIVE_LOCAL,
        LOCAL_HANDOFF
    }
    private record TempVisual(BlockState state, TempVisualProvenance provenance) {
        private static final TempVisual NONE = new TempVisual(null, TempVisualProvenance.SERVER);

        private static TempVisual server(final BlockState state) {
            return state == null ? NONE : new TempVisual(state, TempVisualProvenance.SERVER);
        }

        private static TempVisual underlay(final BlockState state) {
            return state == null ? NONE
                    : new TempVisual(state, TempVisualProvenance.SERVER_UNDERLAY);
        }

        private static TempVisual active(final BlockState state) {
            return state == null ? NONE : new TempVisual(state, TempVisualProvenance.ACTIVE_LOCAL);
        }

        private static TempVisual handoff(final BlockState state) {
            return state == null ? NONE : new TempVisual(state, TempVisualProvenance.LOCAL_HANDOFF);
        }
    }
    private static final class CapturedLifecycle {
        private final Set<BlockState> staleStates = new HashSet<>();

        private void addStale(final BlockState state) {
            if (state != null) staleStates.add(state);
        }
    }
    private static final class LocalLayer {
        private final long actionSequence;
        private final BlockKey key;
        private final EffectKey effect;
        private final long createdTick;
        private final Set<BlockState> createdStates = new HashSet<>();
        private final BlockState initialUnderlay;
        private final CoreAbility owner;
        private long serverLayerId;
        private boolean closed;
        private boolean serverClosed;
        private boolean concealmentGraceReleased;
        private long closedTick;
        private long closedRevision;
        private BlockState closedState;
        private BlockState authoritativeUnderlay;

        private LocalLayer(final long actionSequence, final BlockKey key,
                           final EffectKey effect, final long createdTick,
                           final BlockState createdState, final BlockState initialUnderlay,
                           final CoreAbility owner) {
            this.actionSequence = actionSequence;
            this.key = key;
            this.effect = effect;
            this.createdTick = createdTick;
            if (createdState != null) createdStates.add(createdState);
            this.initialUnderlay = initialUnderlay;
            this.owner = owner;
        }
    }
    private record ServerLayer(long actionSequence, BlockKey key, EffectKey effect,
                               String effectAbility, String effectState, UUID ownerId,
                               BlockState physicalState) { }
    private record CompletedRestore(BlockState expectedState, BlockState state,
                                     boolean followLiveClientState, long tick,
                                     long localLayerId) { }
    private static final class SnapshotAssembly {
        private final long id;
        private final int parts;
        private final List<PredictionPayloads.TempBlockOp> operations = new ArrayList<>();
        private int nextIndex;

        private SnapshotAssembly(final long id, final int parts) {
            this.id = id;
            this.parts = parts;
        }
    }
}
