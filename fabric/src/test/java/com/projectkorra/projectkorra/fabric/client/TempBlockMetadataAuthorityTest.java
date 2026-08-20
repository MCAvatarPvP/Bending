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
