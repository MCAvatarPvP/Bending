package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards semantic TempBlock pairing and exact lifecycle reconciliation. */
class TempBlockMetadataAuthorityTest {
    @Test
    void metadataConcealsTheAcceptedOwnerBeforeExactPairing() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        assertTrue(Files.exists(source), "ClientTempBlockAuthority source must be available to the invariant test");

        String authority = Files.readString(source);
        int start = authority.indexOf("public void applyAuthoritativeBatch");
        int end = authority.indexOf("/** Runs an authoritative ability removal", start);
        assertTrue(start >= 0 && end > start, "TempBlock metadata handler must be present");

        String handler = authority.substring(start, end);
        assertTrue(handler.contains("world.setBlockState(pos, desiredState(key), 19)"));
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
        assertTrue(concealment.contains("server.hiddenForLocalViewer")
                        && concealment.contains("return hasSemanticPair(key)"),
                "an authenticated predicted owner must hide Paper before exact pairing, while a pair remains a fallback identity");
        assertFalse(authority.contains("findLocalTempBlockCandidate")
                        || authority.contains("MAX_TEMP_BLOCK_STEP_SKEW"),
                "nearest-tick/coordinate inference can cross-wire rapid overlapping layers");
        assertTrue(authority.contains("!change.ability().tracksPredictedTempBlocks()"),
                "a transfer preview must be excluded before it can reserve an authoritative ordinal");
        assertTrue(authority.contains("final boolean stableEarthSmashSlot")
                        && authority.contains("repaint(server.key")
                        && authority.contains("rebaseUnderlay(local.key, viewer)"),
                "an exact EarthSmash frame/slot must reconcile immediately without nearest-frame guessing");
        assertTrue(authority.contains("common-client lifecycle")
                        && authority.contains("local.serverClosed = true"),
                "the same semantic operation at another coordinate must keep the client lifecycle");
    }

    @Test
    void reconciliationCannotRejectTheWholeLifecycleOrRollBackTempBlocks() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        assertTrue(Files.exists(source), "ExactPredictionRuntime source must be available to the invariant test");

        String runtime = Files.readString(source);
        Path tempSource = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        if (!Files.exists(tempSource)) tempSource = Path.of("fabric").resolve(tempSource);
        String tempBlocks = Files.readString(tempSource);
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
}
