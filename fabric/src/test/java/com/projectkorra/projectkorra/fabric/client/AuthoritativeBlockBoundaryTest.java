package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the no-rollback authority boundary for common TempBlocks. */
class AuthoritativeBlockBoundaryTest {
    @Test
    void commonTempBlocksBypassTheGenericPredictionLedger() throws IOException {
        String runtime = runtime();
        String direct = method(runtime, "private void setTempBlock0", "private void setBlock0");

        assertTrue(runtime.contains("if (TempBlockSync.currentWorldMutation() != null)"));
        assertTrue(runtime.contains("INSTANCE.setTempBlock0(world, pos.toImmutable(), state)"));
        assertTrue(direct.contains("world.setBlockState(pos, visibleState, 19)"));
        assertTrue(direct.contains("blocks.remove(key)"));
        assertTrue(direct.contains("blockEchoes.removeIf"));
        assertFalse(direct.contains("blocks.computeIfAbsent"),
                "TempBlocks must never enter the timeout/correction ledger");
        assertFalse(direct.contains("new BlockEcho"),
                "TempBlocks must never manufacture vanilla receipt expectations");
        assertFalse(runtime.contains("clientTempBlockActions"),
                "TempBlocks must never be assigned to an action rollback ledger");
        assertTrue(runtime.contains("TempBlock.discardAll()"),
                "world/runtime shutdown must drop old-world bookkeeping without repainting it");
        assertTrue(runtime.indexOf("TempBlock.discardAll()") < runtime.indexOf("CoreAbility.removeAll()"),
                "TempBlocks must be discarded before ability removal can repaint the outgoing ClientWorld");
        assertTrue(direct.contains("pendingTempBlockReveals.remove(key)"));
        assertTrue(direct.contains("clientTempBlockState(world, pos) == null"),
                "an overlapping server underlay may be revealed only after the local stack closes");
        assertTrue(direct.contains("serverTempBlocks.viewerState(key)"),
                "a late snapshot must supply the baseline when the physical server layer predated prediction");
    }

    @Test
    void physicalServerTrafficIsHiddenForTheWholeOwnedLifecycle() throws IOException {
        String runtime = runtime();
        String single = method(runtime, "private boolean authoritativeBlock0",
                "private boolean authoritativeBlockBatch0");
        String batch = method(runtime, "private boolean authoritativeBlockBatch0",
                "private void acceptAuthoritativeBlockBatch0");
        String tail = method(runtime, "private void acceptAuthoritativeBlockBatch0",
                "private void acceptAuthoritativeChunk0");
        String chunk = method(runtime, "private void acceptAuthoritativeChunk0",
                "private void applyTempBlockBatch0");

        assertTrue(single.indexOf("hidesServerTempBlock(key)") < single.indexOf("consumeBlockEcho"),
                "coordinate ownership must be checked before state/echo matching");
        assertTrue(batch.contains("tempBlockBatchRestores.put(key, desiredTempBlockState(key))"));
        assertTrue(batch.contains("if (hiddenTempBlocks == positions.size())"));
        assertTrue(tail.contains("tempBlockBatchRestores.remove(key)"));
        assertTrue(tail.contains("world.setBlockState(pos, tempBlockRestore, 19)"));
        assertTrue(chunk.contains("TempBlock.getActiveLayers()"));
        assertTrue(chunk.contains("desiredTempBlockState(new BlockKey(world, pos))"),
                "chunk reapplication must preserve a newer remote/server overlay above a local layer");
        assertTrue(chunk.contains("serverTempBlocks.hiddenViewerStates(viewerId)"));
        assertFalse(chunk.contains("discardBlock(") || chunk.contains("removeBlock("),
                "chunk delivery is transport, never authority over a local TempBlock stack");
    }

    @Test
    void lifecycleMetadataCannotReconcileOrRetireAClientTempBlock() throws IOException {
        String runtime = runtime();
        String metadata = method(runtime, "private void applyTempBlockBatch0",
                "private void setVelocity0");

        assertTrue(metadata.contains("serverTempBlocks.apply"));
        assertTrue(metadata.contains("clientTempBlockState(world, pos) == null"),
                "late-join concealment must never overwrite a live client TempBlock");
        assertTrue(metadata.contains("desiredTempBlockState(key)"));
        assertTrue(runtime.contains("serverTempBlocks.overlayState(key, player.getUuid())"),
                "a newer server-only/remote layer must overlay and later reveal the client layer");
        assertTrue(metadata.contains("hiddenBefore && commonOperation == TempBlockSync.Operation.DISCARD"));
        assertTrue(metadata.contains("TempBlock.discardBlock(FabricPredictionMC.block(world, pos))"),
                "only an explicit DISCARD authority handoff may retire bookkeeping");
        assertTrue(metadata.contains("pendingTempBlockReveals.put(key, viewerState)"),
                "a metadata-only buried close must retain the underlay until the client stack ends");
        assertFalse(metadata.contains("revertBlock(") || metadata.contains("removeBlock("),
                "server TempBlock traffic must never run a local rollback lifecycle");
        assertFalse(metadata.contains("blocks.computeIfAbsent"));
        assertFalse(metadata.contains("blockEchoes.add("));
    }

    @Test
    void legacyTempBlockRollbackMachineryCannotReturn() throws IOException {
        String runtime = runtime();

        assertFalse(runtime.contains("OwnedTempReceipt"));
        assertFalse(runtime.contains("invalidateClientTempStack"));
        assertFalse(runtime.contains("localBlockHistory"));
        assertFalse(runtime.contains("serverTempActive"));
        assertFalse(runtime.contains("physicalAuthority"));
        assertTrue(runtime.contains("private final ClientTempBlockLedger<BlockKey, BlockState> serverTempBlocks"));
        assertTrue(runtime.contains("private List<PredictionDesyncBlock> ownedTempDesyncs0"));
        assertTrue(runtime.contains("return List.of();"),
                "an intentionally hidden physical TempBlock is not a rollback/desync marker");
    }

    @Test
    void fallingBlocksRequireExactCasterOwnershipInsteadOfProximity() throws IOException {
        String runtime = runtime();
        String receipt = method(runtime, "private void noteTempFallingBlock0",
                "private boolean reconcileSpawn0");
        String spawn = method(runtime, "private boolean reconcileSpawn0",
                "private boolean removeAliasedEntity0");
        String payloads = source("src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionPayloads.java");
        String client = source("src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");

        assertTrue(receipt.contains("localPlayer.getUuid().equals(receipt.abilityOwner())"));
        assertTrue(receipt.contains("new TempFallingBlockKey(")
                && receipt.contains("receipt.actionSequence(), receipt.spawnOrdinal()"));
        assertTrue(receipt.contains("pending.ability.equalsIgnoreCase(receipt.ability())"),
                "an ordinal mismatch must never alias a different ability's falling block");
        assertTrue(receipt.contains("world.getEntityById(receipt.serverEntityId())"),
                "a late receipt must keep an already-spawned authoritative entity");
        assertTrue(receipt.contains("observedFallingBlockSpawns.remove(receipt.serverEntityId())")
                        && receipt.contains("vanillaSpawnSeen"),
                "a receipt arriving after a short-lived vanilla entity must not create a ghost alias");
        assertTrue(spawn.contains("authoritativeEntityAliases.containsKey(packet.getEntityId())")
                && spawn.contains("return true"));
        assertTrue(spawn.contains("packet.getEntityType() == net.minecraft.entity.EntityType.FALLING_BLOCK"));
        assertTrue(spawn.contains("return false"),
                "an unreceipted remote falling block must always take the vanilla spawn path");
        assertTrue(payloads.contains("record TempFallingBlockReceipt"));
        assertTrue(payloads.contains("playS2C().register(TempFallingBlockReceipt.ID"));
        assertTrue(client.contains("registerGlobalReceiver(PredictionPayloads.TempFallingBlockReceipt.ID"));
    }

    private static String runtime() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        if (!Files.exists(source)) source = Path.of("fabric").resolve(source);
        assertTrue(Files.exists(source));
        return Files.readString(source);
    }

    private static String source(String relative) throws IOException {
        Path source = Path.of(relative);
        if (!Files.exists(source)) source = Path.of("fabric").resolve(relative);
        assertTrue(Files.exists(source));
        return Files.readString(source);
    }

    private static String method(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start);
        return source.substring(start, end);
    }
}
