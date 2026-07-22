package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the TempBlock authority hand-off across an in-connection dimension change. */
class WorldChangeTempBlockResyncBoundaryTest {
    @Test
    void clientRestartsAtTheWorldEventBeforeAcceptingTheNewLedger() throws IOException {
        final String client = source("src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        final String initialize = method(client, "public static synchronized void initialize()",
                "public static void recordMovementPacket");
        final String worldChange = method(client, "private void onClientWorldChange",
                "private void requestWorldTempBlockSnapshot");
        final String request = method(client, "private void requestWorldTempBlockSnapshot",
                "private void onTempBlocks");
        final String tempBlocks = method(client, "private void onTempBlocks",
                "private void onVelocityOwner");

        assertTrue(initialize.contains("ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register"),
                "dimension changes need a synchronous lifecycle boundary; an end-tick identity poll is too late");
        final int stop = worldChange.indexOf("restartForWorldChange(client)");
        final int requestSnapshot = worldChange.indexOf("requestWorldTempBlockSnapshot()");
        assertTrue(stop >= 0 && requestSnapshot > stop,
                "the outgoing runtime must be drained and the new runtime installed before requesting its ledger");
        assertTrue(tempBlocks.indexOf("onClientWorldChange(client")
                        < tempBlocks.indexOf("ExactPredictionRuntime.applyTempBlockBatch"),
                "a snapshot racing the lifecycle event must never be applied to a runtime that will immediately clear it");
        assertTrue(request.contains("new PredictionPayloads.ClientReady"),
                "an already-ready session may use its idempotent Ready message to request a fresh world ledger");
        assertFalse(request.contains("readySent =") || request.contains("nextSequence ="),
                "world ledger resync must not reset native input sequencing or repeat the initial handshake");
    }

    @Test
    void paperAuthorityPushesAndAnswersAWorldLedgerResync() throws IOException {
        final String paper = source("../bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        final String paperListener = source("../bukkit/src/main/java/com/projectkorra/projectkorra/PKListener.java");

        final String paperReady = method(paper, "private void onReady", "private void onInputVeto");
        assertTrue(paperReady.contains("if (wasReady)")
                        && paperReady.contains("sendWorldState(player, session)")
                        && paperReady.contains("sendTempBlockSnapshot(player, session)"),
                "Paper must treat a repeated Ready as an idempotent ledger request");

        assertTrue(paper.contains("public static void synchronizeWorld(final Player player)")
                        && paperListener.contains("PaperPredictionServer.synchronizeWorld(event.getPlayer())"),
                "Paper should enqueue the destination-world ledger at its authoritative world-change event");
        final String paperSync = method(paper, "public static void synchronizeWorld(final Player player)",
                "private static void runWithOwner");
        final int paperWorldState = paperSync.indexOf("sendWorldState(player, session)");
        final int paperLedger = paperSync.indexOf("sendTempBlockSnapshot(player, session)");
        assertTrue(paperWorldState >= 0 && paperLedger > paperWorldState);
        assertFalse(exists("src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java"),
                "Fabric is a prediction client only; Paper is the sole authority endpoint");
    }

    @Test
    void paperWorldIdentityForcesABoundaryEvenWhenVanillaReusesTheClientWorld() throws IOException {
        final String client = source("src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        final String payloads = source("src/main/java/com/projectkorra/projectkorra/fabric/prediction/protocol/PredictionPayloads.java");
        final String paperProtocol = source("../bukkit/src/main/java/com/projectkorra/projectkorra/prediction/protocol/PaperPredictionProtocol.java");
        final String paper = source("../bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");

        assertTrue(payloads.contains("record ServerWorldState(UUID sessionId, long worldGeneration")
                        && payloads.contains("playS2C().register(ServerWorldState.ID"),
                "the wire contract needs an opaque authoritative world identity independent of the dimension key");
        assertTrue(paperProtocol.contains("WORLD_STATE = \"projectkorra:world_state\"")
                        && paperProtocol.contains("static byte[] worldState(UUID session, long worldGeneration, String worldIdentity)"),
                "Paper must encode the same world-boundary payload");
        assertTrue(paper.contains("player.getWorld().getUID().toString()"),
                "two Bukkit NORMAL worlds must not collapse to minecraft:overworld");

        final String handler = method(client, "private void onServerWorldState",
                "private void sendReady");
        assertTrue(client.contains("registerGlobalReceiver(PredictionPayloads.ServerWorldState.ID"));
        assertTrue(handler.contains("state.worldGeneration()")
                        && handler.contains("acceptServerWorldState")
                        && handler.contains("restartForWorldChange(client)"),
                "a changed Paper identity must restart the runtime even when ClientWorld and player object identities did not change");
    }

    @Test
    void periodicLedgerSelfHealCannotLoseTheWorldBoundaryEvent() throws IOException {
        final String paper = source("../bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        final String paperHeartbeat = method(paper, "public void run()", "private CommonInputHandler.InputResult handleVanilla0");

        final int paperWorldState = paperHeartbeat.indexOf("sendWorldState(player, session)");
        final int paperLedger = paperHeartbeat.indexOf("sendTempBlockSnapshot(player, session)");
        assertTrue(paperWorldState >= 0 && paperLedger > paperWorldState,
                "Paper's periodic ledger repair must also repair a missed same-dimension world identity event");
    }

    @Test
    void everyTempBlockBatchIsAtomicallyScopedToSessionAndWorldGeneration() throws IOException {
        final String client = source("src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        final String payloads = source("src/main/java/com/projectkorra/projectkorra/fabric/prediction/protocol/PredictionPayloads.java");
        final String paperProtocol = source("../bukkit/src/main/java/com/projectkorra/projectkorra/prediction/protocol/PaperPredictionProtocol.java");
        final String paper = source("../bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        final String handler = method(client, "private void onTempBlocks", "private void onVelocityOwner");

        assertTrue(payloads.contains("record TempBlockBatch(UUID sessionId, long worldGeneration, String worldIdentity"),
                "a ledger packet needs its authority session and ordered physical-world generation");
        assertTrue(paperProtocol.contains("static byte[] tempBlocks(UUID session, long worldGeneration, String worldIdentity"),
                "Paper must encode the same atomic ledger scope as Fabric");
        assertTrue(handler.contains("batch.sessionId()")
                        && handler.contains("batch.worldGeneration()")
                        && handler.contains("batch.worldIdentity()"),
                "the client must reject stale session/world batches before touching ClientWorld");
        assertTrue(handler.indexOf("acceptServerWorldState")
                        < handler.indexOf("ExactPredictionRuntime.applyTempBlockBatch"),
                "the batch's world boundary must be accepted before any contained block is painted");
        assertTrue(paper.contains("session.tempLayers.clear()"),
                "leaving a world must forget its delivered layers so their later closes cannot repaint the destination");
    }

    @Test
    void aReplacementAuthoritySessionRetiresTheOldRuntimeBeforeApplyingSnapshot() throws IOException {
        final String client = source("src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        final String snapshot = method(client, "private void onSnapshot", "private void onConfigChunk");
        final int sessionChange = snapshot.indexOf("sessionChanged");
        final int stop = snapshot.indexOf("ExactPredictionRuntime.stop(client)");
        final int assign = snapshot.indexOf("sessionId = snapshot.sessionId()");
        final int start = snapshot.indexOf("startRuntime(client, \"snapshot\")");

        assertTrue(sessionChange >= 0 && stop > sessionChange && assign > stop && start > assign,
                "a backend/session replacement must drain old TempBlocks before the new authority can reuse the ClientWorld");
    }

    @Test
    void aReplacedClientWorldWaitsForItsSnapshotInsteadOfAcceptingAnOldIncrementalBatch() throws IOException {
        final String client = source("src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        final String accept = method(client, "private boolean acceptServerWorldState",
                "private void sendReady");
        final String tempBlocks = method(client, "private void onTempBlocks",
                "private void onVelocityOwner");

        assertTrue(accept.contains("snapshotBoundary")
                        && accept.contains("clientWorldBoundaryAwaitingIdentity")
                        && accept.contains("if (!snapshotBoundary)")
                        && accept.indexOf("return false", accept.indexOf("if (!snapshotBoundary)")) >= 0,
                "an equal-generation marker/incremental packet must not open a newly replaced ClientWorld");
        assertTrue(tempBlocks.contains("batch.snapshot()"),
                "only the complete ledger snapshot may reopen an equal-generation world after a same-world respawn");
    }

    @Test
    void runtimeStartWaitsUntilVanillaInstallsTheDestinationPlayer() throws IOException {
        final String client = source("src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        final String restart = method(client, "private void restartForWorldChange",
                "private boolean startRuntime");

        assertTrue(restart.contains("player.getEntityWorld() != client.world"),
                "AFTER_CLIENT_WORLD_CHANGE fires before vanilla replaces client.player; starting with the old-world player causes a second destructive restart");
    }

    @Test
    void destinationLedgerIsNeverAcknowledgedWhileTheRuntimeCannotApplyIt() throws IOException {
        final String client = source("src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        final String tempBlocks = method(client, "private void onTempBlocks",
                "private void onVelocityOwner");
        final int readyGate = tempBlocks.indexOf("!active || !ExactPredictionRuntime.isReady()");
        final int accept = tempBlocks.indexOf("acceptServerWorldState");
        final int clearPending = tempBlocks.indexOf("worldTempBlockResyncPending = false");
        final int apply = tempBlocks.indexOf("ExactPredictionRuntime.applyTempBlockBatch");

        assertTrue(readyGate >= 0 && readyGate < accept,
                "a ledger received between ClientWorld and client.player replacement must be deferred");
        assertTrue(apply > readyGate && clearPending > apply,
                "the destination ledger may be acknowledged only on the path that actually applies it");
    }

    @Test
    void worldDiagnosticCapturesObjectIdentityAndLedgerStateWithoutJvmFlags() throws IOException {
        final String client = source("src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        final String commands = source("src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionDebugCommands.java");

        assertTrue(commands.contains("literal(\"world\")")
                        && commands.contains("PredictionClient.worldTransitionReport()"),
                "the transition report must be available in-game without enabling verbose JVM logging");
        assertTrue(client.contains("System.identityHashCode(world)")
                        && client.contains("serverWorldGeneration")
                        && client.contains("worldTempBlockResyncPending")
                        && client.contains("clientWorldBoundaryAwaitingIdentity"),
                "the report must distinguish same-key ClientWorld objects and the ledger fence state");
    }

    @Test
    void ledgerRemainsOwedUntilACompleteSnapshotIsActuallyApplied() throws IOException {
        final String client = source("src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        final String request = method(client, "private void requestWorldTempBlockSnapshot",
                "private void onTempBlocks");
        final String tempBlocks = method(client, "private void onTempBlocks",
                "private void onVelocityOwner");

        assertTrue(request.contains("worldTempBlockRequestSent")
                        && !request.contains("worldTempBlockResyncPending = false"),
                "sending a request is not proof that its destination ledger was received");
        final int apply = tempBlocks.indexOf("ExactPredictionRuntime.applyTempBlockBatch");
        final int complete = tempBlocks.indexOf("worldTempBlockResyncPending = false");
        assertTrue(apply >= 0 && complete > apply,
                "only successful application of a complete snapshot may satisfy the ledger fence");
    }

    private static String source(final String relative) throws IOException {
        Path source = Path.of(relative);
        if (!Files.exists(source)) source = Path.of("fabric").resolve(relative);
        assertTrue(Files.exists(source), "missing source: " + source);
        return Files.readString(source);
    }

    private static boolean exists(final String relative) {
        final Path source = Path.of(relative);
        return Files.exists(source) || Files.exists(Path.of("fabric").resolve(relative));
    }

    private static String method(final String source, final String startMarker, final String endMarker) {
        final int start = source.indexOf(startMarker);
        final int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start,
                "missing method boundary " + startMarker + " -> " + endMarker);
        return source.substring(start, end);
    }
}
