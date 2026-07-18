package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Red-first boundaries for regressions reproduced on the exact-prediction client. */
class ReportedPredictionRegressionsTest {
    @Test
    void waterSpoutCloseFenceResolvesTheLiveClientLayerWhenThePacketActuallyArrives() throws IOException {
        final String runtime = runtime();
        final String metadata = method(runtime, "private void applyTempBlockBatch0",
                "private void noteDirectBlock0");
        final String packetFence = method(runtime, "private CompletedTempBlockRestore takeCompletedTempBlockRestore",
                "private void updateCompletedTempBlockRestores");

        assertTrue(metadata.contains("followLiveClientState = activeLocal != null"),
                "an unpaired owned close must remember that its visible state came from a live client layer");
        assertTrue(packetFence.contains("completed.followLiveClientState")
                        && packetFence.contains("clientTempBlockState(key.world, key.pos)"),
                "the one-shot close fence must choose live water or its final underlay at packet time, not snapshot water at metadata time");
    }

    @Test
    void cooldownVetoStillRunsPaperComboBookkeeping() throws IOException {
        final String paper = source("../bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        final String process = method(paper, "private CommonInputHandler.InputResult processInput(",
                "private void flushTempBlocks()");
        final String bendingPlayer = source("../common/src/main/java/com/projectkorra/projectkorra/BendingPlayer.java");

        assertFalse(process.contains("if (locallyRejectedOnCooldown) {")
                        && process.contains("return CommonInputHandler.InputResult.pass();"),
                "a cooldown veto may suppress the bound cast, but may not skip the native handler that records combo steps");
        assertTrue(process.contains("CooldownSync.runInputVeto"),
                "Paper must run its normal input under a scoped cooldown veto so combo history is retained");
        assertTrue(bendingPlayer.contains("CooldownSync.isInputVetoed"),
                "the scoped veto must be observed by ordinary ability cooldown checks");
    }

    @Test
    void externalKnockbackIsCommittedAfterLocalLocomotionAndAfterQueuedRemovals() throws IOException {
        final String runtime = runtime();
        final String velocity = method(runtime, "private boolean authoritativeVelocity0",
                "private boolean tracksVelocityEntity0");
        final String tick = method(runtime, "private void tick0", "private void reconcileAuthoritativeCooldowns");
        final String paper = source("../bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        final String paperVelocity = method(paper, "public void onVelocity(Ability ability,",
                "public void beforeWrite(final CoreAbility ability");

        assertTrue(velocity.contains("externalVelocityFence.receive") && velocity.contains("return true;"),
                "an externally owned velocity packet for the local player must be staged instead of being overwritten by this tick's scooter/jet progress");
        assertTrue(velocity.contains("stageUnownedLocalVelocity"),
                "vanilla/outside knockback without an ownership receipt must use the same server-authority fence");
        final String predictedWrite = method(runtime, "private void setVelocity0",
                "private boolean authoritativeVelocity0");
        assertTrue(predictedWrite.contains("externalVelocityFence.blocksPredictedWrite")
                        && predictedWrite.contains("return;"),
                "a late Scooter/Jet progress write must remain fenced through the movement-consumption heartbeat");
        assertTrue(tick.indexOf("platform.tick();") < tick.indexOf("applyPendingExternalVelocities"),
                "external velocity must be committed after common ability progress");
        assertTrue(paperVelocity.indexOf("flushAbilityRemovals();")
                        < paperVelocity.indexOf("velocityOwnerV2"),
                "Paper removals caused by the hit must be delivered before the hit's velocity ownership receipt");
    }

    private static String runtime() throws IOException {
        return source("src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
    }

    private static String source(final String value) throws IOException {
        Path path = Path.of(value);
        if (!Files.exists(path)) path = Path.of("fabric").resolve(value);
        return Files.readString(path);
    }

    private static String method(final String source, final String startMarker, final String endMarker) {
        final int start = source.indexOf(startMarker);
        final int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start,
                () -> "missing method boundary " + startMarker + " -> " + endMarker);
        return source.substring(start, end);
    }
}
