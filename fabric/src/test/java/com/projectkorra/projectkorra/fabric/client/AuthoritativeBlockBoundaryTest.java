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

        assertTrue(batch.contains("localOwnedTempBlockState") && batch.contains("setBlockState("),
                "a leaked batch update must restore a proven live owner TempBlock instead of deleting the ability");
        assertTrue(batch.contains("OwnedBatchRestore"));
        assertTrue(runtime.contains("Set<Integer> consumedEchoes")
                        && runtime.contains("world.getBlockState(pos), mutation != null && mutation.locallyPredicted"),
                "mixed chunk deltas must restore ordered Earth movement echoes without hiding unrelated entries");
        assertTrue(chunk.contains("hasOwnedLayer(mutation)"),
                "a chunk snapshot may preserve only a proven owned TempBlock layer");
        assertTrue(chunk.contains("localOwnedTempBlockState")
                        && chunk.contains("mutation.preservePredictedOwnedState"),
                "chunk authority must prefer a live owned TempBlock before ledger fallbacks");
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
        assertTrue(metadata.contains("final boolean confirmedLocalCreate = exactLocalCreate || newerSameActionState")
                        || metadata.contains("final boolean confirmedLocalCreate = exactLocalCreate")
                        && runtime.contains("hasNewerLocalStateAfter"),
                "a lagging server CREATE must not repaint a newer state from the same Water/Earth action");
        assertTrue(metadata.contains("liveSameActionTempBlock")
                        && runtime.contains("isLiveLocalTempBlockForAction"),
                "material differences must not discard a live EarthSmash/WaterFlow layer from the same action");
        assertTrue(metadata.contains("localPredictionAtCoordinate"),
                "owned metadata must still classify prediction for receipts and diagnostics");
        assertTrue(metadata.contains("hasActionBlockHistory(world, pos, operation.actionSequence())"),
                "PhaseChange history must survive ICE-to-original-WATER removing the live mutation entry");
        assertTrue(runtime.contains("private final List<BlockEcho> localBlockHistory")
                        && runtime.contains("localBlockHistory.add(echo)"),
                "TempBlock lifecycle proof must survive disposal of vanilla packet echoes");
        assertFalse(runtime.contains("localPredictionObserved"),
                "merely touching a coordinate must not expose delayed server TempBlocks during chunk authority");
        assertTrue(runtime.contains("boolean preservePredictedOwnedState")
                        && runtime.contains("this.preservePredictedOwnedState = false;"),
                "newer-state preservation must be explicit and cleared whenever authority is adopted");
        assertTrue(metadata.contains("mutation.preservePredictedOwnedState = newerSameActionState || liveSameActionTempBlock;"),
                "exact PhaseChange ICE must not grant later WATER permission to survive authority");
        assertTrue(runtime.contains("mutation.preservePredictedOwnedState = action == mutation.serverAction;"),
                "a different PhaseChange input must clear preservation inherited from the prior action");
        assertFalse(metadata.contains("boolean locallyPredictedLayer = ownPredictedAction"),
                "a REVERT must be tied to its confirmed layer, not merely its action");
        assertTrue(metadata.contains("ownedTempReceipts.addLast"));
        assertFalse(runtime.contains("reconcileSuppressedOwnedTempMetadata"),
                "owner TempBlock metadata must never repaint or discard the client ability");
        assertTrue(metadata.contains("BlockState desired = currentState;")
                        && metadata.contains("BlockState desired = world.getBlockState(pos);"),
                "owned CREATE and REVERT receipts must preserve the currently rendered local state");
        assertTrue(runtime.contains("if (layer.ownedByLocalPlayer) continue;"),
                "server-owned layers are ledger/diagnostic state, not owner-visible world state");
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
        assertTrue(runtime.contains("if (mutation.serverTempActive && hasOwnedLayer(mutation)) return false;"),
                "an active owner layer must never expire into delayed server TempBlock state");
        assertTrue(runtime.contains("invalidateClientTempStack(mutation.world, mutation.pos);"),
                "negative receipts must retire the local TempBlock object before correcting its world state");
        assertTrue(runtime.contains("localOwnedTempBlockState(world, pos, mutation)"),
                "normal block traffic must preserve a live owner TempBlock instead of letting delayed server state replace it");
        assertFalse(runtime.contains("reconcileSuppressedOwnedTempMetadata"),
                "owner TempBlock metadata must remain ledger-only and never rewrite the client world");
        assertTrue(runtime.contains("recordPhysicalAuthority")
                        && runtime.contains("BlockState physicalAuthority"),
                "physical TempBlock confirmations must never replace the owner-visible rollback baseline");
        assertTrue(runtime.contains("recordViewerAuthority(mutation.serverTempState)")
                        && runtime.contains("recordViewerAuthority(reverted)"),
                "CREATE and REVERT metadata must advance the owner's exact latest server-visible rollback state");
    }

    private static String method(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start);
        return source.substring(start, end);
    }
}
