package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards latency ordering for moving direct-earth visuals. */
class MovingEarthVisualLifecycleBoundaryTest {
    @Test
    void preWriteReceiptCannotExposeItsStillInFlightPhysicalBlock() throws IOException {
        final String direct = directAuthority();
        final String receipt = method(direct, "public void noteReceipt(",
                "public void updateServerViewer");
        final String incoming = method(direct, "public DirectView maskForIncoming(",
                "public BlockState viewerState");
        final String consume = method(direct, "public ConfirmedWrite takeConfirmed(",
                "public void confirmFromVanilla");
        final String expiry = method(direct, "public void expire(",
                "public void rollbackAction");
        final String chunk = method(direct, "public Set<BlockPos> restoreChunk(",
                "public void clearTransientReads");
        final String temp = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        final String packetObserver = method(temp, "public boolean acceptBlock(",
                "/** Observes a vanilla chunk delta");
        final String network = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ClientPlayNetworkHandlerPredictionMixin.java");

        final int maskInstall = receipt.indexOf("serverMasks.put(serverKey");
        final int confirmationInstall = receipt.indexOf(
                "confirmedPackets.add(new ConfirmedWrite");
        assertTrue(maskInstall >= 0 && confirmationInstall > maskInstall,
                "the visual mask and pending physical identity must be recorded as one pre-write receipt transaction");
        assertTrue(receipt.contains("rememberSupersededReceipts(")
                        && incoming.contains(
                        "consumeSupersededPredecessor(key, incoming, false)")
                        && incoming.contains("PACKET mask superseded-predecessor")
                        && expiry.contains("expireSupersededReceipts(tick)")
                        && direct.contains("successorDeadlineTick")
                        && direct.contains("successorServerTick")
                        && direct.contains("skipNextConfirmationState")
                        && direct.contains("installedPredecessor")
                        && direct.contains(
                        "receipts.predecessors.size() - 1")
                        && direct.contains(
                        "previous.successorServerTick != serverTick")
                        && direct.contains("rebaseConsumedPredecessor(")
                        && direct.contains("openEarthBlastPredecessor")
                        && direct.contains("completedForeground")
                        && direct.contains("advanceConfirmedPhysicalViewer(key, state, packet.cause)"),
                "same-tick predecessor packets must have exact one-shot identity and a bounded successor timeout");
        assertTrue(incoming.indexOf(
                        "consumeSupersededPredecessor(key, incoming, false)")
                        < incoming.indexOf(
                        "final boolean pendingCausalWrite = hasPendingConfirmed(key, incoming)"),
                "an aliased predecessor must be classified before the equal successor state");
        assertTrue(incoming.contains("final boolean pendingCausalWrite = hasPendingConfirmed(key, incoming)")
                        && incoming.indexOf("pendingCausalWrite")
                        < incoming.indexOf("PACKET release"),
                "an older causal vanilla state must remain concealed behind the newest local view");
        assertTrue(expiry.contains("final boolean causalPacketPending = hasPendingConfirmed(")
                        && expiry.contains("backingDiverged && !causalPacketPending")
                        && expiry.contains("context.confirmationTicks(packet.cause.actionSequence)")
                        && expiry.contains("!retainsEarthBlastConfirmation(packet)"),
                "receipt-announced future state is not a backing conflict before its vanilla packet arrives");
        assertTrue(chunk.contains("hasPendingConfirmed(key)"),
                "a chunk snapshot cannot tear down the same in-flight causal mask");
        assertTrue(network.contains("@Inject(method = \"onBlockUpdate\", at = @At(\"TAIL\"))")
                        && network.contains("@Inject(method = \"onChunkDeltaUpdate\", at = @At(\"TAIL\"))"),
                "vanilla must physically install every state before confirmation is consumed");
        final int maskObservation = packetObserver.indexOf("directBlocks.maskForIncoming(");
        final int confirmationConsumption = packetObserver.indexOf(
                "directBlocks.takeConfirmed(");
        assertTrue(maskObservation >= 0 && confirmationConsumption > maskObservation,
                "packet ownership must be checked while its pending confirmation still exists");
        final int confirmationRemoval = consume.indexOf("confirmedPackets.remove(index)");
        final int completedRelease = consume.indexOf(
                "releaseCompletedConvergedMask(key, state)");
        assertTrue(confirmationRemoval >= 0 && completedRelease > confirmationRemoval,
                "a completed static frame may hand off only after the last coordinate packet is consumed");
        assertTrue(chunk.contains("consumeChunkConfirmations(")
                        && chunk.contains("olderConfirmationStillSuperseded")
                        && chunk.contains(
                        "advanceConfirmedPhysicalViewer(key, chunkState, mask.serverCause)")
                        && chunk.contains(
                        "releaseCompletedConvergedMask(key, chunkState)"),
                "a full chunk snapshot must consume causal writes and release an exact completed frame");
    }

    @Test
    void movingEarthNeverEntersTheStaticHandoffTimer() throws IOException {
        final String direct = directAuthority();
        final String refresh = method(direct, "private void refreshVisual(",
                "private void releaseVisual(");
        final String release = method(direct, "private void releaseVisual(",
                "private void retireConvergedTransactions");
        final String incoming = method(direct, "public DirectView maskForIncoming(",
                "public BlockState viewerState");
        final String expiry = method(direct, "public void expire(",
                "public void rollbackAction");
        final String rollback = method(direct, "public void rollbackAction(",
                "public void finishInput(");

        assertTrue(refresh.contains("visualOverlay.remove(ClientBlockVisualOverlay.Layer.DIRECT")
                        && !refresh.contains("removeDirectWithHandoff"),
                "ordinary mask disappearance must remove an EarthBlast cell in the same frame");
        assertTrue(incoming.contains("releaseVisual(key, mask, false)")
                        && expiry.contains("releaseVisual(entry.getKey(), mask, false)")
                        && rollback.contains("releaseVisual(entry.getKey(), mask, false)"),
                "conflict, expiry, and rollback are moving/invalid teardown, never settled handoff");
        assertTrue(release.contains("allowSettledHandoff")
                        && release.contains(
                        "requiresAuthoritativeHandoff(removed.visualCause.ability)")
                        && release.contains(
                        "DirectVisualProvenance.AUTHORITATIVE_COMPLETION")
                        && release.contains(
                        "removed.viewerState.equals(removed.serverState)")
                        && release.contains(
                        "removed.viewerState.equals(key.world.getBlockState(key.pos))")
                        && release.contains("visualOverlay.removeDirectWithHandoff"),
                "only an explicitly settled state already present in ClientWorld may bridge to terrain");
    }

    @Test
    void coalescedRaiseEarthAndServerOnlyEarthBlastHaveExplicitExitFences()
            throws IOException {
        final String direct = directAuthority();
        final String incoming = method(direct, "public DirectView maskForIncoming(",
                "public BlockState viewerState");
        final String chunk = method(direct, "public Set<BlockPos> restoreChunk(",
                "public void clearTransientReads");
        final String expiry = method(direct, "public void expire(",
                "public void rollbackAction");
        final String coalescing = method(direct,
                "private boolean awaitsAuthoritativeFrame(",
                "private boolean authoritativeFrameComplete");
        final String departed = method(direct,
                "private boolean releaseDepartedEarthBlastMask(",
                "private BlockState clientBaseState");
        final String closeCause = method(direct,
                "public int closeAuthoritativeCause(",
                "public int completeAuthoritativeFrames(");
        final String activeLease = method(direct,
                "private boolean retainsActiveMask(",
                "private static Set<CauseKey> activeEarthCauses");
        final String runtime = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        final String removal = method(runtime,
                "private void removeAuthoritativeAbility0(",
                "static boolean retainsAcceptedPredictedLifecycle");

        assertTrue(coalescing.contains(
                        "hasPendingConfirmed(key, mask.serverState, mask.serverCause)")
                        && coalescing.contains("confirmedPackets.removeIf")
                        && coalescing.contains("packet.cause.equals(mask.serverCause)")
                        && coalescing.contains("coalescedUntilTick"),
                "only the first outstanding RaiseEarth write may use the bounded coalescing fence");
        assertTrue(incoming.contains("observeCoalescedFrame(key, mask, incoming)")
                        && chunk.contains("observeCoalescedFrame(key, mask, chunkState)")
                        && expiry.contains("retainsObservedCoalescedFrame(mask)"),
                "block, chunk, and expiry reconciliation must share the same coalesced-write state");
        assertTrue(coalescing.contains("observedState, mask.viewerState")
                        && coalescing.contains("confirmedPackets.removeIf"),
                "RaiseEarth completion must promote the physical state actually observed in the coalesced packet");
        assertTrue(activeLease.contains("hidesServerOnlyEarthBlast(key, mask)")
                        && activeLease.contains("mask.earthBlastLeaseCauses")
                        && activeLease.contains("mask.physicalViewerState")
                        && activeLease.contains("predictedCauses.get(cause)")
                        && activeLease.contains("authoritativeClosedTick")
                        && activeLease.contains("context.confirmationTicks(")
                        && activeLease.contains(
                        "!authoritativeCauseClosed(mask.visualCause)")
                        && activeLease.contains("openEarthBlastLeaseCause"),
                "a divergent server-only EarthBlast arrival is hidden until its bounded server cause closes");
        assertTrue(expiry.contains(
                        "earthBlastLease != EarthBlastLease.OPEN")
                        && expiry.indexOf("earthBlastLease != EarthBlastLease.OPEN")
                        < expiry.indexOf("EXPIRE_CONFLICT"),
                "an open server-only EarthBlast lease must survive a vanilla packet delayed beyond the RTT estimate");
        assertTrue(removal.contains("directBlockAuthority.closeAuthoritativeCause(")
                        && removal.indexOf("directBlockAuthority.closeAuthoritativeCause(")
                        < removal.indexOf("if (removed.actionSequence() > 0L)"),
                "the ordered server close fence must be recorded before local removal correlation can return early");
        assertTrue(removal.contains("sharedDirectCauseStillActive")
                        && removal.contains("removed.remainingActionInstances() > 0")
                        && removal.contains("sharedDirectCauseStillActive ? 0"),
                "an orchestrator or early child must not close a same-input/name cause while any composite sibling is active");
        assertTrue(closeCause.contains("cause.actionSequence == exactSequence")
                        && closeCause.contains("allowAcknowledgedFallback")
                        && closeCause.contains(
                        "cause.actionSequence <= acknowledgedSequence")
                        && closeCause.contains("predictedCauses.computeIfAbsent(")
                        && closeCause.contains("state.authoritativeClosedTick"),
                "cause closure must retain the exact tombstone and sweep acknowledged transition causes only after the authoritative name is empty");
        assertTrue(departed.contains("physicalState.isAir()")
                        && departed.contains("hasPendingConfirmed(key)")
                        && departed.contains("mask.visualCause.equals(mask.serverCause)")
                        && departed.contains("retiredLocalCoordinate")
                        && departed.contains("authoritativeCauseClosed(mask.visualCause)")
                        && departed.contains("!hasActiveEarthCoordinate(")
                        && departed.contains("serverMasks.remove(key, mask)")
                        && departed.contains("releaseVisual(key, mask, false)")
                        && direct.contains("context.sameActiveAbilityLifecycle(")
                        && runtime.contains(
                        "public boolean sameActiveAbilityLifecycle(")
                        && runtime.contains("abilityCreationActions.getOrDefault("),
                "the exact EarthBlast departure AIR packet may retire only a genuinely departed coordinate, including transition-to-creation action aliases");
    }

    @Test
    void newestCoordinateRevisionAndSuccessfulCastSurviveDelayedReceipts()
            throws IOException {
        final String direct = directAuthority();
        final String receipt = method(direct, "public void noteReceipt(",
                "public void updateServerViewer");
        final String finishInput = method(direct, "public void finishInput(",
                "public int closeAuthoritativeCause(");
        final String rollback = method(direct, "public void rollbackAction(",
                "public void finishInput(");
        final String completion = method(direct, "public int completeAuthoritativeFrames(",
                "public int mutationCount()");
        final String runtime = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        final String input = method(runtime, "private boolean input0(",
                "private void associateAbility");
        final String failed = method(runtime, "private void abortFailedLocalInput(",
                "private int blockConfirmationTicks");

        assertTrue(direct.contains("DirectVisualOrderPolicy.select(")
                        && direct.contains("EffectKey visualEffect")
                        && direct.contains("long visualRevision")
                        && receipt.contains("final boolean retainObserved")
                        && receipt.contains("retainObserved ? observedVisual.state")
                        && receipt.contains("existingMask.physicalViewerState")
                        && receipt.contains("carryExistingEarthBlastState")
                        && receipt.contains("receiptEarthBlastLeaseCauses("),
                "a delayed solid receipt cannot replace a newer same-coordinate AIR revision");
        assertTrue(input.contains("this.directBlockAuthority.finishInput(sequence)")
                        && !input.contains("this.directBlockAuthority.rollbackAction(sequence)"),
                "successful synchronous Earth writes must remain available for exact receipt matching");
        assertTrue(finishInput.contains("mutations.entrySet().removeIf")
                        && !finishInput.contains("predictedWrites")
                        && !finishInput.contains("recentVisuals")
                        && !finishInput.contains("serverMasks"),
                "successful input cleanup may discard read-through cache only");
        assertTrue(rollback.contains("earthBlastLease(entry.getKey(), mask)")
                        && rollback.contains(
                        "rebaseOpenEarthBlastLease(entry.getKey(), mask, true)"),
                "a failed overlapping input must hand an open physical EarthBlast lease back to its saved underlay");
        final int failureGuard = input.indexOf("if (failed)");
        final int failureRollback = input.indexOf("this.abortFailedLocalInput(action)");
        assertTrue(failureGuard >= 0 && failureRollback > failureGuard
                        && failed.contains(
                        "this.directBlockAuthority.rollbackAction(action.sequence)"),
                "a genuine failed local input must still perform the full rollback");
        assertTrue(completion.contains("mask.serverState, mask.serverState")
                        && completion.contains("cause.equals(mask.serverCause)")
                        && completion.contains("rebaseOpenEarthBlastLease(key, mask)")
                        && completion.contains("EarthBlastLease.OPEN")
                        && completion.contains("DirectVisualProvenance.RECEIPT_ONLY")
                        && completion.contains("DirectVisualProvenance.AUTHORITATIVE_COMPLETION")
                        && completion.contains("mask.coalescedUntilTick")
                        && completion.contains("!hasPendingConfirmed(key)")
                        && completion.contains("refreshVisual(key)"),
                "RaiseEarth's final server frame remains immediate while its physical terrain packet catches up");
        final int completedCauseGate = receipt.indexOf(
                "causeState.authoritativeFrameComplete");
        final int overlappedMaskInstall = receipt.indexOf("serverMasks.put(serverKey");
        assertTrue(receipt.contains("causeState.authoritativeClosedTick")
                        && receipt.contains("&& !overlapsProtectedMask")
                        && completedCauseGate >= 0
                        && overlappedMaskInstall > completedCauseGate,
                "a completed restore must still fence its physical packet when a newer ability owns the visual");
    }

    private static String directAuthority() throws IOException {
        return source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientDirectBlockAuthority.java");
    }

    private static String source(final String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path)) path = Path.of("fabric").resolve(relative);
        assertTrue(Files.exists(path), "missing source: " + path);
        return Files.readString(path);
    }

    private static String method(final String source, final String start, final String end) {
        final int from = source.indexOf(start);
        final int to = source.indexOf(end, from);
        assertTrue(from >= 0 && to > from, "missing boundary " + start + " -> " + end);
        return source.substring(from, to);
    }
}
