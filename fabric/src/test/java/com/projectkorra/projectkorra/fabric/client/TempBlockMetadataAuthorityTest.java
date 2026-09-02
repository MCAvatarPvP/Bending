package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards authenticated visual concealment and exact lifecycle reconciliation. */
class TempBlockMetadataAuthorityTest {
    @Test
    void ownerMetadataNeedsABoundedLocalActionLeaseAcrossCoordinateDrift()
            throws IOException {
        final String authority = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        final String handler = method(authority, "private void applyAuthoritativeOperations",
                "private boolean advanceStream");

        assertFalse(authority.contains("setBlockState("),
                "metadata may alter render overlays but never authoritative ClientWorld storage");
        assertTrue(authority.contains("serverLayers.overlayState(key, player.getUuid())"));
        assertFalse(handler.contains("world.getBlockState(pos).equals"),
                "concealment must not depend on a fragile exact physical-state receipt");
        assertFalse(handler.contains("blockEchoes.add("));
        assertTrue(handler.contains("operation.effectAbility()")
                        && handler.contains("operation.effectStep()")
                        && handler.contains("operation.effectOrdinal()"));
        assertTrue(authority.contains("pairedCoordinates.computeIfAbsent(server.key")
                        && authority.contains("shifted="));
        assertTrue(authority.contains("authoritativeEffects.get(local.effect)")
                         && authority.contains("localEffects.get(server.effect)"),
                "pairing must use the exact causal identity in both arrival orders");
        assertFalse(authority.contains("closestUnpairedEarthSmashServer")
                        || authority.contains("closestUnpairedEarthSmashLocal")
                        || authority.contains("sameEarthSmashSlot"),
                "generic TempBlock reconciliation must not guess an ownership transfer by ability or frame proximity");
        String concealment = authority.substring(authority.indexOf("private boolean hidesServerLayer"),
                authority.indexOf("private void indexAuthoritative"));
        assertTrue(concealment.contains(
                        "serverLayers.hidesServerWorld(key, player.getUuid())")
                        && concealment.contains("authoritativeByCoordinate.get(key)")
                        && concealment.contains(
                        "!hasLocalActionConcealment(server.actionSequence)")
                        && concealment.contains("return foundOwnedLayer"),
                "ownership and a mapped local action lease must both be proven without coordinate pairing");
        assertTrue(concealment.contains(
                        "findActiveLayer(entry.getKey()) != null")
                        && concealment.contains("!closedPairGraceExpired(local)")
                        && concealment.contains("actionSequence <= 0L"),
                "concealment must fail open unless that mapped action is active or inside its bounded close grace");
        assertFalse(concealment.contains("hasSemanticPair(key)"),
                "visual concealment must not wait for an equal-coordinate semantic pair");
        assertTrue(authority.contains("reconcileActionConcealment(local.actionSequence)")
                        && authority.contains("releasedConcealment.add(local.actionSequence)")
                        && concealment.contains("if (changed) refreshAuthoritativeForAction"),
                "opening, detaching, or expiring a local lease must repaint every drifted Paper coordinate for that action");
        assertFalse(authority.contains("findLocalTempBlockCandidate")
                        || authority.contains("MAX_TEMP_BLOCK_STEP_SKEW"),
                "nearest-tick/coordinate inference can cross-wire rapid overlapping layers");
        assertTrue(authority.contains("!change.ability().tracksPredictedTempBlocks()"),
                "a transfer preview must be excluded before it can reserve an authoritative ordinal");
        assertTrue(authority.contains("final boolean stableEarthSmashSlot")
                        && authority.contains("repaint(server.key")
                        && authority.contains("rebaseUnderlay(local.key, composedUnderlay(local.key, viewer))"),
                "an exact EarthSmash frame/slot must reconcile immediately without nearest-frame guessing");
        assertTrue(authority.contains("common-client lifecycle")
                        && authority.contains("local.serverClosed = true"),
                "semantic pairing still reconciles the same lifecycle without controlling concealment");
    }

    @Test
    void snapshotsAreFramedStagedAndPruneAbsentLayersOnlyOnCommit() throws IOException {
        final String authority = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        final String payloads = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/prediction/protocol/PredictionPayloads.java");
        final String client = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        final String ledger = source(
                "../common/src/main/java/com/projectkorra/projectkorra/prediction/block/ClientTempBlockLedger.java");
        final String staging = method(authority, "public BatchResult applyAuthoritativeBatch",
                "private void applyAuthoritativeOperations");
        final String pruning = method(authority, "private void pruneAbsentAuthoritativeLayers",
                "/** Runs an authoritative ability removal");
        final String invalidation = method(authority, "private BatchResult requireAuthoritativeResync",
                "/** Commits snapshot membership");

        assertTrue(payloads.contains("boolean snapshot, long streamSequence")
                        && payloads.contains("long snapshotId, int snapshotIndex, int snapshotParts")
                        && payloads.contains("buf.writeVarLong(snapshotId)")
                        && payloads.contains("buf.writeVarInt(snapshotIndex)"),
                "every snapshot fragment needs an ordered stream position and explicit frame coordinates");
        assertTrue(staging.contains("new SnapshotAssembly(batch.snapshotId(), batch.snapshotParts())")
                        && staging.contains("stagedSnapshot.operations.addAll")
                        && staging.contains("return BatchResult.STAGED")
                        && staging.indexOf("applyAuthoritativeOperations(world, batch, committed.operations)")
                        < staging.indexOf("pruneAbsentAuthoritativeLayers(world, committed.operations)"),
                "fragments must be staged and applied atomically before membership is pruned");
        assertTrue(staging.contains("batch.snapshotIndex() >= batch.snapshotParts()")
                        && staging.contains("stagedSnapshot.nextIndex != batch.snapshotIndex()")
                        && staging.contains("advanceStream(batch.streamSequence(), false)"),
                "invalid framing and sequence gaps must request a fresh authoritative snapshot");
        assertTrue(pruning.contains("serverLayers.pruneAbsentFromSnapshot(snapshotLayers)")
                        && pruning.contains("removeAuthoritative(layerId)")
                        && ledger.contains("Set<K> pruneAbsentFromSnapshot"),
                "a complete snapshot must retire every stale layer omitted by Paper");
        assertTrue(client.contains("result == ClientTempBlockAuthority.BatchResult.APPLIED")
                        && client.contains("result == ClientTempBlockAuthority.BatchResult.RESYNC_REQUIRED")
                        && client.contains("requestWorldTempBlockSnapshot()"),
                "the destination ledger may complete only after commit and must recover on stream gaps");
        assertTrue(invalidation.contains("serverLayers.clear()")
                        && invalidation.contains("pairedServerLayers.clear()")
                        && invalidation.contains("completedRestores.clear()")
                        && invalidation.contains("repaintAll()"),
                "a stream gap must fail open to vanilla instead of preserving stale concealment");
    }

    @Test
    void lateMetadataRebasesEvenAnAlreadyClosedLocalUnderlay() throws IOException {
        final String authority = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        final String rebase = method(authority, "private void rebaseUnderlay",
                "private LocalLayer newestClosedLocal");

        assertTrue(rebase.indexOf("local.authoritativeUnderlay = authoritativeState")
                        < rebase.indexOf("final TempBlock layer = TempBlock.get(block)"),
                "metadata must repair bookkeeping even when the physical local TempBlock already closed");
        assertTrue(rebase.contains("if (local.closed)")
                        && rebase.contains("local.closedState = authoritativeState")
                        && rebase.contains("updateCompletedRestores(layerId, key, authoritativeState)"),
                "a chunk-before-metadata underlay may not survive as a closed visual ghost");
    }

    @Test
    void intermediateVanillaWritesDoNotConsumeTheExpectedCloseFence() throws IOException {
        final String authority = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        final String fence = method(authority, "private CompletedRestore takeCompletedRestore",
                "public static <T> T completedRestoreState");

        final int lookup = fence.indexOf("completedRestores.get(key)");
        final int expected = fence.indexOf("completed.expectedState.equals(receivedState)");
        final int consume = fence.indexOf("completedRestores.remove(key, completed)");
        assertTrue(lookup >= 0 && expected > lookup && consume > expected,
                "the bounded close fence may be consumed only by the physical state named in close metadata");
        assertFalse(fence.contains("final CompletedRestore completed = completedRestores.remove(key)"),
                "an unrelated same-coordinate update must not discard the pending physical restore");
        assertTrue(fence.contains(
                        "retained completed TempBlock fence through intermediate update"),
                "the mismatch path must explicitly retain the fence for the expected packet");
    }

    @Test
    void reconciliationCannotRejectTheWholeLifecycleOrRollBackTempBlocks() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        assertTrue(Files.exists(source), "ExactPredictionRuntime source must be available to the invariant test");

        String runtime = com.projectkorra.projectkorra.testutil.PredictionSourceBundle.read(source);
        Path tempSource = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        if (!Files.exists(tempSource)) tempSource = Path.of("fabric").resolve(tempSource);
        String tempBlocks = com.projectkorra.projectkorra.testutil.PredictionSourceBundle.read(tempSource);
        int start = runtime.indexOf("private void reconcile0(");
        int end = runtime.indexOf("private void abortFailedLocalInput", start);
        assertTrue(start >= 0 && end > start, "action reconciliation handler must be present");

        String reconciliation = runtime.substring(start, end);
        assertTrue(runtime.contains("tempBlockAuthority")
                        && tempBlocks.contains("Map<Long, LocalLayer> localLayers"));
        assertTrue(reconciliation.contains("action.reconciled = true")
                        && reconciliation.contains("action.previousAbilityActions.clear()"),
                "reconciliation must be bookkeeping-only");
        String signature = reconciliation.substring(0, reconciliation.indexOf('{'));
        assertFalse(signature.contains("boolean accepted")
                        || reconciliation.contains("if (!accepted")
                        || reconciliation.contains("if (accepted"),
                "server metadata must not expose a whole-action rejection branch to the local lifecycle");
        assertTrue(reconciliation.contains("reconcileCreatedAbilities(action, authoritativeCreated)"),
                "exact post-input ability outcomes may converge without rolling back unrelated lifecycle state");
        assertFalse(reconciliation.contains("ability::remove")
                        || reconciliation.contains("discardLocalTempBlock")
                        || reconciliation.contains("world.setBlockState"),
                "authority metadata must never rewind client ability or block state");
        assertFalse(runtime.contains("rollback(")
                        || runtime.contains("reconcileRejectedTempBlocks")
                        || runtime.contains("\"rejected action\""),
                "the runtime must not retain a rejection rollback path");
        assertTrue(runtime.contains("private void abortFailedLocalInput")
                        && !reconciliation.contains("abortFailedLocalInput("),
                "exception cleanup must remain isolated from server reconciliation");
    }

    private static String source(final String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path)) path = Path.of("fabric").resolve(relative);
        assertTrue(Files.exists(path), "missing source: " + path);
        return com.projectkorra.projectkorra.testutil.PredictionSourceBundle.read(path);
    }

    private static String method(final String source, final String startMarker,
                                 final String endMarker) {
        final int start = source.indexOf(startMarker);
        final int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start,
                () -> "missing method boundary " + startMarker + " -> " + endMarker);
        return source.substring(start, end);
    }
}
