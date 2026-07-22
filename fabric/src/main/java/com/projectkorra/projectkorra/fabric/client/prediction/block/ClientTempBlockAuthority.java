package com.projectkorra.projectkorra.fabric.client.prediction.block;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.earthbending.EarthSmash;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads;
import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.fabric.FabricPredictionMC;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.block.ClientTempBlockLedger;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;
import com.projectkorra.projectkorra.prediction.block.TempBlockTeardownFence;
import com.projectkorra.projectkorra.prediction.block.TempBlockTeardownPolicy;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Owns local TempBlock prediction, Paper lifecycle pairing, and the composed
 * client block view.
 *
 * <p>Pairing uses action + ability + semantic step/ordinal. Coordinates are a
 * rendered consequence and may legitimately differ between the two runtimes;
 * they are never used as lifecycle identity.</p>
 */
public final class ClientTempBlockAuthority implements TempBlockSync.Listener {
    private static final int ACTION_RETENTION_TICKS = 160;
    private static final int HISTORY_LIMIT = 24;

    /** Action identity supplied by the runtime without exposing its model. */
    public interface Context {
        boolean ready();
        long tick();
        long currentAction();
        long actionForAbility(CoreAbility ability);
        String inputAbility(long actionSequence);
        int nextTempBlockOrdinal(long actionSequence);
        long localActionSequence(long paperSequence);
    }

    private final Context context;
    private final ClientDirectBlockAuthority directBlocks;
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
    private final TempBlockTeardownFence<BlockKey, BlockState> teardownFences =
            new TempBlockTeardownFence<>();
    private final List<String> teardownHistory = new ArrayList<>();
    private final List<String> authoritativeHistory = new ArrayList<>();
    private boolean showServerLayers = Boolean.parseBoolean(
            System.getProperty("projectkorra.prediction.debug.server-temp-blocks", "false"));

    public ClientTempBlockAuthority(final Context context,
                                    final ClientDirectBlockAuthority directBlocks,
                                    final Function<String, BlockState> blockStateDecoder,
                                    final Consumer<String> debug) {
        this.context = context;
        this.directBlocks = directBlocks;
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
                    detachLocalLayer(change.layerId());
                    return;
                }
                // Retain a tombstone even when CREATE metadata has not yet
                // arrived, so a short-lived local layer cannot reconsolidate.
                local.closed = true;
                local.closedTick = context.tick();
                local.closedRevision = change.revision();
                local.closedState = decode(TempBlockSync.encode(change.data()));
                updateCompletedRestores(change.layerId(), local.key, local.closedState);
                log("runtime retained predicted TempBlock close layer=" + change.layerId()
                        + " effect=" + local.effect + " pos=" + local.key.pos);
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
    }

    @Override
    public void beforeWorldChange(final TempBlockSync.Change change) {
        if (change == null || change.block() == null) return;
        if (change.ability() != null
                && !change.ability().tracksPredictedTempBlocks()) return;
        final BlockKey key = clientKey(change.block());
        if (key == null) return;
        if (change.operation() == TempBlockSync.Operation.CREATE) {
            pendingUnderlays.putIfAbsent(change.layerId(), key.world.getBlockState(key.pos));
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
        local.closedRevision = change.revision();
        final BlockState capturedUnderlay = change.underlayData() == null
                ? null : decode(TempBlockSync.encode(change.underlayData()));
        local.closedState = local.authoritativeUnderlay != null
                ? local.authoritativeUnderlay
                : capturedUnderlay != null ? capturedUnderlay : local.initialUnderlay;
        updateCompletedRestores(change.layerId(), local.key, local.closedState);
        if (local.closedState != null) {
            local.key.world.setBlockState(local.key.pos, local.closedState, 19);
        }
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
        BlockState visibleState = state;
        if (showServerLayers) {
            visibleState = serverLayers.physicalState(key).orElse(visibleState);
        }
        final ClientPlayerEntity localPlayer = MinecraftClient.getInstance().player;
        if (!showServerLayers && localPlayer != null && hidesServerLayer(key)) {
            visibleState = serverLayers.overlayState(key, localPlayer.getUuid()).orElse(visibleState);
        }
        world.setBlockState(pos, visibleState, 19);
        log("runtime applied client TempBlock directly pos=" + pos + " state=" + visibleState);
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

    /** @return true when vanilla must not install this authoritative state. */
    public boolean acceptBlock(final ClientWorld world, final BlockPos pos,
                               final BlockState state) {
        if (!context.ready() || world == null || pos == null || state == null) return false;
        final BlockKey key = new BlockKey(world, pos.toImmutable());
        final Optional<BlockState> teardownRestore = teardownFences.maskIncoming(key, state);
        if (teardownRestore.isPresent() && !showServerLayers) {
            directBlocks.takeConfirmed(world, pos, state);
            final BlockState retained = composeTeardownView(key, teardownRestore.get());
            if (retained != null && !retained.equals(world.getBlockState(pos))) {
                world.setBlockState(pos, retained, 19);
            }
            directBlocks.removeMutation(world, pos);
            log("runtime rejected late completed TempBlock state pos=" + pos
                    + " stale=" + state + " retained=" + retained);
            return true;
        }
        final CompletedRestore completed = takeCompletedRestore(key, state);
        if (completed != null) {
            directBlocks.takeConfirmed(world, pos, state);
            final ClientDirectBlockAuthority.DirectView direct =
                    directBlocks.maskForIncoming(world, pos, state);
            final BlockState retained = direct == null
                    ? completed.state : direct.viewerState();
            world.setBlockState(pos, retained, 19);
            directBlocks.removeMutation(world, pos);
            log("runtime hid completed physical TempBlock lifecycle pos=" + pos
                    + " state=" + retained);
            return true;
        }
        if (hidesServerLayer(key)) {
            directBlocks.takeConfirmed(world, pos, state);
            log("runtime hid physical server TempBlock update pos=" + pos + " state=" + state);
            return true;
        }
        final boolean serverTempPhysical = serverLayers.physicalState(key)
                .filter(state::equals).isPresent();
        final ClientDirectBlockAuthority.DirectView directMask = serverTempPhysical
                ? null : directBlocks.maskForIncoming(world, pos, state);
        final BlockState directViewer = directMask == null ? null : directMask.viewerState();
        if (preserveLocalAuthority(key, directViewer == null ? state : directViewer)) {
            directBlocks.takeConfirmed(world, pos, state);
            directBlocks.removeMutation(world, pos);
            log("runtime rebased hidden client TempBlock underlay pos=" + pos
                    + " serverState=" + state + " viewerState="
                    + (directViewer == null ? state : directViewer));
            return true;
        }
        final ClientDirectBlockAuthority.ConfirmedWrite confirmed =
                directBlocks.takeConfirmed(world, pos, state);
        if (confirmed != null) {
            final BlockState restore = directViewer == null
                    ? directBlocks.desiredState(confirmed) : directViewer;
            directBlocks.removeMutation(world, pos);
            if (restore != null && !restore.equals(state)) {
                world.setBlockState(pos, restore, 19);
                log("runtime hid exactly-confirmed earth write pos=" + pos
                        + " serverState=" + state + " desired=" + restore);
                return true;
            }
            return false;
        }
        if (directViewer != null) {
            directBlocks.removeMutation(world, pos);
            if (!directViewer.equals(state)) {
                world.setBlockState(pos, directViewer, 19);
                return true;
            }
            return false;
        }
        directBlocks.confirmFromVanilla(world, pos, state);
        directBlocks.removeMutation(world, pos);
        return false;
    }

    /** @return true when the mixed-ownership vanilla batch was applied here. */
    public boolean acceptBatch(final ClientWorld world, final List<BlockPos> positions,
                               final List<BlockState> states) {
        if (!context.ready() || world == null || positions == null || states == null
                || positions.isEmpty() || positions.size() != states.size()) return false;
        final boolean[] masked = new boolean[positions.size()];
        final BlockState[] retainedStates = new BlockState[positions.size()];
        int maskedEntries = 0;
        for (int index = 0; index < positions.size(); index++) {
            final BlockPos pos = positions.get(index).toImmutable();
            final BlockKey key = new BlockKey(world, pos);
            final BlockState incoming = states.get(index);
            final Optional<BlockState> teardownRestore = teardownFences.maskIncoming(key, incoming);
            if (teardownRestore.isPresent() && !showServerLayers) {
                masked[index] = true;
                retainedStates[index] = composeTeardownView(key, teardownRestore.get());
                maskedEntries++;
                directBlocks.takeConfirmed(world, pos, incoming);
                directBlocks.removeMutation(world, pos);
                continue;
            }
            final CompletedRestore completed = takeCompletedRestore(key, incoming);
            if (completed != null) {
                masked[index] = true;
                final ClientDirectBlockAuthority.DirectView direct =
                        directBlocks.maskForIncoming(world, pos, incoming);
                retainedStates[index] = direct == null ? completed.state : direct.viewerState();
                maskedEntries++;
                directBlocks.takeConfirmed(world, pos, incoming);
                continue;
            }
            if (hidesServerLayer(key)) {
                masked[index] = true;
                retainedStates[index] = desiredState(key);
                maskedEntries++;
                directBlocks.takeConfirmed(world, pos, incoming);
                continue;
            }
            final boolean serverTempPhysical = serverLayers.physicalState(key)
                    .filter(incoming::equals).isPresent();
            final ClientDirectBlockAuthority.DirectView directMask = serverTempPhysical
                    ? null : directBlocks.maskForIncoming(world, pos, incoming);
            final BlockState directViewer = directMask == null ? null : directMask.viewerState();
            if (preserveLocalAuthority(key,
                    directViewer == null ? incoming : directViewer)) {
                masked[index] = true;
                retainedStates[index] = desiredState(key);
                maskedEntries++;
                directBlocks.takeConfirmed(world, pos, incoming);
                continue;
            }
            final ClientDirectBlockAuthority.ConfirmedWrite confirmed =
                    directBlocks.takeConfirmed(world, pos, incoming);
            if (confirmed != null) {
                final BlockState retained = directViewer == null
                        ? directBlocks.desiredState(confirmed) : directViewer;
                if (retained != null && !retained.equals(incoming)) {
                    masked[index] = true;
                    retainedStates[index] = retained;
                    maskedEntries++;
                } else {
                    directBlocks.removeMutation(world, pos);
                }
            } else if (directViewer != null) {
                directBlocks.removeMutation(world, pos);
                if (!directViewer.equals(incoming)) {
                    masked[index] = true;
                    retainedStates[index] = directViewer;
                    maskedEntries++;
                }
            } else {
                directBlocks.confirmFromVanilla(world, pos, incoming);
                directBlocks.removeMutation(world, pos);
            }
        }
        if (maskedEntries == 0) return false;
        // Chunk deltas are one packet with per-entry ownership. Install every
        // unrelated entry here and leave owned entries on their composed view.
        for (int index = 0; index < positions.size(); index++) {
            final BlockPos pos = positions.get(index).toImmutable();
            final BlockState selected = masked[index] ? retainedStates[index] : states.get(index);
            if (selected != null && !world.getBlockState(pos).equals(selected)) {
                world.setBlockState(pos, selected, 19);
            }
        }
        log("runtime masked owned chunk-delta entries=" + maskedEntries
                + " authoritativeEntries=" + (positions.size() - maskedEntries));
        return true;
    }

    public void acceptChunk(final ClientWorld world, final int chunkX, final int chunkZ) {
        if (!context.ready() || world == null) return;
        final Set<BlockPos> preserved = new HashSet<>();
        final String worldName = FabricPredictionMC.world(world).getName();
        final Map<BlockKey, BlockState> teardownChunkStates = new LinkedHashMap<>();
        for (BlockKey key : teardownFences.keys()) {
            if (key == null || key.world != world || key.pos == null
                    || key.pos.getX() >> 4 != chunkX || key.pos.getZ() >> 4 != chunkZ) continue;
            teardownChunkStates.put(key, world.getBlockState(key.pos));
        }
        preserved.addAll(directBlocks.restoreChunk(world, chunkX, chunkZ,
                (pos, chunkState) -> serverLayers.physicalState(new BlockKey(world, pos))
                        .filter(chunkState::equals).isPresent()));
        for (Map.Entry<BlockKey, BlockState> entry : teardownChunkStates.entrySet()) {
            final Optional<BlockState> retained =
                    teardownFences.maskIncoming(entry.getKey(), entry.getValue());
            if (retained.isEmpty() || showServerLayers) continue;
            final BlockState desired = composeTeardownView(entry.getKey(), retained.get());
            if (desired != null
                    && !desired.equals(entry.getKey().world.getBlockState(entry.getKey().pos))) {
                entry.getKey().world.setBlockState(entry.getKey().pos, desired, 19);
            }
            preserved.add(entry.getKey().pos);
        }
        final Set<BlockPos> localCoordinates = new HashSet<>();
        for (TempBlock layer : TempBlock.getActiveLayers()) {
            final com.projectkorra.projectkorra.platform.mc.block.Block block = layer.getBlock();
            if (block.getWorld() == null || !block.getWorld().getName().equals(worldName)
                    || block.getX() >> 4 != chunkX || block.getZ() >> 4 != chunkZ) continue;
            final BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ()).toImmutable();
            if (!localCoordinates.add(pos)) continue;
            final BlockKey key = new BlockKey(world, pos);
            if (!hidesServerLayer(key)) rebaseUnderlay(key, world.getBlockState(pos));
            preserved.add(pos);
            final BlockState desired = desiredState(key);
            if (desired != null) world.setBlockState(pos, desired, 19);
        }
        for (BlockKey key : List.copyOf(pairedCoordinates.keySet())) {
            if (key.world != world || key.pos.getX() >> 4 != chunkX
                    || key.pos.getZ() >> 4 != chunkZ || preserved.contains(key.pos)
                    || !hidesServerLayer(key)) continue;
            final BlockState desired = desiredState(key);
            if (desired != null) world.setBlockState(key.pos, desired, 19);
            preserved.add(key.pos);
        }
        for (ServerLayer server : List.copyOf(authoritativeLayers.values())) {
            if (server == null || !server.hiddenForLocalViewer || server.key.world != world
                    || server.key.pos.getX() >> 4 != chunkX
                    || server.key.pos.getZ() >> 4 != chunkZ
                    || preserved.contains(server.key.pos) || !hidesServerLayer(server.key)) continue;
            final BlockState desired = desiredState(server.key);
            if (desired != null) world.setBlockState(server.key.pos, desired, 19);
            preserved.add(server.key.pos);
        }
        directBlocks.removeChunkMutationsExcept(world, chunkX, chunkZ, preserved);
    }

    private CompletedRestore takeCompletedRestore(final BlockKey key,
                                                   final BlockState receivedState) {
        final CompletedRestore completed = completedRestores.remove(key);
        if (completed == null) return null;
        if (completed.expectedState.equals(receivedState)) {
            final BlockState liveState = completed.followLiveClientState
                    ? clientState(key.world, key.pos) : null;
            final BlockState retained = completedRestoreState(
                    completed.followLiveClientState, liveState, completed.state);
            return retained == completed.state ? completed
                    : new CompletedRestore(completed.expectedState, retained,
                    completed.followLiveClientState, completed.tick, completed.localLayerId);
        }
        log("runtime released mismatched completed TempBlock fence pos=" + key.pos
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

    public void applyAuthoritativeBatch(final ClientWorld world,
                                        final PredictionPayloads.TempBlockBatch batch) {
        if (!context.ready() || world == null || batch == null) return;
        log("runtime temp-block batch serverTick=" + batch.serverTick()
                + " ops=" + batch.operations().size());
        final String worldName = world.getRegistryKey().getValue().toString();
        final ClientPlayerEntity localPlayer = MinecraftClient.getInstance().player;
        final UUID viewerId = localPlayer == null ? null : localPlayer.getUuid();
        for (PredictionPayloads.TempBlockOp operation : batch.operations()) {
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
            final boolean advanced = serverLayers.apply(key, commonOperation,
                    operation.actionSequence(), operation.layerId(), operation.revision(),
                    operation.ownerId(), physicalState, viewerState);
            if (!advanced) continue;

            if (commonOperation == TempBlockSync.Operation.REVERT
                    || commonOperation == TempBlockSync.Operation.DISCARD) {
                final ServerLayer server = authoritativeLayers.get(operation.layerId());
                // Concealment belongs to the accepted owner lifecycle, not to
                // a particular ordinal. RaiseEarth and other moving structures
                // can legitimately produce a different number of overlapping
                // layers before their exact pairs arrive. Their closing packet
                // must still reveal the live client view rather than a delayed
                // Paper frame.
                final boolean hiddenClosingLayer = server != null
                        && server.hiddenForLocalViewer;
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
            // Keep the visibility decision for the complete server lifecycle.
            // In particular, ownership-transfer bridge layers deliberately
            // begin visible (ownerId=null) and must not become concealed when
            // a later expiry refresh is delivered.
            final boolean hiddenForLocalViewer = previous != null
                    ? previous.hiddenForLocalViewer
                    : viewerId != null && viewerId.equals(operation.ownerId());
            if (previous != null && (!Objects.equals(previous.effect, effect)
                    || previous.hiddenForLocalViewer != hiddenForLocalViewer)) {
                if (previous.effect != null) {
                    authoritativeEffects.remove(previous.effect, operation.layerId());
                }
                unpairServer(operation.layerId());
            }
            final ServerLayer server = new ServerLayer(causalSequence, key, effect,
                    operation.effectAbility(), operation.effectState(), operation.ownerId(),
                    physicalState, hiddenForLocalViewer);
            indexAuthoritative(operation.layerId(), server);
            if (effect != null && locallyOwned) {
                authoritativeEffects.put(effect, operation.layerId());
                tryMatchServer(operation.layerId(), server);
            }

            final boolean hiddenAfter = hidesServerLayer(key);
            if (hiddenAfter) {
                directBlocks.removeMutation(world, pos);
                world.setBlockState(pos, desiredState(key), 19);
            } else if (!operation.packetExpected()) {
                if (preserveLocalAuthority(key, physicalState)) {
                    world.setBlockState(pos, desiredState(key), 19);
                } else {
                    repaint(key, physicalState);
                }
            }
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
        auditTeardownFences(world);
    }

    public void expire() {
        final long tick = context.tick();
        completedRestores.entrySet().removeIf(entry -> tick - entry.getValue().tick > 2L);
        teardownFences.expireBefore(tick - ACTION_RETENTION_TICKS);
        expireUnconfirmedLayers();
    }

    public List<String> report() {
        final List<String> report = new ArrayList<>();
        report.add("TempBlocks: localRecords=" + localLayers.size()
                + " localActive=" + TempBlock.getActiveLayers().size()
                + " serverLayers=" + authoritativeLayers.size()
                + " serverCoordinates=" + serverLayers.coordinateCount()
                + " closeFences=" + completedRestores.size()
                + " teardownFences=" + teardownFences.size()
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
        int fenceDetails = 0;
        for (BlockKey key : teardownFences.keys()) {
            if (key == null || key.world == null || key.pos == null) continue;
            final BlockState current = key.world.getBlockState(key.pos);
            final Optional<BlockState> retained = teardownFences.retainedState(key);
            report.add("teardown fence pos=" + key.pos + " world=" + current
                    + " retained=" + retained.orElse(null)
                    + " staleNow=" + teardownFences.audit(key, current).isPresent());
            if (++fenceDetails >= 12) break;
        }
        return List.copyOf(report);
    }

    public void clear() {
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
        teardownFences.clear();
        teardownHistory.clear();
        authoritativeHistory.clear();
    }

    private Map<BlockKey, CapturedLifecycle> captureAbility(final CoreAbility ability) {
        if (ability == null) return Map.of();
        final Map<BlockKey, CapturedLifecycle> captured = new LinkedHashMap<>();
        for (LocalLayer local : localLayers.values()) {
            if (local == null || local.owner != ability || local.key == null) continue;
            final BlockState underlay = local.closedState != null ? local.closedState
                    : local.authoritativeUnderlay != null ? local.authoritativeUnderlay
                    : local.initialUnderlay;
            final CapturedLifecycle lifecycle = captured.computeIfAbsent(
                    local.key, ignored -> new CapturedLifecycle());
            if (underlay != null) lifecycle.underlay = underlay;
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
            if (underlay != null) lifecycle.underlay = underlay;
            lifecycle.addStale(created);
            lifecycle.addStale(underlay);
        }
        return captured;
    }

    private void finalizeAbilityRemoval(final CoreAbility ability,
                                        final Map<BlockKey, CapturedLifecycle> captured) {
        if (captured == null || captured.isEmpty()) return;
        int repainted = 0;
        int armed = 0;
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
            final BlockState hiddenViewer = hidden
                    ? serverLayers.viewerState(key).orElse(null) : null;
            final BlockState visiblePhysical = hidden
                    ? null : serverLayers.physicalState(key).orElse(null);
            final BlockState directViewer = directBlocks.viewerState(key.world, key.pos);
            final BlockState before = key.world.getBlockState(key.pos);
            final BlockState selected = TempBlockTeardownPolicy.select(local,
                    hiddenViewer, visiblePhysical, directViewer,
                    lifecycle == null ? null : lifecycle.underlay, before);
            if (local != null) remainingLocal++;
            if (hidden) hiddenServer++;
            if (selected != null && !selected.equals(before)) {
                key.world.setBlockState(key.pos, selected, 19);
                repainted++;
            }
            if (lifecycle != null && selected != null && !lifecycle.staleStates.isEmpty()) {
                teardownFences.arm(key, lifecycle.staleStates, selected, context.tick());
                armed++;
            }
            directBlocks.removeMutation(key.world, key.pos);
            if (samples.size() < 6) {
                samples.add(key.pos + ":" + before + "->" + selected
                        + (hidden ? "(server-hidden)" : "")
                        + " stale=" + (lifecycle == null ? 0 : lifecycle.staleStates.size()));
            }
        }
        teardownHistory.add("ability=" + (ability == null ? "<null>" : ability.getName())
                + " captured=" + captured.size() + " armed=" + armed
                + " repainted=" + repainted + " remainingLocal=" + remainingLocal
                + " hiddenServer=" + hiddenServer + " samples=" + samples);
        while (teardownHistory.size() > 12) teardownHistory.remove(0);
        log("runtime finalized authoritative TempBlock teardown "
                + teardownHistory.get(teardownHistory.size() - 1));
    }

    private BlockState composeTeardownView(final BlockKey key,
                                           final BlockState retainedFallback) {
        if (key == null || key.world == null || key.pos == null) return retainedFallback;
        final boolean hidden = hidesServerLayer(key);
        BlockState local = clientState(key.world, key.pos);
        final ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (hidden && player != null) {
            local = serverLayers.overlayState(key, player.getUuid()).orElse(local);
        }
        final BlockState hiddenViewer = hidden
                ? serverLayers.viewerState(key).orElse(null) : null;
        final BlockState visiblePhysical = hidden
                ? null : serverLayers.physicalState(key).orElse(null);
        return TempBlockTeardownPolicy.select(local, hiddenViewer, visiblePhysical,
                directBlocks.viewerState(key.world, key.pos),
                retainedFallback, retainedFallback);
    }

    private void auditTeardownFences(final ClientWorld world) {
        if (world == null || showServerLayers || teardownFences.size() == 0) return;
        int repaired = 0;
        for (BlockKey key : teardownFences.keys()) {
            if (key == null || key.world != world || key.pos == null) continue;
            final BlockState current = world.getBlockState(key.pos);
            final Optional<BlockState> retained = teardownFences.audit(key, current);
            if (retained.isEmpty()) continue;
            final BlockState desired = composeTeardownView(key, retained.get());
            if (desired != null && !desired.equals(current)) {
                world.setBlockState(key.pos, desired, 19);
                repaired++;
            }
        }
        if (repaired > 0) log("runtime rejected client-side late TempBlock writes=" + repaired);
    }

    private ServerLayer topAuthoritative(final BlockKey key) {
        if (key == null) return null;
        final NavigableMap<Long, ServerLayer> atCoordinate = authoritativeByCoordinate.get(key);
        if (atCoordinate == null || atCoordinate.isEmpty()) return null;
        while (!atCoordinate.isEmpty()) {
            final Map.Entry<Long, ServerLayer> newest = atCoordinate.lastEntry();
            if (serverLayers.containsLayer(key, newest.getKey())) return newest.getValue();
            atCoordinate.pollLastEntry();
        }
        authoritativeByCoordinate.remove(key);
        return null;
    }

    private boolean hidesServerLayer(final BlockKey key) {
        if (showServerLayers || key == null || key.world == null || key.pos == null) return false;
        // Paper authenticates ownerId only for a ready exact-prediction client
        // which advertised this ability. Hide that owner's complete physical
        // lifecycle immediately; waiting for an exact ordinal pair exposes the
        // latency-delayed Paper frame beside moving client TempBlocks (most
        // visibly RaiseEarth). EarthSmash ownership bridges carry no ownerId,
        // so they remain visible until the confirmed continuation takes over.
        final NavigableMap<Long, ServerLayer> atCoordinate = authoritativeByCoordinate.get(key);
        if (atCoordinate != null) {
            for (final Map.Entry<Long, ServerLayer> entry
                    : atCoordinate.descendingMap().entrySet()) {
                final ServerLayer server = entry.getValue();
                if (server != null && server.hiddenForLocalViewer
                        && serverLayers.containsLayer(key, entry.getKey())) return true;
            }
        }
        return hasSemanticPair(key);
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
        paired.removeIf(serverLayer -> {
            final Long localLayer = pairedServerLayers.get(serverLayer);
            final LocalLayer local = localLayer == null ? null : localLayers.get(localLayer);
            return local == null || (!local.closed && findActiveLayer(localLayer) == null)
                    || !serverLayers.containsLayer(key, serverLayer);
        });
        if (paired.isEmpty()) {
            pairedCoordinates.remove(key);
            return false;
        }
        return true;
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
                || local.serverClosed) return;
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
        if (local != null) reconcilePair(serverLayerId, server, localLayerId, local);
    }

    private void reconcilePair(final long serverLayerId, final ServerLayer server,
                               final long localLayerId, final LocalLayer local) {
        if (server == null || local == null || !Objects.equals(server.effect, local.effect)) return;
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
                    rebaseUnderlay(local.key, viewer));
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
            if (local != null && local.serverLayerId == serverLayerId) local.serverLayerId = 0L;
        }
    }

    private void repaint(final BlockKey key, final BlockState fallback) {
        if (key == null || key.world == null || key.pos == null) return;
        final BlockState local = clientState(key.world, key.pos);
        final BlockState desired;
        if (hidesServerLayer(key)) {
            desired = desiredState(key);
        } else if (local != null) {
            desired = local;
        } else {
            desired = serverLayers.physicalState(key).orElseGet(() -> {
                final BlockState direct = directBlocks.viewerState(key.world, key.pos);
                return direct == null ? fallback : direct;
            });
        }
        if (desired != null) key.world.setBlockState(key.pos, desired, 19);
    }

    private void expireUnconfirmedLayers() {
        List<Long> expired = null;
        for (Map.Entry<Long, LocalLayer> entry : localLayers.entrySet()) {
            final LocalLayer local = entry.getValue();
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
        if (expired == null) return;
        for (long layerId : expired) {
            final LocalLayer detached = detachLocalLayer(layerId);
            if (detached != null && detached.closed) {
                log("runtime expired unconfirmed TempBlock lifecycle layer=" + layerId
                        + " closed=true effect=" + detached.effect);
            }
        }
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
        final com.projectkorra.projectkorra.platform.mc.block.Block block =
                FabricPredictionMC.block(key.world, key.pos);
        final TempBlock layer = TempBlock.get(block);
        if (layer == null) return;
        final com.projectkorra.projectkorra.platform.mc.block.BlockState snapshot =
                FabricPredictionMC.blockStateSnapshot(key.world, key.pos, authoritativeState);
        if (snapshot == null) return;
        layer.setState(snapshot);
        final Set<Long> localAtCoordinate = localLayersByCoordinate.get(key);
        if (localAtCoordinate == null) return;
        for (long layerId : List.copyOf(localAtCoordinate)) {
            final LocalLayer local = localLayers.get(layerId);
            if (local != null && !local.closed) local.authoritativeUnderlay = authoritativeState;
        }
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
        if (showServerLayers) {
            final Optional<BlockState> physical = serverLayers.physicalState(key);
            if (physical.isPresent()) return physical.get();
        }
        final ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (hidesServerLayer(key) && player != null) {
            final Optional<BlockState> overlay = serverLayers.overlayState(key, player.getUuid());
            if (overlay.isPresent()) return overlay.get();
        }
        final BlockState local = clientState(key.world, key.pos);
        if (local != null) return local;
        if (hasSemanticPair(key)) {
            final BlockState closed = closedClientState(key);
            if (closed != null) return closed;
        }
        if (hidesServerLayer(key)) {
            final Optional<BlockState> viewer = serverLayers.viewerState(key);
            if (viewer.isPresent()) return viewer.get();
        }
        final Optional<BlockState> physical = serverLayers.physicalState(key);
        if (physical.isPresent()) return physical.get();
        final Optional<BlockState> serverViewer = serverLayers.viewerState(key);
        if (serverViewer.isPresent()) return serverViewer.get();
        final BlockState direct = directBlocks.viewerState(key.world, key.pos);
        return direct == null ? key.world.getBlockState(key.pos) : direct;
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
        final Set<BlockKey> coordinates = new HashSet<>(authoritativeByCoordinate.keySet());
        for (LocalLayer local : localLayers.values()) {
            if (local != null && local.key != null) coordinates.add(local.key);
        }
        coordinates.addAll(teardownFences.keys());
        for (BlockKey key : coordinates) {
            if (key.world == null || key.pos == null) continue;
            final BlockState current = key.world.getBlockState(key.pos);
            final Optional<BlockState> fenced = showServerLayers
                    ? Optional.empty() : teardownFences.audit(key, current);
            final BlockState desired = fenced.isPresent()
                    ? composeTeardownView(key, fenced.get()) : desiredState(key);
            if (desired != null && !desired.equals(current)) {
                key.world.setBlockState(key.pos, desired, 19);
            }
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
    private static final class CapturedLifecycle {
        private BlockState underlay;
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
                               BlockState physicalState, boolean hiddenForLocalViewer) { }
    private record CompletedRestore(BlockState expectedState, BlockState state,
                                    boolean followLiveClientState, long tick,
                                    long localLayerId) { }
}
