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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 * Owns render-only direct (non-TempBlock) prediction and its Paper receipts.
 *
 * <p>Direct Earth changes are causal transactions. The common client lifecycle
 * remains the owner's visual state, while Paper's exact state is retained as a
 * comparison key for vanilla block packets and chunk snapshots.</p>
 */
public final class ClientDirectBlockAuthority {
    private static final int HISTORY_LIMIT = 72;
    private static final int COALESCED_PACKET_GRACE_TICKS = 4;

    /** Runtime action information needed without exposing the action model. */
    public interface Context {
        long currentAction();
        long tick();
        String inputAbility(long actionSequence);
        void markMutation(long actionSequence, String ability, int ordinal);
        boolean hasAction(long actionSequence);
        boolean hasActiveAbility(long actionSequence, String ability);
        boolean sameActiveAbilityLifecycle(long actionSequence,
                                           long creationActionSequence,
                                           String ability);
        int confirmationTicks(long actionSequence);
    }

    private final Context context;
    private final Function<String, BlockState> blockStateDecoder;
    private final ClientBlockVisualOverlay visualOverlay;
    private final Consumer<String> debug;
    private final Map<BlockKey, BlockMutation> mutations = new HashMap<>();
    private final Map<EffectKey, PredictedWrite> predictedWrites = new LinkedHashMap<>();
    private final LinkedHashMap<CauseKey, PredictedCause> predictedCauses = new LinkedHashMap<>();
    private final LinkedHashMap<BlockKey, RecentVisual> recentVisuals = new LinkedHashMap<>();
    private final List<ConfirmedWrite> confirmedPackets = new ArrayList<>();
    private final LinkedHashMap<BlockKey, SupersededReceipts> supersededReceipts =
            new LinkedHashMap<>();
    private final Map<BlockKey, DirectMask> serverMasks = new LinkedHashMap<>();
    private final Deque<String> history = new ArrayDeque<>();
    private long visualRevision;
    private long predictedWriteCount;
    private long receiptCount;
    private long concealedReceiptCount;
    private long maskedPacketCount;
    private long releasedMaskCount;
    private long convergedMaskCount;
    private long completedFrameCount;

    public ClientDirectBlockAuthority(final Context context,
                                      final Function<String, BlockState> blockStateDecoder,
                                      final ClientBlockVisualOverlay visualOverlay,
                                      final Consumer<String> debug) {
        this.context = context;
        this.blockStateDecoder = blockStateDecoder;
        this.visualOverlay = visualOverlay;
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
        boolean authoritativeFrameComplete = false;
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
            authoritativeFrameComplete = causeState.authoritativeFrameComplete;
        }

        if (effect != null && authoritativeFrameComplete) {
            // The final Paper RaiseEarth frame now owns this cause. Continue
            // consuming the common lifecycle and its ordinals, but never let a
            // later local RevertChecker/animation write replace that frame.
            record("tick=" + context.tick() + " LOCAL suppress completed-frame effect="
                    + effect + " pos=" + pos + " state=" + state);
            return;
        }

        if (state.equals(before)) return;

        final long revision = effect == null ? 0L : ++visualRevision;
        if (effect != null) {
            updateLocalView(key, before == null ? world.getBlockState(pos) : before,
                    state, cause, localPlayer == null ? null : localPlayer.getUuid(),
                    effect, revision);
        }

        final BlockMutation mutation = mutations.computeIfAbsent(key,
                ignored -> new BlockMutation(world, pos.toImmutable()));
        mutation.lastAction = actionSequence;
        mutation.lastTick = context.tick();
        mutation.predicted = state;
        mutation.locallyPredicted = true;
        if (effect == null) return;

        predictedWrites.put(effect, new PredictedWrite(key,
                before == null ? world.getBlockState(pos) : before,
                state, context.tick(), revision));
        recentVisuals.put(key, new RecentVisual(effect, state, context.tick(), revision));
        refreshVisual(key);
        predictedWriteCount++;
        record("tick=" + context.tick() + " LOCAL effect=" + effect
                + " pos=" + pos + " " + before + "->" + state);
        debug.accept("runtime painted causal earth write effect=" + effect
                + " pos=" + pos + " state=" + state);
    }

    public BlockState simulatedState(final ClientWorld world, final BlockPos pos) {
        if (world == null || pos == null) return null;
        final BlockKey key = new BlockKey(world, pos.toImmutable());
        final DirectMask mask = serverMasks.get(key);
        if (mask != null) return mask.viewerState;
        final BlockMutation mutation = mutations.get(key);
        return mutation != null && mutation.locallyPredicted
                ? mutation.predicted : world.getBlockState(pos);
    }

    public boolean suppressBreakAnimation(final ClientWorld world, final BlockPos pos) {
        if (world == null || pos == null) return false;
        final BlockKey key = new BlockKey(world, pos.toImmutable());
        final BlockMutation mutation = mutations.get(key);
        if (mutation != null && mutation.locallyPredicted) return true;
        final DirectMask mask = serverMasks.get(key);
        return mask != null && (DirectBlockAuthorityPolicy.requiresAuthoritativeHandoff(
                mask.visualCause.ability)
                || hasActiveCause(mask.ownerId, mask.visualCause));
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
                normalized, local != null, receipt.movedEarthLifecycle(), knownCause)) {
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
        final PredictedCause causeState = predictedCauses.computeIfAbsent(cause,
                ignored -> new PredictedCause());
        causeState.lastReceiptTick = context.tick();
        causeState.lastTick = Math.max(causeState.lastTick, context.tick());
        final BlockKey serverKey = new BlockKey(world,
                new BlockPos(receipt.x(), receipt.y(), receipt.z()).toImmutable());
        final DirectMask existingMask = serverMasks.get(serverKey);
        final boolean existingVisualEligible = existingMask != null
                && (cause.equals(existingMask.visualCause)
                || !authoritativeCauseClosed(existingMask.visualCause));
        final boolean existingPhysicalLeaseOpen = existingMask != null
                && earthBlastLease(serverKey, existingMask) == EarthBlastLease.OPEN;
        final boolean overlapsProtectedMask = existingPhysicalLeaseOpen
                || (existingVisualEligible && !cause.equals(existingMask.visualCause));
        if ((causeState.authoritativeFrameComplete
                || causeState.authoritativeClosedTick >= 0L)
                && !overlapsProtectedMask) {
            if (existingMask != null && !existingVisualEligible
                    && serverMasks.remove(serverKey, existingMask)) {
                releaseVisual(serverKey, existingMask, false);
            }
            // AbilityRemoved is ordered after the ability's final direct
            // writes. Anything later from this cause is server-owned cleanup
            // (normally RevertChecker restoring the eventual source block).
            // A different live visual at this coordinate is the exception:
            // its overlay still needs the restore's pending physical identity.
            record("tick=" + context.tick() + " RECEIPT allow closed-cause effect="
                    + effect + " paperAction=" + receipt.actionSequence()
                    + " pos=(" + receipt.x() + "," + receipt.y() + "," + receipt.z() + ")"
                    + " state=" + receipt.material());
            return;
        }

        final BlockState serverState = blockStateDecoder.apply(receipt.material());
        final boolean sameCoordinate = local != null && local.key.equals(serverKey);
        final boolean sameState = local != null && local.after.equals(serverState);
        final BlockState serverUnderlay = world.getBlockState(serverKey.pos);
        final RecentVisual observedVisual = recentVisuals.get(serverKey);
        final CauseKey observedVisualCause = observedVisual == null ? null
                : new CauseKey(observedVisual.effect.actionSequence,
                observedVisual.effect.ability);
        final boolean observedVisualEligible = observedVisual != null
                && (cause.equals(observedVisualCause)
                || !authoritativeCauseClosed(observedVisualCause));
        final long observedRevision = observedVisual == null ? 0L : observedVisual.revision;
        // A receipt may update physical comparison metadata, but local visual
        // chronology is revision-based. Action ids alone are insufficient: a
        // long-lived EarthBlast can write again after a newer cast has begun.
        final long exactLocalRevision = sameCoordinate
                ? local.visualRevision : 0L;
        final DirectVisualOrderPolicy.Source visualSource =
                DirectVisualOrderPolicy.select(
                        existingVisualEligible,
                        existingVisualEligible
                                && existingMask.visualProvenance.locallyPredicted(),
                        !existingVisualEligible ? 0L
                                : existingMask.visualCause.actionSequence,
                        !existingVisualEligible ? 0L : existingMask.visualRevision,
                        observedVisualEligible, observedRevision,
                        cause.actionSequence, exactLocalRevision);
        final boolean retainExisting =
                visualSource == DirectVisualOrderPolicy.Source.EXISTING;
        final boolean retainObserved =
                visualSource == DirectVisualOrderPolicy.Source.OBSERVED;
        final CauseKey maskCause = retainObserved ? observedVisualCause
                : retainExisting ? existingMask.visualCause : cause;
        final BlockState viewerState = retainObserved ? observedVisual.state
                : retainExisting ? existingMask.viewerState
                : sameCoordinate ? local.after
                : existingMask != null
                ? (existingVisualEligible ? existingMask.viewerState
                : existingMask.physicalViewerState)
                : clientBaseState(serverKey, serverUnderlay);
        final DirectVisualProvenance visualProvenance = retainObserved
                ? DirectVisualProvenance.ACTIVE_LOCAL
                : retainExisting ? existingMask.visualProvenance
                : sameCoordinate ? DirectVisualProvenance.ACTIVE_LOCAL
                : DirectVisualProvenance.RECEIPT_ONLY;
        final EffectKey visualEffect = retainObserved ? observedVisual.effect
                : retainExisting ? existingMask.visualEffect
                : sameCoordinate ? effect : null;
        final long maskVisualRevision = retainObserved ? observedVisual.revision
                : retainExisting ? existingMask.visualRevision
                : sameCoordinate ? local.visualRevision : 0L;
        final boolean carryExistingEarthBlastState = "earthblast".equals(cause.ability)
                || causeState.authoritativeClosedTick >= 0L
                || causeState.authoritativeFrameComplete;
        final BlockState physicalViewerState = existingMask != null
                && (carryExistingEarthBlastState
                || earthBlastLease(serverKey, existingMask) == EarthBlastLease.OPEN)
                ? existingMask.physicalViewerState
                : sameCoordinate ? local.before
                : clientBaseState(serverKey, serverUnderlay);
        final Set<CauseKey> earthBlastLeaseCauses = receiptEarthBlastLeaseCauses(
                serverKey, existingMask, cause, serverState,
                carryExistingEarthBlastState);
        serverMasks.put(serverKey, new DirectMask(serverState, viewerState,
                physicalViewerState,
                maskCause, localPlayer.getUuid(), true, visualProvenance,
                visualEffect, maskVisualRevision, cause, earthBlastLeaseCauses,
                context.tick(),
                0L, context.tick()));
        refreshVisual(serverKey);
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
        rememberSupersededReceipts(
                receipt.serverTick(), serverKey, cause, serverState);
        confirmedPackets.add(new ConfirmedWrite(receipt.serverTick(), serverKey,
                serverState, cause, context.tick()));
    }

    public void updateServerViewer(final ClientWorld world, final BlockPos pos,
                                   final BlockState viewerState) {
        final BlockKey key = key(world, pos);
        if (key == null || viewerState == null) return;
        final DirectMask existing = serverMasks.get(key);
        if (existing == null) return;
        serverMasks.put(key, new DirectMask(existing.serverState, viewerState,
                viewerState,
                existing.visualCause, existing.ownerId, existing.authoritative,
                existing.visualProvenance, existing.visualEffect,
                existing.visualRevision, existing.serverCause,
                existing.earthBlastLeaseCauses,
                existing.serverReceiptTick, existing.coalescedUntilTick,
                context.tick()));
        refreshVisual(key);
    }

    /** Returns the durable owner view for an incoming physical block state. */
    public DirectView maskForIncoming(final ClientWorld world, final BlockPos pos,
                                      final BlockState incoming) {
        final BlockKey key = key(world, pos);
        final DirectMask mask = key == null ? null : serverMasks.get(key);
        if (mask == null || incoming == null) return null;
        if (consumeSupersededPredecessor(key, incoming, false)) {
            maskedPacketCount++;
            record("tick=" + context.tick()
                    + " PACKET mask superseded-predecessor pos=" + key.pos
                    + " expected=" + mask.serverState + " incoming=" + incoming
                    + " viewer=" + mask.viewerState);
            refreshVisual(key);
            return new DirectView(mask.viewerState);
        }
        final boolean pendingCausalWrite = hasPendingConfirmed(key, incoming);
        if (!pendingCausalWrite
                && releaseCompletedConvergedMask(key, incoming)) return null;
        if (mask.serverState.equals(incoming) || pendingCausalWrite) {
            if (!pendingCausalWrite
                    && (releaseDepartedEarthBlastMask(key, incoming)
                    || releaseClosedReceiptMask(key, incoming))) return null;
            maskedPacketCount++;
            record("tick=" + context.tick() + " PACKET mask"
                    + (pendingCausalWrite && !mask.serverState.equals(incoming)
                    ? " in-flight" : "") + " pos=" + key.pos
                    + " server=" + incoming + " viewer=" + mask.viewerState
                    + " cause=" + mask.visualCause);
            refreshVisual(key);
            return new DirectView(mask.viewerState);
        }
        if (awaitsAuthoritativeFrame(key, mask)) {
            // A RaiseEarth coordinate is written more than once in one server
            // tick. Minecraft may coalesce those writes into only the final
            // chunk-delta entry, so inequality with the last receipt is not
            // evidence of an external edit. The ordered completion fence will
            // install the complete final frame.
            maskedPacketCount++;
            record("tick=" + context.tick() + " PACKET mask coalesced pos=" + key.pos
                    + " expected=" + mask.serverState + " incoming=" + incoming
                    + " viewer=" + mask.viewerState + " cause=" + mask.visualCause);
            observeCoalescedFrame(key, mask, incoming);
            refreshVisual(key);
            return new DirectView(mask.viewerState);
        }
        serverMasks.remove(key, mask);
        releaseVisual(key, mask, false);
        releasedMaskCount++;
        record("tick=" + context.tick() + " PACKET release pos=" + key.pos
                + " expected=" + mask.serverState + " incoming=" + incoming
                + " viewer=" + mask.viewerState + " cause=" + mask.visualCause);
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
        final SupersededReceipts predecessor = supersededReceipts.get(key);
        if (predecessor != null && state != null
                && state.equals(predecessor.skipNextConfirmationState)) {
            predecessor.skipNextConfirmationState = null;
            return null;
        }
        for (int index = 0; index < confirmedPackets.size(); index++) {
            final ConfirmedWrite packet = confirmedPackets.get(index);
            if (!packet.key.equals(key) || !packet.state.equals(state)) continue;
            confirmedPackets.remove(index);
            final SupersededReceipts superseded = supersededReceipts.get(key);
            if (superseded != null
                    && packet.serverTick == superseded.successorServerTick
                    && packet.cause.equals(superseded.successorCause)
                    && packet.state.equals(superseded.successorState)) {
                supersededReceipts.remove(key, superseded);
            }
            advanceConfirmedPhysicalViewer(key, state, packet.cause);
            if (!releaseCompletedConvergedMask(key, state)) {
                if (!releaseDepartedEarthBlastMask(key, state)) {
                    releaseClosedReceiptMask(key, state);
                }
            }
            return packet;
        }
        final DirectMask current = key == null ? null : serverMasks.get(key);
        advanceConfirmedPhysicalViewer(key, state,
                current == null ? null : current.serverCause);
        return null;
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
            final boolean supersededPredecessor = consumeSupersededPredecessor(
                    key, chunkState, true);
            final boolean olderConfirmationStillSuperseded = !supersededPredecessor
                    && consumeChunkConfirmations(key, chunkState,
                    mask.serverState.equals(chunkState));
            if (!supersededPredecessor && mask.serverState.equals(chunkState)) {
                advanceConfirmedPhysicalViewer(key, chunkState, mask.serverCause);
            }
            if (!supersededPredecessor && !olderConfirmationStillSuperseded
                    && releaseCompletedConvergedMask(key, chunkState)) continue;
            if (!mask.serverState.equals(chunkState)) {
                if (supersededPredecessor || olderConfirmationStillSuperseded) {
                    refreshVisual(key);
                    preserved.add(key.pos);
                    continue;
                }
                if (awaitsAuthoritativeFrame(key, mask)) {
                    observeCoalescedFrame(key, mask, chunkState);
                    refreshVisual(key);
                    preserved.add(key.pos);
                    continue;
                }
                if (hasPendingConfirmed(key)) {
                    refreshVisual(key);
                    preserved.add(key.pos);
                    continue;
                }
                serverMasks.remove(key, mask);
                releaseVisual(key, mask, false);
                debug.accept("runtime released owned earth view for external chunk state pos="
                        + key.pos + " expected=" + mask.serverState + " received=" + chunkState);
                continue;
            }
            if (releaseCompletedConvergedMask(key, chunkState)
                    || releaseDepartedEarthBlastMask(key, chunkState)
                    || releaseClosedReceiptMask(key, chunkState)) continue;
            refreshVisual(key);
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
        expireSupersededReceipts(tick);
        confirmedPackets.removeIf(packet -> tick - packet.receivedTick
                > Math.max(COALESCED_PACKET_GRACE_TICKS,
                context.confirmationTicks(packet.cause.actionSequence))
                && !retainsEarthBlastConfirmation(packet));
        retireConvergedTransactions(tick);
        for (Map.Entry<BlockKey, DirectMask> entry : List.copyOf(serverMasks.entrySet())) {
            final DirectMask mask = entry.getValue();
            final BlockState backingState = entry.getKey().world == null ? null
                    : entry.getKey().world.getBlockState(entry.getKey().pos);
            final boolean backingDiverged = backingState != null
                    && !mask.serverState.equals(backingState);
            final boolean causalPacketPending = hasPendingConfirmed(entry.getKey());
            final EarthBlastLease earthBlastLease = earthBlastLease(entry.getKey(), mask);
            if (earthBlastLease == EarthBlastLease.OPEN
                    && authoritativeCauseClosed(mask.visualCause)
                    && rebaseOpenEarthBlastLease(entry.getKey(), mask)) {
                continue;
            }
            if (!causalPacketPending && !backingDiverged
                    && (releaseCompletedConvergedMask(entry.getKey(), backingState)
                    || releaseDepartedEarthBlastMask(entry.getKey(), backingState)
                    || releaseClosedReceiptMask(entry.getKey(), backingState))) {
                continue;
            }
            if (!causalPacketPending && earthBlastLease == EarthBlastLease.CLOSED) {
                if (hasEligibleForeground(mask)
                        && pruneClosedEarthBlastLease(entry.getKey(), mask, backingState)) {
                    continue;
                }
                if (serverMasks.remove(entry.getKey(), mask)) {
                    releaseVisual(entry.getKey(), mask, false);
                }
                continue;
            }
            if (backingDiverged && !causalPacketPending
                    && earthBlastLease != EarthBlastLease.OPEN
                    && !retainsObservedCoalescedFrame(mask)) {
                if (serverMasks.remove(entry.getKey(), mask)) {
                    releaseVisual(entry.getKey(), mask, false);
                    releasedMaskCount++;
                    record("tick=" + tick + " EXPIRE_CONFLICT pos=" + entry.getKey().pos
                            + " expected=" + mask.serverState + " backing="
                            + backingState
                            + " cause=" + mask.visualCause);
                }
                continue;
            }
            final boolean converged = mask.serverState.equals(mask.viewerState);
            final boolean transactionWide = requiresAuthoritativeHandoff(
                    mask.visualCause.ability);
            final int retention = transactionWide
                    ? converged ? actionRetentionTicks : earthCauseRetentionTicks
                    : Math.max(COALESCED_PACKET_GRACE_TICKS,
                    context.confirmationTicks(mask.visualCause.actionSequence));
            if (tick - mask.updatedTick <= retention
                    || hasPendingConfirmed(entry.getKey())
                    || earthBlastLease == EarthBlastLease.OPEN
                    || retainsActiveMask(entry.getKey(), mask, transactionWide)) continue;
            if (serverMasks.remove(entry.getKey(), mask)) {
                releaseVisual(entry.getKey(), mask, false);
                releasedMaskCount++;
                record("tick=" + tick + " EXPIRE pos=" + entry.getKey().pos
                        + " server=" + mask.serverState + " viewer=" + mask.viewerState
                        + " cause=" + mask.visualCause);
            }
        }
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
        predictedCauses.entrySet().removeIf(entry -> {
            // Closed causes are ordering tombstones. Native action aliases are
            // session-lived too, so retaining these small records prevents a
            // late RevertChecker receipt from reopening prediction authority.
            if (entry.getValue().authoritativeFrameComplete
                    || entry.getValue().authoritativeClosedTick >= 0L) return false;
            return tick - entry.getValue().lastTick > earthCauseRetentionTicks
                        && !context.hasAction(entry.getKey().actionSequence)
                        && !activeEarthCauses.contains(entry.getKey())
                        && !hasMaskForCause(entry.getKey());
        });
        recentVisuals.entrySet().removeIf(entry ->
                tick - entry.getValue().createdTick > earthCauseRetentionTicks);
        while (predictedCauses.size() > 4_096) {
            final CauseKey removable = predictedCauses.entrySet().stream()
                    .filter(entry -> !entry.getValue().authoritativeFrameComplete)
                    .filter(entry -> entry.getValue().authoritativeClosedTick < 0L)
                    .map(Map.Entry::getKey)
                    .filter(cause -> !context.hasAction(cause.actionSequence))
                    .filter(cause -> !activeEarthCauses.contains(cause))
                    .filter(cause -> !hasMaskForCause(cause))
                    .findFirst().orElse(null);
            if (removable == null) break;
            predictedCauses.remove(removable);
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
        predictedWrites.entrySet().removeIf(entry ->
                entry.getKey().actionSequence == actionSequence);
        recentVisuals.entrySet().removeIf(entry ->
                entry.getValue().effect.actionSequence == actionSequence);
        for (Map.Entry<BlockKey, DirectMask> entry : List.copyOf(serverMasks.entrySet())) {
            final DirectMask mask = entry.getValue();
            if (mask.visualCause.actionSequence != actionSequence) continue;
            if (earthBlastLease(entry.getKey(), mask) == EarthBlastLease.OPEN
                    && rebaseOpenEarthBlastLease(entry.getKey(), mask, true)) continue;
            if (serverMasks.remove(entry.getKey(), mask)) {
                releaseVisual(entry.getKey(), mask, false);
            }
        }
    }

    /** Clears same-call read-through state without discarding a successful cast. */
    public void finishInput(final long actionSequence) {
        mutations.entrySet().removeIf(entry -> entry.getValue().lastAction == actionSequence);
    }

    /**
     * Records Paper's ordered end-of-ability fence without discarding direct
     * writes whose vanilla block packets are still in flight.
     */
    public int closeAuthoritativeCause(final String ability,
                                       final long exactSequence,
                                       final long acknowledgedSequence,
                                       final boolean allowAcknowledgedFallback) {
        if (ability == null || ability.isBlank()) return 0;
        final String normalized = ability.toLowerCase(Locale.ROOT);
        if (exactSequence > 0L) {
            predictedCauses.computeIfAbsent(
                    new CauseKey(exactSequence, normalized), ignored -> new PredictedCause());
        }
        int closed = 0;
        for (Map.Entry<CauseKey, PredictedCause> entry : predictedCauses.entrySet()) {
            final CauseKey cause = entry.getKey();
            final boolean exact = exactSequence > 0L
                    && cause.actionSequence == exactSequence;
            final boolean acknowledgedFallback = allowAcknowledgedFallback
                    && acknowledgedSequence > 0L
                    && cause.actionSequence <= acknowledgedSequence;
            if (!cause.ability.equals(normalized)
                    || (!exact && !acknowledgedFallback)) continue;
            final PredictedCause state = entry.getValue();
            if (state.authoritativeClosedTick < 0L) {
                state.authoritativeClosedTick = context.tick();
            }
            state.lastTick = Math.max(state.lastTick, context.tick());
            closed++;
        }
        if (closed > 0) {
            for (Map.Entry<BlockKey, DirectMask> maskEntry
                    : List.copyOf(serverMasks.entrySet())) {
                final DirectMask mask = maskEntry.getValue();
                final EarthBlastLease lease = earthBlastLease(maskEntry.getKey(), mask);
                if (lease == EarthBlastLease.CLOSED && hasEligibleForeground(mask)
                        && pruneClosedEarthBlastLease(
                        maskEntry.getKey(), mask, mask.serverState)) {
                    continue;
                }
                if (lease == EarthBlastLease.OPEN
                        && authoritativeCauseClosed(mask.visualCause)
                        && rebaseOpenEarthBlastLease(maskEntry.getKey(), mask)) {
                    continue;
                }
                final boolean closedReceiptOverlap = mask.visualProvenance
                        == DirectVisualProvenance.RECEIPT_ONLY
                        && authoritativeCauseClosed(mask.visualCause)
                        && !mask.visualCause.equals(mask.serverCause)
                        && lease != EarthBlastLease.OPEN;
                if ((lease != EarthBlastLease.CLOSED && !closedReceiptOverlap)
                        || !serverMasks.remove(maskEntry.getKey(), maskEntry.getValue())) continue;
                releaseVisual(maskEntry.getKey(), maskEntry.getValue(), false);
            }
            record("tick=" + context.tick() + " CAUSE close ability=" + normalized
                    + " exact=" + exactSequence + " acknowledged="
                    + acknowledgedSequence + " causes=" + closed);
        }
        return closed;
    }

    /**
     * Installs Paper's complete final rising frame for every acknowledged
     * transaction of one ability. This is called from the ordered final
     * RaiseEarth removal receipt, after all direct writes for the pillars have
     * been emitted.
     */
    public int completeAuthoritativeFrames(final String ability,
                                           final long exactSequence,
                                           final long acknowledgedSequence,
                                           final boolean allowAcknowledgedFallback) {
        if (ability == null || ability.isBlank()
                || exactSequence <= 0L && acknowledgedSequence <= 0L) return 0;
        final String normalized = ability.toLowerCase(Locale.ROOT);
        final boolean exactCausePreviouslyKnown = exactSequence > 0L
                && predictedCauses.containsKey(new CauseKey(exactSequence, normalized));
        if (exactSequence > 0L) {
            predictedCauses.computeIfAbsent(
                    new CauseKey(exactSequence, normalized), ignored -> new PredictedCause());
        }
        int completed = 0;
        for (Map.Entry<CauseKey, PredictedCause> causeEntry
                : List.copyOf(predictedCauses.entrySet())) {
            final CauseKey cause = causeEntry.getKey();
            final PredictedCause state = causeEntry.getValue();
            final boolean exact = exactSequence > 0L
                    && cause.actionSequence == exactSequence;
            final boolean acknowledgedFallback = !exactCausePreviouslyKnown
                    && allowAcknowledgedFallback && acknowledgedSequence > 0L
                    && cause.actionSequence <= acknowledgedSequence;
            if (!cause.ability.equals(normalized) || (!exact && !acknowledgedFallback)
                    || state.authoritativeFrameComplete) continue;

            state.authoritativeFrameComplete = true;
            state.lastTick = context.tick();
            final List<Map.Entry<BlockKey, DirectMask>> transaction = serverMasks.entrySet()
                    .stream()
                    .filter(entry -> cause.equals(entry.getValue().visualCause))
                    .toList();
            for (Map.Entry<BlockKey, DirectMask> entry : transaction) {
                final BlockKey key = entry.getKey();
                final DirectMask mask = entry.getValue();
                mutations.remove(key);
                if (!cause.equals(mask.serverCause)) {
                    if (rebaseOpenEarthBlastLease(key, mask)) {
                        completed++;
                        continue;
                    }
                    if (serverMasks.remove(key, mask)) {
                        releaseVisual(key, mask, false);
                    }
                    completed++;
                    continue;
                }
                final TempBlock tempLayer =
                        TempBlock.get(FabricPredictionMC.block(key.world, key.pos));
                if (tempLayer != null) {
                    final com.projectkorra.projectkorra.platform.mc.block.BlockState snapshot =
                            FabricPredictionMC.blockStateSnapshot(
                                    key.world, key.pos, mask.serverState);
                    if (snapshot != null) tempLayer.setState(snapshot);
                }
                final DirectMask completedMask = new DirectMask(
                        mask.serverState, mask.serverState, mask.serverState,
                        mask.visualCause, mask.ownerId,
                        true, DirectVisualProvenance.AUTHORITATIVE_COMPLETION,
                        mask.visualEffect,
                        mask.visualRevision, mask.serverCause,
                        mask.earthBlastLeaseCauses,
                        mask.serverReceiptTick, mask.coalescedUntilTick,
                        context.tick());
                serverMasks.replace(key, mask, completedMask);
                if (key.world.getBlockState(key.pos).equals(completedMask.serverState)
                        && !hasPendingConfirmed(key)
                        && serverMasks.remove(key, completedMask)) {
                    releaseVisual(key, completedMask, true);
                } else {
                    refreshVisual(key);
                }
                completed++;
            }
            predictedWrites.entrySet().removeIf(entry ->
                    entry.getKey().actionSequence == cause.actionSequence
                            && entry.getKey().ability.equals(cause.ability));
            recentVisuals.entrySet().removeIf(entry ->
                    entry.getValue().effect.actionSequence == cause.actionSequence
                            && entry.getValue().effect.ability.equals(cause.ability));
            record("tick=" + context.tick() + " FRAME complete cause=" + cause
                    + " coordinates=" + transaction.size());
            completedFrameCount++;
        }
        return completed;
    }

    /**
     * A completed local terrain frame may be the visual underlay currently
     * concealing a different, still-flying server EarthBlast. Transfer that
     * AIR pixel to the physical EarthBlast cause instead of revealing its
     * delayed solid arrival when the terrain transaction completes.
     */
    private boolean rebaseOpenEarthBlastLease(final BlockKey key,
                                              final DirectMask mask) {
        return rebaseOpenEarthBlastLease(key, mask, false);
    }

    private boolean rebaseOpenEarthBlastLease(final BlockKey key,
                                              final DirectMask mask,
                                              final boolean replaceMatchingForeground) {
        if (key == null || mask == null || mask.serverCause == null
                || earthBlastLease(key, mask) != EarthBlastLease.OPEN) return false;
        if (mask.visualCause.equals(mask.serverCause)
                && !"earthblast".equals(mask.visualCause.ability)
                && !replaceMatchingForeground) return false;
        final CauseKey leaseCause = openEarthBlastLeaseCause(mask);
        if (leaseCause == null) return false;
        if (leaseCause.equals(mask.visualCause)
                && mask.visualProvenance == DirectVisualProvenance.RECEIPT_ONLY
                && mask.viewerState.equals(mask.physicalViewerState)) return true;
        final DirectMask rebased = new DirectMask(
                mask.serverState, mask.physicalViewerState,
                mask.physicalViewerState, leaseCause,
                mask.ownerId, true, DirectVisualProvenance.RECEIPT_ONLY,
                null, 0L, mask.serverCause, mask.earthBlastLeaseCauses,
                mask.serverReceiptTick,
                mask.coalescedUntilTick, context.tick());
        if (!serverMasks.replace(key, mask, rebased)) return false;
        refreshVisual(key);
        return true;
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
                + " renderOverrides="
                + visualOverlay.size(ClientBlockVisualOverlay.Layer.DIRECT)
                + " confirmedPackets=" + confirmedPackets.size()
                + " totals={local=" + predictedWriteCount
                + ",receipts=" + receiptCount
                + ",concealed=" + concealedReceiptCount
                + ",maskedPackets=" + maskedPacketCount
                + ",releasedMasks=" + releasedMaskCount
                + ",convergedMasks=" + convergedMaskCount
                + ",completedFrames=" + completedFrameCount + "}");
        if (history.isEmpty()) {
            report.add("DirectBlock history: no causal world write was recorded");
        } else {
            report.add("DirectBlock history (oldest to newest):");
            report.addAll(history);
        }
        return List.copyOf(report);
    }

    public void clear() {
        visualOverlay.clear(ClientBlockVisualOverlay.Layer.DIRECT);
        mutations.clear();
        predictedWrites.clear();
        predictedCauses.clear();
        recentVisuals.clear();
        confirmedPackets.clear();
        supersededReceipts.clear();
        serverMasks.clear();
        history.clear();
        visualRevision = 0L;
        predictedWriteCount = 0L;
        receiptCount = 0L;
        concealedReceiptCount = 0L;
        maskedPacketCount = 0L;
        releasedMaskCount = 0L;
        convergedMaskCount = 0L;
        completedFrameCount = 0L;
    }

    private void record(final String entry) {
        if (entry == null || entry.isBlank()) return;
        history.addLast(entry);
        while (history.size() > HISTORY_LIMIT) history.removeFirst();
    }

    private void updateLocalView(final BlockKey key, final BlockState before,
                                 final BlockState viewerState, final CauseKey cause,
                                 final UUID ownerId, final EffectKey effect,
                                 final long revision) {
        if (key == null || before == null || viewerState == null
                || cause == null || ownerId == null || effect == null
                || revision <= 0L) return;
        final DirectMask existing = serverMasks.get(key);
        serverMasks.put(key, new DirectMask(
                existing == null ? before : existing.serverState,
                viewerState,
                existing == null ? before : existing.physicalViewerState,
                cause, ownerId,
                existing != null && existing.authoritative,
                DirectVisualProvenance.ACTIVE_LOCAL,
                effect, revision,
                existing == null ? null : existing.serverCause,
                existing == null ? Set.of() : existing.earthBlastLeaseCauses,
                existing == null ? Long.MIN_VALUE / 2 : existing.serverReceiptTick,
                existing == null ? 0L : existing.coalescedUntilTick,
                context.tick()));
        refreshVisual(key);
    }

    private void refreshVisual(final BlockKey key) {
        if (key == null || key.world == null || key.pos == null) return;
        final DirectMask mask = serverMasks.get(key);
        if (mask == null) {
            visualOverlay.remove(ClientBlockVisualOverlay.Layer.DIRECT,
                    key.world, key.pos);
        } else if (mask.visualProvenance.immediate()) {
            visualOverlay.setImmediateDirect(key.world, key.pos, mask.viewerState);
        } else {
            visualOverlay.set(ClientBlockVisualOverlay.Layer.DIRECT,
                    key.world, key.pos, mask.viewerState);
        }
    }

    /**
     * Removes a visual immediately unless an explicitly settled static frame
     * is pixel-identical to the physically installed backing state.
     */
    private void releaseVisual(final BlockKey key, final DirectMask removed,
                               final boolean allowSettledHandoff) {
        if (key == null || key.world == null || key.pos == null) return;
        supersededReceipts.remove(key);
        final boolean settled = allowSettledHandoff && removed != null
                && requiresAuthoritativeHandoff(removed.visualCause.ability)
                && (removed.visualProvenance
                == DirectVisualProvenance.AUTHORITATIVE_COMPLETION
                || removed.viewerState.equals(removed.serverState))
                && removed.viewerState.equals(key.world.getBlockState(key.pos));
        if (settled) {
            visualOverlay.removeDirectWithHandoff(key.world, key.pos);
        } else {
            invalidateReleasedLocalVisual(key, removed);
            visualOverlay.remove(ClientBlockVisualOverlay.Layer.DIRECT,
                    key.world, key.pos);
        }
    }

    /** An external/immediate release cannot be resurrected by an older receipt. */
    private void invalidateReleasedLocalVisual(final BlockKey key,
                                               final DirectMask removed) {
        if (key == null) return;
        mutations.remove(key);
        if (removed == null || removed.visualRevision <= 0L) return;
        predictedWrites.entrySet().removeIf(entry -> entry.getValue().key.equals(key)
                && entry.getValue().visualRevision <= removed.visualRevision);
        recentVisuals.computeIfPresent(key, (ignored, recent) ->
                recent.revision <= removed.visualRevision ? null : recent);
    }

    /**
     * Releases a completed transaction only after Paper and the common client
     * independently reached the same state at every coordinate.
     *
     * <p>This is deliberately a bookkeeping transition, not a world repaint.
     * An inferred period of inactivity is not an ordering guarantee and may
     * occur between delayed pillar updates. Only the explicit final concrete
     * RaiseEarth removal is allowed to install Paper's final rising frame.</p>
     */
    private void retireConvergedTransactions(final long tick) {
        for (Map.Entry<CauseKey, PredictedCause> causeEntry
                : List.copyOf(predictedCauses.entrySet())) {
            final CauseKey cause = causeEntry.getKey();
            final PredictedCause state = causeEntry.getValue();
            if (state.authoritativeFrameComplete
                    || !requiresAuthoritativeHandoff(cause.ability)
                    || context.hasActiveAbility(cause.actionSequence, cause.ability)) continue;

            final long lastActivity = Math.max(state.lastTick, state.lastReceiptTick);
            final int convergenceDelay = Math.max(4,
                    context.confirmationTicks(cause.actionSequence));
            if (tick - lastActivity <= convergenceDelay) continue;

            final List<Map.Entry<BlockKey, DirectMask>> transaction = serverMasks.entrySet()
                    .stream()
                    .filter(entry -> cause.equals(entry.getValue().visualCause))
                    .toList();
            if (transaction.isEmpty()) continue;

            if (transaction.stream().anyMatch(entry ->
                    hasActiveCause(entry.getValue().ownerId, cause)
                            || !entry.getValue().serverState.equals(
                            entry.getValue().viewerState)
                            || !entry.getValue().serverState.equals(
                            entry.getKey().world.getBlockState(entry.getKey().pos))
                            || hasPendingConfirmed(entry.getKey(),
                            entry.getValue().serverState))) continue;

            for (Map.Entry<BlockKey, DirectMask> entry : transaction) {
                if (!serverMasks.remove(entry.getKey(), entry.getValue())) continue;
                releaseVisual(entry.getKey(), entry.getValue(), true);
                convergedMaskCount++;
                record("tick=" + tick + " CONVERGED pos=" + entry.getKey().pos
                        + " authoritative=" + entry.getValue().authoritative
                        + " state=" + entry.getValue().viewerState + " cause=" + cause);
            }
            predictedWrites.entrySet().removeIf(entry ->
                    entry.getKey().actionSequence == cause.actionSequence
                            && entry.getKey().ability.equals(cause.ability));
            recentVisuals.entrySet().removeIf(entry ->
                    entry.getValue().effect.actionSequence == cause.actionSequence
                            && entry.getValue().effect.ability.equals(cause.ability));
        }
    }

    private static boolean requiresAuthoritativeHandoff(final String ability) {
        return DirectBlockAuthorityPolicy.requiresAuthoritativeHandoff(ability);
    }

    private boolean awaitsAuthoritativeFrame(final BlockKey key,
                                             final DirectMask mask) {
        if (key == null || mask == null || mask.serverCause == null
                || !"raiseearth".equals(mask.serverCause.ability)) return false;
        final PredictedCause state = predictedCauses.get(mask.serverCause);
        // Only the packet adjacent to a concrete receipt may be a same-tick
        // coalesced RaiseEarth write. A later mismatch is external authority
        // and must tear down the overlay even if the ability is still alive or
        // its explicit completion receipt was lost.
        return state != null && !state.authoritativeFrameComplete
                && hasPendingConfirmed(key, mask.serverState, mask.serverCause)
                && context.tick() - mask.serverReceiptTick
                <= COALESCED_PACKET_GRACE_TICKS;
    }

    /**
     * Retires the receipt identity swallowed by a coalesced chunk entry while
     * retaining the predicted pillar only until its explicit frame fence can
     * arrive. A second mismatched packet is therefore immediately external.
     */
    private void observeCoalescedFrame(final BlockKey key, final DirectMask mask,
                                      final BlockState observedState) {
        if (key == null || mask == null || observedState == null) return;
        confirmedPackets.removeIf(packet -> packet.key.equals(key)
                && mask.serverCause != null
                && packet.cause.equals(mask.serverCause));
        final long untilTick = mask.serverReceiptTick + COALESCED_PACKET_GRACE_TICKS;
        serverMasks.replace(key, mask, new DirectMask(
                observedState, mask.viewerState, mask.physicalViewerState,
                mask.visualCause, mask.ownerId,
                mask.authoritative, mask.visualProvenance, mask.visualEffect,
                mask.visualRevision, mask.serverCause,
                mask.earthBlastLeaseCauses, mask.serverReceiptTick,
                untilTick, mask.updatedTick));
    }

    private boolean retainsObservedCoalescedFrame(final DirectMask mask) {
        return mask != null && mask.coalescedUntilTick > 0L
                && context.tick() <= mask.coalescedUntilTick;
    }

    private boolean authoritativeFrameComplete(final CauseKey cause) {
        final PredictedCause state = cause == null ? null : predictedCauses.get(cause);
        return state != null && state.authoritativeFrameComplete;
    }

    private boolean authoritativeCauseClosed(final CauseKey cause) {
        final PredictedCause state = cause == null ? null : predictedCauses.get(cause);
        return state != null && state.authoritativeClosedTick >= 0L;
    }

    /** A pre-write receipt proves this physical state is causal, not external. */
    private boolean hasPendingConfirmed(final BlockKey key, final BlockState state) {
        if (key == null || state == null) return false;
        return confirmedPackets.stream().anyMatch(packet ->
                packet.key.equals(key) && packet.state.equals(state));
    }

    private boolean hasPendingConfirmed(final BlockKey key, final BlockState state,
                                        final CauseKey cause) {
        if (key == null || state == null || cause == null) return false;
        return confirmedPackets.stream().anyMatch(packet -> packet.key.equals(key)
                && packet.state.equals(state) && packet.cause.equals(cause));
    }

    private boolean hasPendingConfirmed(final BlockKey key) {
        if (key == null) return false;
        return confirmedPackets.stream().anyMatch(packet -> packet.key.equals(key));
    }

    private void rememberSupersededReceipts(final long serverTick,
                                            final BlockKey key,
                                            final CauseKey successorCause,
                                            final BlockState successorState) {
        if (key == null || successorCause == null || successorState == null) return;
        final List<ConfirmedWrite> writes = new ArrayList<>();
        confirmedPackets.removeIf(packet -> {
            final boolean superseded = packet.serverTick == serverTick
                    && packet.key.equals(key);
            if (superseded) writes.add(packet);
            return superseded;
        });
        final SupersededReceipts previous = supersededReceipts.get(key);
        if (previous != null && previous.successorServerTick != serverTick) {
            supersededReceipts.remove(key, previous);
        }
        if (writes.isEmpty()) return;
        final long tick = context.tick();
        final SupersededReceipts receipts = supersededReceipts.computeIfAbsent(
                key, ignored -> new SupersededReceipts());
        if (tick > Math.max(receipts.receiptUntilTick,
                receipts.successorDeadlineTick)) {
            receipts.predecessors.clear();
            receipts.installedPredecessor = null;
            receipts.skipNextConfirmationState = null;
            receipts.successorDeadlineTick = -1L;
        }
        receipts.predecessors.addAll(writes);
        while (receipts.predecessors.size() > 16) {
            receipts.predecessors.remove(0);
        }
        receipts.successorCause = successorCause;
        receipts.successorState = successorState;
        receipts.successorServerTick = serverTick;
        final int confirmationTicks = Math.max(COALESCED_PACKET_GRACE_TICKS,
                context.confirmationTicks(successorCause.actionSequence));
        receipts.receiptUntilTick = tick + confirmationTicks;
        if (receipts.successorDeadlineTick >= 0L) {
            receipts.successorDeadlineTick = tick + confirmationTicks;
        }
        while (supersededReceipts.size() > 4_096) {
            supersededReceipts.remove(supersededReceipts.keySet().iterator().next());
        }
    }

    private boolean consumeSupersededPredecessor(final BlockKey key,
                                                 final BlockState incoming,
                                                 final boolean maySkipPredecessors) {
        if (key == null || incoming == null) return false;
        final SupersededReceipts receipts = supersededReceipts.get(key);
        if (receipts == null || context.tick() > receipts.receiptUntilTick) return false;
        int matched = -1;
        if (maySkipPredecessors) {
            // A chunk snapshot is the newest coalesced occurrence. Walking
            // backward preserves the right cause when states alias (X-Y-X).
            for (int index = receipts.predecessors.size() - 1;
                 index >= 0; index--) {
                if (receipts.predecessors.get(index).state.equals(incoming)) {
                    matched = index;
                    break;
                }
            }
        } else if (!receipts.predecessors.isEmpty()
                && receipts.predecessors.get(0).state.equals(incoming)) {
            // Individual vanilla updates are ordered and consume only FIFO.
            matched = 0;
        }
        if (matched < 0) return false;
        receipts.installedPredecessor = receipts.predecessors.get(matched);
        receipts.predecessors.subList(0, matched + 1).clear();
        if (!maySkipPredecessors) receipts.skipNextConfirmationState = incoming;
        receipts.successorDeadlineTick = context.tick()
                + Math.max(COALESCED_PACKET_GRACE_TICKS,
                context.confirmationTicks(receipts.successorCause.actionSequence));
        return true;
    }

    /** A consumed predecessor cannot make a missing successor authoritative forever. */
    private void expireSupersededReceipts(final long tick) {
        for (Map.Entry<BlockKey, SupersededReceipts> entry
                : List.copyOf(supersededReceipts.entrySet())) {
            final BlockKey key = entry.getKey();
            final SupersededReceipts receipts = entry.getValue();
            if (receipts.successorDeadlineTick < 0L) {
                if (tick > receipts.receiptUntilTick) {
                    supersededReceipts.remove(key, receipts);
                }
                continue;
            }
            if (tick <= receipts.successorDeadlineTick) continue;
            confirmedPackets.removeIf(packet -> packet.key.equals(key)
                    && packet.serverTick == receipts.successorServerTick
                    && packet.cause.equals(receipts.successorCause)
                    && packet.state.equals(receipts.successorState));
            final DirectMask mask = serverMasks.get(key);
            final BlockState backingState = key.world == null
                    ? null : key.world.getBlockState(key.pos);
            if (mask != null && backingState != null
                    && receipts.successorCause.equals(mask.serverCause)
                    && receipts.successorState.equals(mask.serverState)
                    && !mask.serverState.equals(backingState)) {
                if (!rebaseConsumedPredecessor(
                        key, mask, receipts, backingState, tick)
                        && serverMasks.remove(key, mask)) {
                    releaseVisual(key, mask, false);
                    releasedMaskCount++;
                    record("tick=" + tick
                            + " SUPERSEDED successor-timeout release pos=" + key.pos
                            + " expected=" + mask.serverState
                            + " backing=" + backingState);
                }
            }
            supersededReceipts.remove(key, receipts);
        }
    }

    /**
     * The successor never became physical, so restore the identity of the
     * predecessor that did. A live foreground remains visual-only; an open
     * EarthBlast predecessor keeps its non-colliding underlay instead of
     * exposing a stationary server temp block.
     */
    private boolean rebaseConsumedPredecessor(final BlockKey key,
                                              final DirectMask mask,
                                              final SupersededReceipts receipts,
                                              final BlockState backingState,
                                              final long tick) {
        final ConfirmedWrite predecessor = receipts.installedPredecessor;
        final boolean exactPredecessor = predecessor != null
                && predecessor.key.equals(key)
                && predecessor.state.equals(backingState);
        final CauseKey backingCause = exactPredecessor ? predecessor.cause : null;
        final Set<CauseKey> leases = new HashSet<>();
        for (CauseKey leaseCause : mask.earthBlastLeaseCauses) {
            if (!leaseCause.equals(receipts.successorCause)
                    && earthBlastCauseLease(leaseCause) == EarthBlastLease.OPEN) {
                leases.add(leaseCause);
            }
        }
        final boolean openEarthBlastPredecessor = backingCause != null
                && "earthblast".equals(backingCause.ability)
                && earthBlastCauseLease(backingCause) == EarthBlastLease.OPEN
                && !backingState.getCollisionShape(key.world, key.pos).isEmpty();
        if (openEarthBlastPredecessor) leases.add(backingCause);

        final boolean completedForeground = mask.visualProvenance
                == DirectVisualProvenance.AUTHORITATIVE_COMPLETION
                && authoritativeFrameComplete(mask.visualCause);
        final boolean independentForeground = completedForeground
                || hasEligibleForeground(mask)
                && (mask.visualProvenance != DirectVisualProvenance.RECEIPT_ONLY
                || !receipts.successorCause.equals(mask.visualCause));
        if (!independentForeground && !openEarthBlastPredecessor) return false;

        final BlockState physicalViewerState = openEarthBlastPredecessor
                && mask.physicalViewerState.getCollisionShape(key.world, key.pos).isEmpty()
                ? mask.physicalViewerState : backingState;
        final CauseKey visualCause = independentForeground
                ? mask.visualCause : backingCause;
        final DirectMask rebased = new DirectMask(
                backingState,
                independentForeground ? mask.viewerState : physicalViewerState,
                physicalViewerState, visualCause, mask.ownerId, mask.authoritative,
                independentForeground ? mask.visualProvenance
                        : DirectVisualProvenance.RECEIPT_ONLY,
                independentForeground ? mask.visualEffect : null,
                independentForeground ? mask.visualRevision : 0L,
                backingCause, leases,
                exactPredecessor ? predecessor.receivedTick : mask.serverReceiptTick,
                0L, tick);
        if (!serverMasks.replace(key, mask, rebased)) return false;
        refreshVisual(key);
        record("tick=" + tick
                + " SUPERSEDED successor-timeout rebase pos=" + key.pos
                + " expected=" + mask.serverState
                + " backing=" + backingState
                + " foreground=" + independentForeground
                + " earthBlastLease=" + openEarthBlastPredecessor);
        return true;
    }

    private boolean retainsEarthBlastConfirmation(final ConfirmedWrite packet) {
        if (packet == null || packet.cause == null
                || !"earthblast".equals(packet.cause.ability)) return false;
        final PredictedCause cause = predictedCauses.get(packet.cause);
        if (cause == null) return false;
        if (cause.authoritativeClosedTick < 0L) return true;
        return context.tick() - cause.authoritativeClosedTick
                <= Math.max(COALESCED_PACKET_GRACE_TICKS,
                context.confirmationTicks(packet.cause.actionSequence));
    }

    /**
     * A full chunk snapshot subsumes queued direct writes when it contains the
     * newest announced state. If it only contains an older in-flight state,
     * consume that identity while retaining later coordinate confirmations.
     */
    private boolean consumeChunkConfirmations(final BlockKey key,
                                              final BlockState chunkState,
                                              final boolean newestStateInstalled) {
        if (key == null || chunkState == null) return false;
        final boolean matchedOlderConfirmation = !newestStateInstalled
                && hasPendingConfirmed(key, chunkState);
        confirmedPackets.removeIf(packet -> packet.key.equals(key)
                && (newestStateInstalled || packet.state.equals(chunkState)));
        if (newestStateInstalled) supersededReceipts.remove(key);
        return matchedOlderConfirmation && hasPendingConfirmed(key);
    }

    /** Hands an exact completed static frame back to the installed terrain. */
    private boolean releaseCompletedConvergedMask(final BlockKey key,
                                                  final BlockState physicalState) {
        final DirectMask mask = key == null ? null : serverMasks.get(key);
        if (mask == null
                || mask.visualProvenance
                != DirectVisualProvenance.AUTHORITATIVE_COMPLETION
                || !authoritativeFrameComplete(mask.visualCause)
                || !mask.viewerState.equals(physicalState)
                || hasPendingConfirmed(key)
                || !serverMasks.remove(key, mask)) return false;
        releaseVisual(key, mask, true);
        return true;
    }

    /**
     * Once Paper's EarthBlast departure AIR is physically installed, a
     * receipt-only arrival visual has no remaining ownership. Removing it at
     * that exact packet prevents a saved solid viewer from becoming a trail.
     */
    private boolean releaseDepartedEarthBlastMask(final BlockKey key,
                                                  final BlockState physicalState) {
        final DirectMask mask = key == null ? null : serverMasks.get(key);
        final boolean departedReceiptOnly = mask != null && mask.serverCause != null
                && mask.visualProvenance == DirectVisualProvenance.RECEIPT_ONLY
                && "earthblast".equals(mask.serverCause.ability)
                && (mask.visualCause.equals(mask.serverCause)
                || authoritativeCauseClosed(mask.visualCause));
        final boolean retiredLocalCoordinate = mask != null
                && mask.visualProvenance == DirectVisualProvenance.ACTIVE_LOCAL
                && "earthblast".equals(mask.visualCause.ability)
                && (authoritativeCauseClosed(mask.visualCause)
                || !hasActiveEarthCoordinate(key, mask.ownerId, mask.visualCause));
        if (mask == null
                || (!departedReceiptOnly && !retiredLocalCoordinate)
                || physicalState == null || !physicalState.isAir()
                || !mask.serverState.equals(physicalState)
                || hasPendingConfirmed(key)
                || !serverMasks.remove(key, mask)) return false;
        releaseVisual(key, mask, false);
        return true;
    }

    /** A closed visual cannot conceal a later different-cause physical write. */
    private boolean releaseClosedReceiptMask(final BlockKey key,
                                             final BlockState physicalState) {
        final DirectMask mask = key == null ? null : serverMasks.get(key);
        final EarthBlastLease lease = earthBlastLease(key, mask);
        final boolean closedReceipt = mask != null
                && mask.visualProvenance == DirectVisualProvenance.RECEIPT_ONLY
                && authoritativeCauseClosed(mask.visualCause);
        if (mask == null || lease == EarthBlastLease.OPEN
                || (lease != EarthBlastLease.CLOSED && !closedReceipt)
                || physicalState == null || !mask.serverState.equals(physicalState)
                || hasPendingConfirmed(key)) return false;
        if (lease == EarthBlastLease.CLOSED && hasEligibleForeground(mask)
                && pruneClosedEarthBlastLease(key, mask, physicalState)) return false;
        if (!serverMasks.remove(key, mask)) return false;
        releaseVisual(key, mask, false);
        return true;
    }

    /** Advances only the hidden underlay after the exact current write installs. */
    private void advanceConfirmedPhysicalViewer(final BlockKey key,
                                                final BlockState physicalState,
                                                final CauseKey physicalCause) {
        final DirectMask mask = key == null ? null : serverMasks.get(key);
        if (mask == null || physicalState == null || physicalCause == null
                || !physicalCause.equals(mask.serverCause)
                || !physicalState.equals(mask.serverState)
                || earthBlastLease(key, mask) != EarthBlastLease.NONE
                || physicalState.equals(mask.physicalViewerState)) return;
        final DirectMask advanced = new DirectMask(
                mask.serverState, mask.viewerState, physicalState,
                mask.visualCause, mask.ownerId, mask.authoritative,
                mask.visualProvenance, mask.visualEffect, mask.visualRevision,
                mask.serverCause, mask.earthBlastLeaseCauses,
                mask.serverReceiptTick, mask.coalescedUntilTick,
                mask.updatedTick);
        serverMasks.replace(key, mask, advanced);
    }

    private boolean pruneClosedEarthBlastLease(final BlockKey key,
                                               final DirectMask mask,
                                               final BlockState physicalState) {
        if (key == null || mask == null || physicalState == null) return false;
        final Set<CauseKey> openLeases = mask.earthBlastLeaseCauses.stream()
                .filter(cause -> earthBlastCauseLease(cause) == EarthBlastLease.OPEN)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        final DirectMask pruned = new DirectMask(
                mask.serverState, mask.viewerState, physicalState,
                mask.visualCause, mask.ownerId, mask.authoritative,
                mask.visualProvenance, mask.visualEffect, mask.visualRevision,
                mask.serverCause, openLeases, mask.serverReceiptTick,
                mask.coalescedUntilTick, mask.updatedTick);
        if (!serverMasks.replace(key, mask, pruned)) return false;
        refreshVisual(key);
        return true;
    }

    private boolean hasEligibleForeground(final DirectMask mask) {
        return mask != null && !authoritativeCauseClosed(mask.visualCause);
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

    private boolean retainsActiveMask(final BlockKey key, final DirectMask mask,
                                      final boolean transactionWide) {
        if (key == null || mask == null) return false;
        if (transactionWide) return hasActiveCause(mask.ownerId, mask.visualCause);
        return !authoritativeCauseClosed(mask.visualCause)
                && hasActiveEarthCoordinate(key, mask.ownerId, mask.visualCause);
    }

    private Set<CauseKey> receiptEarthBlastLeaseCauses(
            final BlockKey key, final DirectMask existingMask,
            final CauseKey incomingCause, final BlockState incomingState,
            final boolean carryExistingLeases) {
        final Set<CauseKey> leases = new HashSet<>();
        if (carryExistingLeases && existingMask != null) {
            for (CauseKey leaseCause : existingMask.earthBlastLeaseCauses) {
                if (earthBlastCauseLease(leaseCause) == EarthBlastLease.OPEN) {
                    leases.add(leaseCause);
                }
            }
        }
        if (incomingCause != null && "earthblast".equals(incomingCause.ability)) {
            final boolean collidingArrival = key != null && key.world != null
                    && incomingState != null
                    && !incomingState.getCollisionShape(key.world, key.pos).isEmpty();
            if (collidingArrival) leases.add(incomingCause);
            else leases.remove(incomingCause);
        }
        return Set.copyOf(leases);
    }

    private EarthBlastLease earthBlastLease(final BlockKey key, final DirectMask mask) {
        if (!hidesServerOnlyEarthBlast(key, mask)) return EarthBlastLease.NONE;
        boolean tracked = false;
        for (CauseKey leaseCause : mask.earthBlastLeaseCauses) {
            final EarthBlastLease lease = earthBlastCauseLease(leaseCause);
            if (lease == EarthBlastLease.OPEN) return EarthBlastLease.OPEN;
            tracked |= lease == EarthBlastLease.CLOSED;
        }
        return tracked ? EarthBlastLease.CLOSED : EarthBlastLease.NONE;
    }

    private EarthBlastLease earthBlastCauseLease(final CauseKey cause) {
        if (cause == null || !"earthblast".equals(cause.ability)) {
            return EarthBlastLease.NONE;
        }
        final PredictedCause server = predictedCauses.get(cause);
        if (server == null) return EarthBlastLease.CLOSED;
        if (server.authoritativeClosedTick < 0L) return EarthBlastLease.OPEN;
        return context.tick() - server.authoritativeClosedTick
                <= Math.max(COALESCED_PACKET_GRACE_TICKS,
                context.confirmationTicks(cause.actionSequence))
                ? EarthBlastLease.OPEN : EarthBlastLease.CLOSED;
    }

    private CauseKey openEarthBlastLeaseCause(final DirectMask mask) {
        if (mask == null) return null;
        if (mask.serverCause != null
                && mask.earthBlastLeaseCauses.contains(mask.serverCause)
                && earthBlastCauseLease(mask.serverCause) == EarthBlastLease.OPEN) {
            return mask.serverCause;
        }
        return mask.earthBlastLeaseCauses.stream()
                .filter(cause -> earthBlastCauseLease(cause) == EarthBlastLease.OPEN)
                .findFirst().orElse(null);
    }

    private static boolean hidesServerOnlyEarthBlast(final BlockKey key,
                                                      final DirectMask mask) {
        return key != null && key.world != null && mask != null
                && !mask.earthBlastLeaseCauses.isEmpty()
                && mask.physicalViewerState.getCollisionShape(key.world, key.pos).isEmpty()
                && !mask.serverState.getCollisionShape(key.world, key.pos).isEmpty();
    }

    private boolean hasMaskForCause(final CauseKey cause) {
        return cause != null && serverMasks.values().stream().anyMatch(mask ->
                cause.equals(mask.visualCause) || cause.equals(mask.serverCause)
                        || mask.earthBlastLeaseCauses.contains(cause));
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

    private boolean matchesEarthLifecycle(final Information information,
                                          final UUID ownerId,
                                          final CauseKey cause) {
        return information != null && ownerId.equals(information.getPredictionOwner())
                && information.getPredictionAbility() != null
                && information.getPredictionAbility().equalsIgnoreCase(cause.ability)
                && (information.getPredictionActionSequence() == cause.actionSequence
                || context.sameActiveAbilityLifecycle(
                cause.actionSequence,
                information.getPredictionActionSequence(), cause.ability));
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

    /** Opaque pending receipt consumed after vanilla installs its physical state. */
    public static final class ConfirmedWrite {
        private final long serverTick;
        private final BlockKey key;
        private final BlockState state;
        private final CauseKey cause;
        private final long receivedTick;

        private ConfirmedWrite(final long serverTick, final BlockKey key,
                               final BlockState state, final CauseKey cause,
                               final long receivedTick) {
            this.serverTick = serverTick;
            this.key = key;
            this.state = state;
            this.cause = cause;
            this.receivedTick = receivedTick;
        }
    }

    private static final class SupersededReceipts {
        private final List<ConfirmedWrite> predecessors = new ArrayList<>();
        private ConfirmedWrite installedPredecessor;
        private CauseKey successorCause;
        private BlockState successorState;
        private BlockState skipNextConfirmationState;
        private long successorServerTick = Long.MIN_VALUE;
        private long receiptUntilTick = -1L;
        private long successorDeadlineTick = -1L;
    }

    private record BlockKey(ClientWorld world, BlockPos pos) { }
    private record CauseKey(long actionSequence, String ability) { }
    private record EffectKey(long actionSequence, String ability, int mutationOrdinal) { }
    private static final class PredictedCause {
        private int lastOrdinal;
        private long lastTick;
        private long lastReceiptTick = Long.MIN_VALUE / 2;
        private long authoritativeClosedTick = -1L;
        private boolean authoritativeFrameComplete;
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
    private enum DirectVisualProvenance {
        RECEIPT_ONLY(false, false),
        ACTIVE_LOCAL(true, true),
        AUTHORITATIVE_COMPLETION(false, true);

        private final boolean locallyPredicted;
        private final boolean immediate;

        DirectVisualProvenance(final boolean locallyPredicted,
                               final boolean immediate) {
            this.locallyPredicted = locallyPredicted;
            this.immediate = immediate;
        }

        private boolean locallyPredicted() {
            return locallyPredicted;
        }

        private boolean immediate() {
            return immediate;
        }
    }
    private enum EarthBlastLease {
        NONE,
        OPEN,
        CLOSED
    }
    private record DirectMask(BlockState serverState, BlockState viewerState,
                              BlockState physicalViewerState,
                              CauseKey visualCause, UUID ownerId, boolean authoritative,
                              DirectVisualProvenance visualProvenance,
                              EffectKey visualEffect,
                              long visualRevision, CauseKey serverCause,
                              Set<CauseKey> earthBlastLeaseCauses,
                              long serverReceiptTick, long coalescedUntilTick,
                              long updatedTick) {
        private DirectMask {
            earthBlastLeaseCauses = earthBlastLeaseCauses == null
                    ? Set.of() : Set.copyOf(earthBlastLeaseCauses);
        }
    }
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
