package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the network and render batching that keeps dense TempBlock scenes responsive. */
class TempBlockNetworkOptimizationBoundaryTest {
    @Test
    void periodicRepairIsDrivenByVisibilityChangesInsteadOfEveryHeartbeat() throws IOException {
        final String server = source(
                "src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        final String heartbeat = method(server, "public void run()",
                "private CommonInputHandler.InputResult handleVanilla0");
        final String policy = method(server, "private boolean shouldSendPeriodicTempBlockSnapshot",
                "private void sendWorldState");

        assertTrue(heartbeat.contains("shouldSendPeriodicTempBlockSnapshot(player, session)"));
        assertFalse(heartbeat.contains("sendWorldState(player, session);\n                    sendTempBlockSnapshot(player, session);"),
                "an unchanged player must not receive the complete ledger every second");
        assertTrue(policy.contains("lastTempBlockSnapshotChunkX")
                        && policy.contains("lastTempBlockSnapshotChunkZ")
                        && policy.contains("lastTempBlockSnapshotViewDistance")
                        && policy.contains("TEMP_BLOCK_SNAPSHOT_SAFETY_TICKS"),
                "movement, view-distance changes, and a slow safety repair must all refresh visibility");
    }

    @Test
    void denseOperationsArePackedByWireSizeAndClientRebuildsAreCoalesced() throws IOException {
        final String server = source(
                "src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        final String overlay = source(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientBlockVisualOverlay.java");
        final String authority = source(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        final String runtime = source(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");

        assertTrue(server.contains("PaperPredictionProtocol.tempBlockOperationSize(operation)")
                        && server.contains("Messenger.MAX_MESSAGE_SIZE - TEMP_BLOCK_PACKET_HEADROOM_BYTES")
                        && server.contains("MAX_TEMP_BLOCK_OPS_PER_PACKET = 256"),
                "packet density must be bounded by encoded bytes rather than the legacy four-op page");
        assertFalse(server.contains("TEMP_BLOCK_OPS_PER_PACKET = 4"));
        assertTrue(overlay.contains("Set<RenderSectionKey> pendingRebuildSections")
                        && overlay.contains("flushRebuildSections()"));
        assertTrue(authority.contains("visualOverlay.beginRebuildBatch()")
                        && authority.contains("visualOverlay.endRebuildBatch()"),
                "one network batch should request each affected terrain section only once");
        assertTrue(runtime.contains("blockVisualOverlay.flushRebuilds()"),
                "separate TempBlock packets handled in one client tick must share the same rebuild flush");
    }

    private static String method(final String source, final String start, final String end) {
        final int from = source.indexOf(start);
        final int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from, start);
        return source.substring(from, to);
    }

    private static String source(final String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path) && relative.startsWith("../")) path = Path.of(relative.substring(3));
        if (!Files.exists(path)) path = Path.of("bukkit").resolve(relative);
        assertTrue(Files.exists(path), path.toString());
        return com.projectkorra.projectkorra.testutil.PredictionSourceBundle.read(path).replace("\r\n", "\n");
    }
}
