package com.projectkorra.projectkorra.fabric.client.prediction.block;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads;
import com.projectkorra.projectkorra.platform.fabric.FabricPredictionMC;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.block.DirectBlockAuthorityPolicy;
import com.projectkorra.projectkorra.prediction.block.DirectBlockSync;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;
import com.projectkorra.projectkorra.util.Information;
import com.projectkorra.projectkorra.util.TempBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Owns direct (non-TempBlock) predicted world writes and their Paper receipts.
 *
 * <p>Direct Earth writes are causal transactions. The common client lifecycle
 * remains the owner's visual state, while Paper's exact state is retained as a
 * comparison key for vanilla block packets and chunk snapshots.</p>
 */
public final class ClientDirectBlockAuthority {
    private static final int HISTORY_LIMIT = 72;

    /** Runtime action information needed without exposing the action model. */
    public interface Context {
        long currentAction();
        long tick();
        String inputAbility(long actionSequence);
        void markMutation(long actionSequence, String ability, int ordinal);
        boolean hasAction(long actionSequence);
        boolean hasActiveAbility(long actionSequence, String ability);
        int confirmationTicks(long actionSequence);
    }

    private final Context context;
    private final Function<String, BlockState> blockStateDecoder;
    private final Consumer<String> debug;
    private final Map<BlockKey, BlockMutation> mutations = new HashMap<>();
    private final Map<EffectKey, PredictedWrite> predictedWrites = new LinkedHashMap<>();
    private final LinkedHashMap<CauseKey, PredictedCause> predictedCauses = new LinkedHashMap<>();
    private final LinkedHashMap<BlockKey, RecentVisual> recentVisuals = new LinkedHashMap<>();
    private final List<ConfirmedWrite> confirmedPackets = new ArrayList<>();
    private final Map<BlockKey, DirectMask> serverMasks = new LinkedHashMap<>();
    private final List<String> history = new ArrayList<>();
    private long visualRevision;
    private long predictedWriteCount;
    private long receiptCount;
    private long concealedReceiptCount;
    private long maskedPacketCount;
    private long releasedMaskCount;

    public ClientDirectBlockAuthority(final Context context,
                                      final Function<String, BlockState> blockStateDecoder,
                                      final Consumer<String> debug) {
        this.context = context;
        this.blockStateDecoder = blockStateDecoder;
        this.debug = debug == null ? ignored -> { } : debug;
    }

    public void predict(final ClientWorld world, final BlockPos pos, final BlockState state) {
        if (world == null || pos == null || state == null) return;
        final long actionSequence = context.currentAction();
        final BlockKey key = new BlockKey(world, pos.toImmutable());
        final BlockState before = simulatedState(world, pos);
        final CoreAbility ability = AbilityExecutionContext.current();
        final DirectBlockSync.EarthLifecycle lifecycle = DirectBlockSync.currentEarthLifecycle();
        final long causalSequence = actionSequence > 0L ? actionSequence
                : lifecycle != null && lifecycle.valid() ? lifecycle.actionSequence() : 0L;
        final String abilityName = ability != null ? ability.getName()
                : lifecycle != null && lifecycle.valid() ? lifecycle.ability()
                : context.inputAbility(actionSequence);
        final ClientPlayerEntity localPlayer = MinecraftClient.getInstance().player;
        EffectKey effect = null;
        CauseKey cause = null;
        if (causalSequence > 0L && DirectBlockSync.isPredictable(ability, abilityName)
                && (lifecycle == null || !lifecycle.valid() || localPlayer != null
                && localPlayer.getUuid().equals(lifecycle.ownerId()))) {
            final String normalized = abilityName == null ? "" : abilityName.toLowerCase(Locale.ROOT);
            cause = new CauseKey(causalSequence, normalized);
            final PredictedCause causeState = predictedCauses.computeIfAbsent(cause,
                    ignored -> new PredictedCause());
            // Consume a semantic common-code ordinal even for an equal-state
            // no-op so a single asymmetric branch cannot shift later receipts.
            final int ordinal = ++causeState.lastOrdinal;
            causeState.lastTick = context.tick();
            context.markMutation(causalSequence, normalized, ordinal);
            effect = new EffectKey(causalSequence, normalized, ordinal);
        }

        if (effect != null) {
            updateLocalView(key, before == null ? world.getBlockState(pos) : before,
                    state, cause, localPlayer == null ? null : localPlayer.getUuid());
        }
        if (state.equals(before)) return;

        final BlockMutation mutation = mutations.computeIfAbsent(key,
                ignored -> new BlockMutation(world, pos.toImmutable()));
        mutation.lastAction = actionSequence;
        mutation.lastTick = context.tick();
        mutation.predicted = state;
        mutation.locallyPredicted = true;
        if (effect == null) return;

        final long revision = ++visualRevision;
        predictedWrites.put(effect, new PredictedWrite(key,
                before == null ? world.getBlockState(pos) : before,
                state, context.tick(), revision));
        recentVisuals.put(key, new RecentVisual(effect, state, context.tick(), revision));
        world.setBlockState(pos, state, 19);
        predictedWriteCount++;
        record("tick=" + context.tick() + " LOCAL effect=" + effect
                + " pos=" + pos + " " + before + "->" + state);
        debug.accept("runtime painted causal earth write effect=" + effect
                + " pos=" + pos + " state=" + state);
    }

    public BlockState simulatedState(final ClientWorld world, final BlockPos pos) {
        if (world == null || pos == null) return null;
        final BlockMutation mutation = mutations.get(new BlockKey(world, pos.toImmutable()));
        if (mutation == null) return world.getBlockState(pos);
        final long action = context.currentAction();
        return action != 0L && action == mutation.lastAction
                ? mutation.predicted : world.getBlockState(pos);
    }

    public boolean suppressBreakAnimation(final ClientWorld world, final BlockPos pos) {
        final BlockMutation mutation = mutations.get(new BlockKey(world, pos.toImmutable()));
        return mutation != null && mutation.locallyPredicted;
    }

    public void noteReceipt(final Entity localPlayer,
                            final PredictionPayloads.DirectBlockReceipt receipt,
                            final long localSequence, final ClientWorld world) {
        if (localPlayer == null || receipt == null || world == null
                || receipt.actionSequence() <= 0L
                || !localPlayer.getUuid().equals(receipt.abilityOwner())
                || !matchesWorld(world.getRegistryKey().getValue().toString(), receipt.world())) return;
        if (localSequence <= 0L) {
            debug.accept("runtime allowed authoritative direct write without mapped action paperSequence="
                    + receipt.actionSequence() + " ability=" + receipt.ability());
            return;
        }
        final String normalized = receipt.ability().toLowerCase(Locale.ROOT);
        final EffectKey effect = new EffectKey(localSequence, normalized, receipt.mutationOrdinal());
        final CauseKey cause = new CauseKey(localSequence, normalized);
        final PredictedWrite local = predictedWrites.remove(effect);
        final boolean knownCause = predictedCauses.containsKey(cause);
        receiptCount++;
        if (!DirectBlockAuthorityPolicy.mayConceal(
                local != null, receipt.movedEarthLifecycle(), knownCause)) {
            record("tick=" + context.tick() + " RECEIPT allow effect=" + effect
                    + " paperAction=" + receipt.actionSequence()
                    + " pos=(" + receipt.x() + "," + receipt.y() + "," + receipt.z() + ")"
                    + " state=" + receipt.material() + " exact=" + (local != null)
                    + " movedEarth=" + receipt.movedEarthLifecycle()
                    + " knownCause=" + knownCause);
            debug.accept("runtime allowed authoritative direct write without exact local effect="
                    + effect);
            return;
        }

        final BlockKey serverKey = new BlockKey(world,
                new BlockPos(receipt.x(), receipt.y(), receipt.z()).toImmutable());
        final BlockState serverState = blockStateDecoder.apply(receipt.material());
        final boolean sameCoordinate = local != null && local.key.equals(serverKey);
        final boolean sameState = local != null && local.after.equals(serverState);
        final BlockState serverUnderlay = world.getBlockState(serverKey.pos);
        final RecentVisual observedVisual = recentVisuals.get(serverKey);
        final long observedRevision = observedVisual == null ? 0L : observedVisual.revision;
        final DirectMask existingMask = serverMasks.get(serverKey);
        final BlockState viewerState = existingMask == null
                ? clientBaseState(serverKey, serverUnderlay) : existingMask.viewerState;
        serverMasks.put(serverKey, new DirectMask(serverState, viewerState,
                cause, localPlayer.getUuid(), context.tick()));
        concealedReceiptCount++;
        record("tick=" + context.tick() + " RECEIPT conceal effect=" + effect
                + " paperAction=" + receipt.actionSequence()
                + " serverPos=" + serverKey.pos + " serverState=" + serverState
                + " viewer=" + viewerState + " exact=" + (local != null)
                + " localPos=" + (local == null ? null : local.key.pos)
                + " localState=" + (local == null ? null : local.after)
                + " movedEarth=" + receipt.movedEarthLifecycle()
                + " knownCause=" + knownCause);
        if (local != null && (!sameCoordinate || !sameState)) {
            // The common client transaction remains the visual answer. This
            // receipt fences Paper's write; it does not relocate the local
            // wall or install Paper's intermediate source-hole air.
            debug.accept("runtime concealed divergent causal write effect=" + effect
                    + " clientPos=" + local.key.pos + " serverPos=" + serverKey.pos
                    + " clientState=" + local.after + " serverState=" + serverState);
        } else if (local == null && receipt.movedEarthLifecycle()) {
            debug.accept("runtime concealed unmatched moved-earth physical write effect="
                    + effect + " serverPos=" + serverKey.pos + " serverState=" + serverState);
        }
        // Only the last same-tick write to one coordinate can become the
        // chunk-delta entry. Retaining earlier receipts would swallow a later,
        // unrelated restore to the same state and create a ghost block.
        confirmedPackets.removeIf(packet -> packet.serverTick == receipt.serverTick()
                && packet.key.equals(serverKey));
        confirmedPackets.add(new ConfirmedWrite(receipt.serverTick(), serverKey,
                serverState, cause, localPlayer.getUuid(),
                local == null ? 0L : local.visualRevision, observedRevision,
                serverUnderlay, context.tick()));
    }

    public void updateServerViewer(final ClientWorld world, final BlockPos pos,
                                   final BlockState viewerState) {
        final BlockKey key = key(world, pos);
        if (key == null || viewerState == null) return;
        final DirectMask existing = serverMasks.get(key);
        if (existing == null) return;
        serverMasks.put(key, new DirectMask(existing.serverState, viewerState,
                existing.cause, existing.ownerId, context.tick()));
    }

    /**
     * Returns a durable owner mask only for the physical state it owns. A
     * different unlayered state is external authority and releases the mask.
     */
    public DirectView maskForIncoming(final ClientWorld world, final BlockPos pos,
                                      final BlockState incoming) {
        final BlockKey key = key(world, pos);
        final DirectMask mask = key == null ? null : serverMasks.get(key);
        if (mask == null || incoming == null) return null;
        if (mask.serverState.equals(incoming)) {
            maskedPacketCount++;
            record("tick=" + context.tick() + " PACKET mask pos=" + key.pos
                    + " server=" + incoming + " viewer=" + mask.viewerState
                    + " cause=" + mask.cause);
            return new DirectView(mask.viewerState);
        }
        serverMasks.remove(key, mask);
        releasedMaskCount++;
        record("tick=" + context.tick() + " PACKET release pos=" + key.pos
                + " expected=" + mask.serverState + " incoming=" + incoming
                + " viewer=" + mask.viewerState + " cause=" + mask.cause);
        debug.accept("runtime released owned earth view for external state pos=" + key.pos
                + " expected=" + mask.serverState + " received=" + incoming);
        return null;
    }

    public BlockState viewerState(final ClientWorld world, final BlockPos pos) {
        final BlockKey key = key(world, pos);
        final DirectMask mask = key == null ? null : serverMasks.get(key);
        return mask == null ? null : mask.viewerState;
    }

    public ConfirmedWrite takeConfirmed(final ClientWorld world, final BlockPos pos,
                                        final BlockState state) {
        final BlockKey key = key(world, pos);
        for (int index = 0; index < confirmedPackets.size(); index++) {
            final ConfirmedWrite packet = confirmedPackets.get(index);
            if (!packet.key.equals(key) || !packet.state.equals(state)) continue;
            confirmedPackets.remove(index);
            return packet;
        }
        return null;
    }

    public BlockState desiredState(final ConfirmedWrite packet) {
        if (packet == null || packet.key == null || packet.key.world == null) return null;
        final BlockState current = packet.key.world.getBlockState(packet.key.pos);
        if (current.equals(packet.state)) return current;

        final RecentVisual recent = recentVisuals.get(packet.key);
        if (recent != null && current.equals(recent.state)) {
            final boolean retainsExactOrLater = packet.localVisualRevision > 0L
                    && recent.revision >= packet.localVisualRevision;
            final boolean changedAfterReceipt = recent.revision > packet.observedVisualRevision;
            if (retainsExactOrLater || changedAfterReceipt) return recent.state;
        }
        if (hasActiveEarthCoordinate(packet.key, packet.ownerId, packet.cause)) return current;
        return packet.serverUnderlay;
    }

    public void confirmFromVanilla(final ClientWorld world, final BlockPos pos,
                                   final BlockState state) {
        final BlockKey key = key(world, pos);
        for (PredictedWrite predicted : predictedWrites.values()) {
            if (predicted.key.equals(key) && predicted.after.equals(state)) {
                predicted.vanillaConfirmed = true;
            }
        }
    }

    public Set<BlockPos> restoreChunk(final ClientWorld world, final int chunkX, final int chunkZ,
                                      final BiPredicate<BlockPos, BlockState> isTempPhysical) {
        final Set<BlockPos> preserved = new HashSet<>();
        for (Map.Entry<BlockKey, DirectMask> entry : List.copyOf(serverMasks.entrySet())) {
            final BlockKey key = entry.getKey();
            final DirectMask mask = entry.getValue();
            if (key.world != world || key.pos.getX() >> 4 != chunkX
                    || key.pos.getZ() >> 4 != chunkZ) continue;
            final BlockState chunkState = world.getBlockState(key.pos);
            if (isTempPhysical != null && isTempPhysical.test(key.pos, chunkState)) continue;
            if (!mask.serverState.equals(chunkState)) {
                serverMasks.remove(key, mask);
                debug.accept("runtime released owned earth view for external chunk state pos="
                        + key.pos + " expected=" + mask.serverState + " received=" + chunkState);
                continue;
            }
            if (!chunkState.equals(mask.viewerState)) {
                world.setBlockState(key.pos, mask.viewerState, 19);
            }
            preserved.add(key.pos);
        }
        return preserved;
    }

    public void clearTransientReads() {
        mutations.clear();
    }

    public void removeMutation(final ClientWorld world, final BlockPos pos) {
        final BlockKey key = key(world, pos);
        if (key != null) mutations.remove(key);
    }

    public void removeChunkMutationsExcept(final ClientWorld world, final int chunkX, final int chunkZ,
                                           final Set<BlockPos> preserved) {
        mutations.entrySet().removeIf(entry -> {
            final BlockKey key = entry.getKey();
            if (key.world != world || key.pos.getX() >> 4 != chunkX
                    || key.pos.getZ() >> 4 != chunkZ) return false;
            return preserved == null || !preserved.contains(key.pos);
        });
    }

    public void expire(final UUID localPlayer, final int actionRetentionTicks,
                       final int earthCauseRetentionTicks) {
        final long tick = context.tick();
        confirmedPackets.removeIf(packet -> tick - packet.receivedTick > 4L);
        serverMasks.entrySet().removeIf(entry -> {
            final DirectMask mask = entry.getValue();
            return mask.serverState.equals(mask.viewerState)
                    && tick - mask.updatedTick > actionRetentionTicks
                    && !hasActiveCause(mask.ownerId, mask.cause);
        });
        for (Map.Entry<EffectKey, PredictedWrite> entry
                : List.copyOf(predictedWrites.entrySet())) {
            if (tick - entry.getValue().createdTick
                    <= context.confirmationTicks(entry.getKey().actionSequence)) continue;
            // Expiry is bookkeeping only. Repainting the saved before-state
            // resurrects source air and erases a RaiseEarth/EarthSmash move
            // whose causal receipt used a different physical ordinal.
            predictedWrites.remove(entry.getKey(), entry.getValue());
        }
        final Set<CauseKey> activeEarthCauses = activeEarthCauses(localPlayer);
        predictedCauses.entrySet().removeIf(entry ->
                tick - entry.getValue().lastTick > earthCauseRetentionTicks
                        && !context.hasAction(entry.getKey().actionSequence)
                        && !activeEarthCauses.contains(entry.getKey()));
        recentVisuals.entrySet().removeIf(entry ->
                tick - entry.getValue().createdTick > earthCauseRetentionTicks);
        while (predictedCauses.size() > 4_096) {
            predictedCauses.remove(predictedCauses.keySet().iterator().next());
        }
        while (recentVisuals.size() > 4_096) {
            recentVisuals.remove(recentVisuals.keySet().iterator().next());
        }
        mutations.entrySet().removeIf(entry -> {
            final BlockMutation mutation = entry.getValue();
            return tick - mutation.lastTick > context.confirmationTicks(mutation.lastAction);
        });
    }

    public void rollbackAction(final long actionSequence) {
        mutations.entrySet().removeIf(entry -> entry.getValue().lastAction == actionSequence);
    }

    public int mutationCount() {
        return mutations.size();
    }

    /** Bounded diagnostics for direct moved-earth prediction and packet authority. */
    public List<String> report() {
        final List<String> report = new ArrayList<>();
        report.add("DirectBlocks: transient=" + mutations.size()
                + " pendingEffects=" + predictedWrites.size()
                + " causes=" + predictedCauses.size()
                + " recentVisuals=" + recentVisuals.size()
                + " masks=" + serverMasks.size()
                + " confirmedPackets=" + confirmedPackets.size()
                + " totals={local=" + predictedWriteCount
                + ",receipts=" + receiptCount
                + ",concealed=" + concealedReceiptCount
                + ",maskedPackets=" + maskedPacketCount
                + ",releasedMasks=" + releasedMaskCount + "}");
        if (history.isEmpty()) {
            report.add("DirectBlock history: no causal world write was recorded");
        } else {
            report.add("DirectBlock history (oldest to newest):");
            report.addAll(history);
        }
        return List.copyOf(report);
    }

    public void clear() {
        mutations.clear();
        predictedWrites.clear();
        predictedCauses.clear();
        recentVisuals.clear();
        confirmedPackets.clear();
        serverMasks.clear();
        history.clear();
        visualRevision = 0L;
        predictedWriteCount = 0L;
        receiptCount = 0L;
        concealedReceiptCount = 0L;
        maskedPacketCount = 0L;
        releasedMaskCount = 0L;
    }

    private void record(final String entry) {
        if (entry == null || entry.isBlank()) return;
        history.add(entry);
        while (history.size() > HISTORY_LIMIT) history.remove(0);
    }

    private void updateLocalView(final BlockKey key, final BlockState before,
                                 final BlockState viewerState, final CauseKey cause,
                                 final UUID ownerId) {
        if (key == null || before == null || viewerState == null
                || cause == null || ownerId == null) return;
        final DirectMask existing = serverMasks.get(key);
        serverMasks.put(key, new DirectMask(
                existing == null ? before : existing.serverState,
                viewerState, cause, ownerId, context.tick()));
    }

    private BlockState clientBaseState(final BlockKey key, final BlockState fallback) {
        if (key == null || key.world == null || key.pos == null) return fallback;
        final TempBlock layer = TempBlock.get(FabricPredictionMC.block(key.world, key.pos));
        if (layer == null || layer.getState() == null
                || layer.getState().getBlockData() == null) return fallback;
        return blockStateDecoder.apply(TempBlockSync.encode(layer.getState().getBlockData()));
    }

    private boolean hasActiveCause(final UUID ownerId, final CauseKey cause) {
        if (ownerId == null || cause == null) return false;
        return context.hasActiveAbility(cause.actionSequence, cause.ability)
                || activeEarthCauses(ownerId).contains(cause);
    }

    private static Set<CauseKey> activeEarthCauses(final UUID ownerId) {
        final Set<CauseKey> causes = new HashSet<>();
        if (ownerId == null) return causes;
        for (Information information : EarthAbility.getMovedEarth().values()) {
            addEarthLifecycle(causes, information, ownerId);
        }
        for (Information information : EarthAbility.getTempAirLocations().values()) {
            addEarthLifecycle(causes, information, ownerId);
        }
        return causes;
    }

    private static void addEarthLifecycle(final Set<CauseKey> causes,
                                          final Information information,
                                          final UUID ownerId) {
        if (information == null || !ownerId.equals(information.getPredictionOwner())
                || information.getPredictionActionSequence() <= 0L
                || information.getPredictionAbility() == null) return;
        causes.add(new CauseKey(information.getPredictionActionSequence(),
                information.getPredictionAbility().toLowerCase(Locale.ROOT)));
    }

    private static boolean matchesEarthLifecycle(final Information information,
                                                 final UUID ownerId,
                                                 final CauseKey cause) {
        return information != null && ownerId.equals(information.getPredictionOwner())
                && information.getPredictionActionSequence() == cause.actionSequence
                && information.getPredictionAbility() != null
                && information.getPredictionAbility().equalsIgnoreCase(cause.ability);
    }

    private boolean hasActiveEarthCoordinate(final BlockKey key, final UUID ownerId,
                                             final CauseKey cause) {
        if (key == null || ownerId == null || cause == null) return false;
        for (Map.Entry<com.projectkorra.projectkorra.platform.mc.block.Block, Information> entry
                : EarthAbility.getMovedEarth().entrySet()) {
            if (key.equals(clientKey(entry.getKey()))
                    && matchesEarthLifecycle(entry.getValue(), ownerId, cause)) return true;
        }
        for (Information information : EarthAbility.getTempAirLocations().values()) {
            if (information != null && key.equals(clientKey(information.getBlock()))
                    && matchesEarthLifecycle(information, ownerId, cause)) return true;
        }
        return false;
    }

    private static BlockKey clientKey(
            final com.projectkorra.projectkorra.platform.mc.block.Block block) {
        final ClientWorld world = MinecraftClient.getInstance().world;
        if (block == null || block.getWorld() == null || world == null
                || !matchesWorld(world.getRegistryKey().getValue().toString(),
                block.getWorld().getName())) return null;
        return new BlockKey(world,
                new BlockPos(block.getX(), block.getY(), block.getZ()).toImmutable());
    }

    private static BlockKey key(final ClientWorld world, final BlockPos pos) {
        return world == null || pos == null ? null : new BlockKey(world, pos.toImmutable());
    }

    private static boolean matchesWorld(final String clientWorld, final String serverWorld) {
        if (serverWorld == null || serverWorld.isBlank()) return false;
        if (clientWorld.equals(serverWorld)) return true;
        return serverWorld.indexOf(':') < 0
                && ("minecraft:overworld".equals(clientWorld) || "overworld".equals(clientWorld));
    }

    /** Public projection; internal ownership identity remains encapsulated. */
    public record DirectView(BlockState viewerState) { }

    /** Opaque exact receipt consumed by {@link #desiredState(ConfirmedWrite)}. */
    public static final class ConfirmedWrite {
        private final long serverTick;
        private final BlockKey key;
        private final BlockState state;
        private final CauseKey cause;
        private final UUID ownerId;
        private final long localVisualRevision;
        private final long observedVisualRevision;
        private final BlockState serverUnderlay;
        private final long receivedTick;

        private ConfirmedWrite(final long serverTick, final BlockKey key,
                               final BlockState state, final CauseKey cause,
                               final UUID ownerId, final long localVisualRevision,
                               final long observedVisualRevision,
                               final BlockState serverUnderlay,
                               final long receivedTick) {
            this.serverTick = serverTick;
            this.key = key;
            this.state = state;
            this.cause = cause;
            this.ownerId = ownerId;
            this.localVisualRevision = localVisualRevision;
            this.observedVisualRevision = observedVisualRevision;
            this.serverUnderlay = serverUnderlay;
            this.receivedTick = receivedTick;
        }
    }

    private record BlockKey(ClientWorld world, BlockPos pos) { }
    private record CauseKey(long actionSequence, String ability) { }
    private record EffectKey(long actionSequence, String ability, int mutationOrdinal) { }
    private static final class PredictedCause {
        private int lastOrdinal;
        private long lastTick;
    }
    private static final class PredictedWrite {
        private final BlockKey key;
        private final BlockState before;
        private final BlockState after;
        private final long createdTick;
        private final long visualRevision;
        private boolean vanillaConfirmed;

        private PredictedWrite(final BlockKey key, final BlockState before,
                               final BlockState after, final long createdTick,
                               final long visualRevision) {
            this.key = key;
            this.before = before;
            this.after = after;
            this.createdTick = createdTick;
            this.visualRevision = visualRevision;
        }
    }
    private record RecentVisual(EffectKey effect, BlockState state,
                                long createdTick, long revision) { }
    private record DirectMask(BlockState serverState, BlockState viewerState,
                              CauseKey cause, UUID ownerId, long updatedTick) { }
    private static final class BlockMutation {
        private final ClientWorld world;
        private final BlockPos pos;
        private BlockState predicted;
        private long lastAction;
        private long lastTick;
        private boolean locallyPredicted;

        private BlockMutation(final ClientWorld world, final BlockPos pos) {
            this.world = world;
            this.pos = pos;
        }
    }
}
