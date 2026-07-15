package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards metadata-only concealment without reviving reconciliation. */
class TempBlockMetadataAuthorityTest {
    @Test
    void metadataMayConcealALateSnapshotButCannotTouchALiveClientLayer() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        assertTrue(Files.exists(source), "ExactPredictionRuntime source must be available to the invariant test");

        String runtime = Files.readString(source);
        int start = runtime.indexOf("private void applyTempBlockBatch0");
        int end = runtime.indexOf("private void setVelocity0", start);
        assertTrue(start >= 0 && end > start, "TempBlock metadata handler must be present");

        String handler = runtime.substring(start, end);
        assertTrue(handler.contains("if (clientTempBlockState(world, pos) == null)"));
        assertTrue(handler.contains("world.setBlockState(pos, desiredTempBlockState(key), 19)"));
        assertTrue(runtime.contains("serverTempBlocks.overlayState(key, player.getUuid())"));
        assertFalse(handler.contains("world.getBlockState(pos).equals"),
                "concealment must not depend on a fragile exact physical-state receipt");
        assertFalse(handler.contains("blockEchoes.add("),
                "metadata must never invent an echo for a vanilla packet");
        assertFalse(handler.contains("revertBlock("));
        assertTrue(handler.contains("hiddenBefore && commonOperation == TempBlockSync.Operation.DISCARD")
                && handler.contains("TempBlock.discardBlock"),
                "explicit DISCARD authority may discard bookkeeping, never run a rollback");
        assertTrue(handler.contains("} else if (hiddenBefore) {")
                        && handler.contains("pendingTempBlockReveals.put(key, viewerState)"),
                "a buried expiry or ownership transfer must defer its underlay while a local layer is live");
    }

    @Test
    void rejectedActionsCannotRollBackClientTempBlocks() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        assertTrue(Files.exists(source), "ExactPredictionRuntime source must be available to the invariant test");

        String runtime = Files.readString(source);
        int start = runtime.indexOf("private void rollback(Action action)");
        int end = runtime.indexOf("private int blockConfirmationTicks", start);
        assertTrue(start >= 0 && end > start, "action rejection handler must be present");

        String rejection = runtime.substring(start, end);
        assertFalse(runtime.contains("clientTempBlockActions"),
                "client TempBlocks must never be assigned to an action rollback ledger");
        assertFalse(rejection.contains("layer.revertBlock()"),
                "action rejection must never directly roll back a client-owned TempBlock layer");
        assertTrue(runtime.contains("rollback(action, true)"));
        assertTrue(rejection.contains("preserveTempBlockOwners && ownsClientTempBlock(ability)"));
        assertTrue(rejection.indexOf("preserveTempBlockOwners && ownsClientTempBlock(ability)")
                        < rejection.indexOf("ability::remove"),
                "a rejected action must retain its ability before remove() can indirectly revert live layers");
        assertTrue(rejection.contains("if (!preservedVisualAuthority)"),
                "entities supporting a retained TempBlock ability must not be torn down underneath it");
    }
}
