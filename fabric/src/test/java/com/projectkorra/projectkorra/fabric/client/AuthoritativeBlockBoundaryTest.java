package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents generic prediction from repainting authority while allowing proven owned TempBlock receipts. */
class AuthoritativeBlockBoundaryTest {
    @Test
    void onlyOwnedTempLayersMaySurviveBatchesAndChunkSnapshots() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        assertTrue(Files.exists(source));

        String runtime = Files.readString(source);
        String batch = method(runtime, "private void acceptAuthoritativeBlockBatch0",
                "private void acceptAuthoritativeChunk0");
        String chunk = method(runtime, "private void acceptAuthoritativeChunk0",
                "private void applyTempBlockBatch0");

        assertFalse(batch.contains("setBlockState("),
                "batch restoration must stay behind the owned-receipt helper");
        assertTrue(batch.contains("OwnedBatchRestore"));
        assertTrue(chunk.contains("hasOwnedLayer(mutation)"),
                "a chunk snapshot may preserve only a proven owned TempBlock layer");
        assertTrue(chunk.contains("BlockState desired = mutation.serverTempState;"),
                "chunk authority must not restore stale predicted WATER over an active server ICE layer");
        assertFalse(chunk.contains("mutation.predicted : mutation.serverTempState"));
        assertTrue(chunk.contains("blockEchoes.removeIf"));
        assertTrue(chunk.contains("blocks.entrySet().removeIf"));
        assertTrue(runtime.contains("invalidateClientTempStack"),
                "winning authority must invalidate live client TempBlocks before their late cleanup can ghost");
    }

    @Test
    void ownedReceiptsAreBoundedAndMetadataStartsANewConfirmationWindow() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        String runtime = Files.readString(source);
        String metadata = method(runtime, "private void applyTempBlockBatch0",
                "private static BlockState visibleServerLayer");

        assertTrue(runtime.contains("OWNED_TEMP_RECEIPT_TICKS = 40"));
        assertTrue(metadata.contains("mutation.lastTick = tick;"),
                "PhaseChange REVERT metadata must not inherit an already-expired prediction deadline");
        assertTrue(metadata.contains("operation.packetExpected()"),
                "metadata-only layer changes and snapshots must not create phantom packet receipts");
        assertTrue(metadata.contains("final boolean localPredictionAtCoordinate = (ownPredictedAction")
                        && metadata.contains("&& mutation.locallyPredicted"),
                "ability-level prediction must not expose a coordinate the client never predicted");
        assertTrue(metadata.contains("mutation.predicted.equals(material)"),
                "a matching action and coordinate must not confirm the wrong PhaseChange material");
        assertTrue(metadata.contains("world.getBlockState(pos).equals(material)"),
                "the actual client block must also match an exact CREATE receipt");
        assertTrue(metadata.contains("nextBlockEchoOrdinal")
                        && metadata.contains("lastMatchedLocalCreateOrdinal"),
                "repeated WATER/AIR/WATER lifecycles must consume CREATE echoes in order");
        assertTrue(metadata.contains("localPredictionAtCoordinate"),
                "a mismatched local prediction must be corrected without exposing unpredicted owner layers");
        assertTrue(metadata.contains("hasActionBlockHistory(world, pos, operation.actionSequence())"),
                "PhaseChange history must survive ICE-to-original-WATER removing the live mutation entry");
        assertTrue(runtime.contains("private final List<BlockEcho> localBlockHistory")
                        && runtime.contains("localBlockHistory.add(echo)"),
                "TempBlock lifecycle proof must survive disposal of vanilla packet echoes");
        assertTrue(runtime.contains("if (mutation.localPredictionObserved) return layers.get(layers.size() - 1).state;"),
                "an active lower ICE layer must never resolve to original WATER after an overlapping REVERT");
        assertFalse(metadata.contains("boolean locallyPredictedLayer = ownPredictedAction"),
                "a REVERT must be tied to its confirmed layer, not merely its action");
        assertTrue(metadata.contains("ownedTempReceipts.addLast"));
        assertTrue(metadata.contains("reconcileSuppressedOwnedTempMetadata"),
                "suppressed CREATE/REVERT metadata is the only remaining lifecycle authority");
        assertTrue(runtime.contains("hasNewerLocalBlockEcho"),
                "an older same-action REVERT must not erase a newer overlapping Water/Earth CREATE");
        assertTrue(runtime.contains("localCreateOrdinal >= 0L"),
                "same-action overlap preservation requires an exact preceding CREATE receipt");
        assertFalse(runtime.contains("earlyPhaseChangeThaw"),
                "owned ICE-to-WATER prediction must not wait for a rollback");
        assertTrue(runtime.contains("isTopLayerOwnedByLocalPlayer(mutation)"),
                "a buried owned layer must not overwrite an overlapping authoritative top layer");
        assertFalse(metadata.contains("world.setBlockState("),
                "metadata records receipts but never directly changes the world");
    }

    @Test
    void clientOnlyTrailBlocksUseTheMeasuredActionWindow() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        String runtime = Files.readString(source);

        assertTrue(runtime.contains("tick - action.createdTick")
                        && runtime.contains("+ ACTION_BLOCK_CONFIRMATION_MARGIN_TICKS"),
                "accepted input RTT must define when a missing server TempBlock becomes a negative receipt");
        assertTrue(runtime.contains("mutation.serverTempActive")
                        && runtime.contains("blockConfirmationTicks(mutation.lastAction)"),
                "only server-absent predicted trail blocks may use the shorter measured deadline");
        assertTrue(runtime.contains("MIN_ACTION_BLOCK_CONFIRMATION_TICKS = 4"));
        assertTrue(runtime.contains("invalidateClientTempStack(mutation.world, mutation.pos);"),
                "negative receipts must retire the local TempBlock object before correcting its world state");
        assertTrue(runtime.contains("invalidateClientTempStack(world, mutation.pos);"),
                "suppressed metadata corrections must prevent delayed local cleanup from resurrecting ghosts");
    }

    private static String method(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start);
        return source.substring(start, end);
    }
}
