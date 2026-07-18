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
    void metadataConcealsOnlyAnExactSemanticOperationWhileClientCoordinatesRemainVisualAuthority() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        assertTrue(Files.exists(source), "ExactPredictionRuntime source must be available to the invariant test");

        String runtime = Files.readString(source);
        int start = runtime.indexOf("private void applyTempBlockBatch0");
        int end = runtime.indexOf("private void setVelocity0", start);
        assertTrue(start >= 0 && end > start, "TempBlock metadata handler must be present");

        String handler = runtime.substring(start, end);
        assertTrue(handler.contains("world.setBlockState(pos, desiredTempBlockState(key), 19)"));
        assertTrue(runtime.contains("serverTempBlocks.overlayState(key, player.getUuid())"));
        assertFalse(handler.contains("world.getBlockState(pos).equals"),
                "concealment must not depend on a fragile exact physical-state receipt");
        assertFalse(handler.contains("blockEchoes.add("));
        assertTrue(handler.contains("operation.effectAbility()")
                        && handler.contains("operation.effectStep()")
                        && handler.contains("operation.effectOrdinal()"));
        assertTrue(runtime.contains("pairedTempBlockCoordinates.computeIfAbsent(server.key")
                        && runtime.contains("shifted="));
        assertTrue(runtime.contains("authoritativeTempBlockEffects.get(local.effect)")
                        && runtime.contains("clientTempBlockEffects.get(server.effect)"),
                "pairing must use the exact causal identity in both arrival orders");
        assertFalse(runtime.contains("findLocalTempBlockCandidate")
                        || runtime.contains("MAX_TEMP_BLOCK_STEP_SKEW"),
                "nearest-tick/coordinate inference can cross-wire rapid overlapping layers");
        assertTrue(runtime.contains("The client TempBlock is the visual authority")
                        && runtime.contains("local.serverClosed = true"),
                "the same semantic operation at another coordinate must keep the client lifecycle");
    }

    @Test
    void reconciliationIsBookkeepingOnlyAndCannotRollBackTheClientLifecycle() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        assertTrue(Files.exists(source), "ExactPredictionRuntime source must be available to the invariant test");

        String runtime = Files.readString(source);
        int start = runtime.indexOf("private void reconcile0(");
        int end = runtime.indexOf("private void abortFailedLocalInput", start);
        assertTrue(start >= 0 && end > start, "action reconciliation handler must be present");

        String reconciliation = runtime.substring(start, end);
        assertTrue(runtime.contains("clientTempBlockActions"));
        assertTrue(reconciliation.contains("action.reconciled = true")
                        && reconciliation.contains("action.previousAbilityActions.clear()"),
                "reconciliation must be bookkeeping-only");
        assertFalse(reconciliation.contains("accepted"),
                "server metadata must not expose a rejection branch to the local lifecycle");
        assertFalse(reconciliation.contains("ability::remove")
                        || reconciliation.contains("discardLocalTempBlock")
                        || reconciliation.contains("world.setBlockState"),
                "authority metadata must never rewind client ability or block state");
        assertFalse(runtime.contains("rollback(")
                        || runtime.contains("reconcileRejectedTempBlocks")
                        || runtime.contains("\"rejected action\""),
                "the runtime must not retain a rejection rollback path");
        assertTrue(runtime.contains("private void abortFailedLocalInput")
                        && runtime.contains("This is not an authority response"),
                "exception cleanup must remain isolated from server reconciliation");
    }
}
