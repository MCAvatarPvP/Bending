package com.projectkorra.projectkorra.fabric.client;

import com.projectkorra.projectkorra.prediction.movement.ExternalVelocityFence;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Red-first boundaries for regressions reproduced on the exact-prediction client. */
class ReportedPredictionRegressionsTest {
    @Test
    void earthSmashCheckpointCannotRewriteItsCreationAction() throws IOException {
        final String runtime = runtime();
        final String transfer = method(runtime, "private void transferAuthoritativeAbility0",
                "private void recordAbilityRemoval");
        final String reconcile = method(runtime, "private void reconcileCreatedAbilities",
                "private List<CoreAbility> locallyCreatedAbilities");

        assertTrue(transfer.contains("this.associateAbility(action, selected)")
                        && transfer.contains("this.abilityCreationActions.putIfAbsent(selected, localSequence)"));
        assertFalse(transfer.contains("this.abilityCreationActions.put(selected, localSequence)"),
                "GRABBED/SHOT checkpoints transition an existing smash and must not make it look newly created by that input");
        assertTrue(transfer.contains("restoredFromAuthority = true")
                        && transfer.contains("this.authoritativelyEstablishedAbilities.add(selected)"),
                "a checkpoint-restored smash is proven by Paper even though the transition correctly reports created=[]");
        assertTrue(reconcile.contains("this.locallyCreatedAbilities(action.sequence)")
                        && reconcile.contains("!this.authoritativelyEstablishedAbilities.contains(local)"),
                "created=[] may retire actual client-only creations, but never an existing or authority-restored ability");
    }

    @Test
    void delayedEarthSmashCheckpointCannotRewindANewerGrabOrThrow() throws IOException {
        final String runtime = runtime();
        final String transfer = method(runtime, "private void transferAuthoritativeAbility0",
                "private void recordAbilityRemoval");
        final String smashSource = source(
                "../common/src/main/java/com/projectkorra/projectkorra/earthbending/EarthSmash.java");
        final String checkpointMatch = method(smashSource,
                "public boolean matchesPredictionCheckpoint",
                "private static BlockData predictionBlockData");

        assertTrue(transfer.contains("action.previousAbilityActions.containsKey(candidate)"),
                "a checkpoint must find the exact smash even after a newer local transition moved it out of the older action");
        assertTrue(transfer.contains("latestTransition > localSequence")
                        && transfer.contains("if (!checkpointSuperseded)")
                        && transfer.contains("this.associateAbility(action, selected)"),
                "an older checkpoint must neither overwrite nor re-associate a smash already advanced by a newer input");
        assertTrue(checkpointMatch.contains("final boolean confirmsCurrentState")
                        && checkpointMatch.contains("checkpointState == State.GRABBED && this.state == State.LIFTED")
                        && checkpointMatch.contains("this.currentBlocks.size() != transfer.blocks().size()")
                        && checkpointMatch.contains("expected.material().equals"),
                "moving checkpoints are confirmations by logical state and shape, not stale network-time coordinates");
        final String movingMatch = checkpointMatch.substring(
                checkpointMatch.indexOf("final boolean confirmsCurrentState"));
        assertFalse(movingMatch.contains("this.location"),
                "GRABBED/SHOT/FLYING checkpoints must not compare or replace the locally advanced center");
        assertTrue(smashSource.contains("private boolean redrawTransferredShape")
                        && smashSource.contains("this.drawTransferredShapeIfNeeded()")
                        && smashSource.contains("this.redrawTransferredShape = !this.currentBlocks.isEmpty()"),
                "a corrected stationary smash must repaint after transfer instead of remaining alive with zero layers");
    }

    @Test
    void earthSmashReleaseIsAnOrderedNativeTransition() throws IOException {
        final String smash = source(
                "../common/src/main/java/com/projectkorra/projectkorra/earthbending/EarthSmash.java");
        final String paper = source(
                "../bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        final String input = source(
                "../common/src/main/java/com/projectkorra/projectkorra/listener/CommonInputHandler.java");
        final String activation = source(
                "../common/src/main/java/com/projectkorra/projectkorra/ability/activation/CoreAbilityActivationBootstrap.java");
        final String constructor = method(smash,
                "public EarthSmash(final Player player, final ClickType type)",
                "/** Whether this constructor invocation changed an already-running smash. */");

        assertTrue(input.contains("new ActivationContext(player, bPlayer, type)"),
                "the common input path must dispatch SHIFT_UP on both runtimes");
        assertTrue(activation.contains("register(\"EarthSmash\", ClickType.SHIFT_UP")
                        && activation.contains("activateEarthSmash(context, ClickType.SHIFT_UP)"),
                "EarthSmash release must be registered as a native transition");
        assertTrue(constructor.indexOf("if (type == ClickType.SHIFT_UP)")
                        < constructor.indexOf("if (type == ClickType.SHIFT_DOWN)")
                        && constructor.indexOf("smash.transitionState(State.LIFTED)")
                        < constructor.indexOf("this.markActivationHandled(smash)"),
                "release must synchronously lift and identify the affected existing smash");
        assertFalse(paper.contains("kind != PaperPredictionProtocol.InputKind.SNEAK_STOP"),
                "Paper must checkpoint the synchronously completed release on its own action");
    }

    @Test
    void earthSmashIgnoresOnlyItsOwnLatencyDelayedPaperFootprint() throws IOException {
        final String smash = source(
                "../common/src/main/java/com/projectkorra/projectkorra/earthbending/EarthSmash.java");
        final String sync = source(
                "../common/src/main/java/com/projectkorra/projectkorra/prediction/block/TempBlockSync.java");
        final String transparent = method(smash, "private boolean isVisibleTransparent",
                "private boolean isAuthoritativeShapeBlock");

        assertTrue(sync.contains("hasAuthoritativeEffect(final Block block, final String ability,")
                        && sync.contains("ownerId.equals(current.authoritativeOwnerId(block))"),
                "the collision exception must authenticate the Paper layer owner");
        assertTrue(transparent.contains("this.isOwnAuthoritativeSmashBlock(block)")
                        && transparent.contains("this.player.getUniqueId()"),
                "a local smash may pass its delayed server footprint without passing another player's smash");
    }

    @Test
    void earthSmashClickDoesNotStartVanillaBlockBreakingOverlay() throws IOException {
        final String interaction = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ClientPlayerInteractionManagerPredictionMixin.java");
        final String mixins = source("src/main/resources/projectkorra.mixins.json");
        final String runtime = runtime();
        final String tempBlocks = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");

        assertTrue(mixins.contains("client.ClientPlayerInteractionManagerPredictionMixin")
                        && interaction.contains("method = {\"attackBlock\", \"updateBlockBreakingProgress\"}")
                        && interaction.contains("ExactPredictionRuntime.suppressLocalBlockBreaking")
                        && interaction.contains("callback.setReturnValue(false)"),
                "clicking a predicted smash must skip local mining while leaving Minecraft's subsequent hand swing intact");
        assertTrue(tempBlocks.contains("layer.getAbility().orElse(null) instanceof EarthSmash"),
                "the mining exception must be narrowly scoped to active EarthSmash TempBlocks");
        assertTrue(runtime.contains("INSTANCE.tempBlockAuthority.suppressBreakAnimation(world, pos)"),
                "Paper crack packets for the hidden authoritative smash must be suppressed as well");
    }

    @Test
    void waterSpoutCloseFenceResolvesTheLiveClientLayerWhenThePacketActuallyArrives() throws IOException {
        final String tempBlocks = source("src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        final String metadata = method(tempBlocks, "public void applyAuthoritativeBatch",
                "/** Runs an authoritative ability removal");
        final String packetFence = method(tempBlocks, "private CompletedRestore takeCompletedRestore",
                "public static <T> T completedRestoreState");

        assertTrue(metadata.contains("final boolean hiddenClosingLayer = server != null")
                        && metadata.contains("server.hiddenForLocalViewer"),
                "an owner-hidden lifecycle must fence its physical close even if differing overlap counts prevented an exact ordinal pair");
        assertTrue(metadata.contains("followLiveClientState = activeLocal != null"),
                "a paired owned close must remember that its visible state came from a live client layer");
        assertTrue(packetFence.contains("completed.followLiveClientState")
                        && packetFence.contains("clientState(key.world, key.pos)"),
                "the one-shot close fence must choose live water or its final underlay at packet time, not snapshot water at metadata time");
    }

    @Test
    void raiseEarthDoesNotExposeLatencyDelayedPaperFrames() throws IOException {
        final String tempBlocks = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        final String concealment = method(tempBlocks, "private boolean hidesServerLayer",
                "private void indexAuthoritative");
        final String metadata = method(tempBlocks, "public void applyAuthoritativeBatch",
                "/** Runs an authoritative ability removal");

        assertTrue(concealment.contains("authoritativeByCoordinate.get(key)")
                        && concealment.contains("server.hiddenForLocalViewer")
                        && concealment.contains("serverLayers.containsLayer(key, entry.getKey())"),
                "Paper's owned RaiseEarth frame must be hidden as soon as metadata arrives, without waiting for its per-layer ordinal pair");
        assertTrue(metadata.contains("previous.hiddenForLocalViewer"),
                "UPDATE_EXPIRY must not make a previously hidden moving lifecycle visible again");
    }

    @Test
    void earthSmashOwnershipTransferIsolatesSpeculativeLayerOrdinals() throws IOException {
        final String runtime = runtime();
        final String transfer = method(runtime, "private void transferAuthoritativeAbility0",
                "private void recordAbilityRemoval");
        final String tempBlocks = source("src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        final String smash = source("../common/src/main/java/com/projectkorra/projectkorra/earthbending/EarthSmash.java");
        final String paper = source("../bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        final String handoff = method(paper, "public void onOwnerTransferred",
                "public void onCheckpoint");

        assertTrue(transfer.contains("action.tempBlockOrdinal = Math.max(0, transfer.tempBlockOrdinal())")
                        && !transfer.contains("resetAbilityLayerIdentity"),
                "the payload must establish the first shared ordinal without mutating generic TempBlock records");
        assertTrue(smash.contains("private boolean awaitingPredictionTransfer")
                        && smash.contains("return !this.awaitingPredictionTransfer")
                        && smash.contains("new EarthSmash(player, transfer, true)"),
                "latency-offset client frames must render as an explicitly provisional ownership preview");
        assertTrue(tempBlocks.contains("!change.ability().tracksPredictedTempBlocks()")
                        && tempBlocks.contains("stableEarthSmashSlot")
                        && !tempBlocks.contains("closestUnpairedEarthSmash")
                        && !tempBlocks.contains("resetAbilityLayerIdentity"),
                "the ledger must isolate previews and pair confirmed EarthSmash pieces without frame guessing");
        assertTrue(handoff.contains("tempLayerActions.remove(layer.getLayerId())")
                        && handoff.contains("tempLayerEffects.remove(layer.getLayerId())")
                        && handoff.contains("ownershipBridgeTempLayers.add(layer.getLayerId())")
                        && !handoff.contains("tempLayerActions.put(layer.getLayerId(), transferAction)"),
                "Paper's pre-transfer shape must remain a visible bridge outside the new action namespace");
        assertTrue(paper.contains("ownershipBridgeTempLayers.contains(change.layerId())")
                        && paper.contains("? null : predictedTempBlockOwner"),
                "re-owning a live server layer must not conceal it before the exact continuation begins");
    }

    @Test
    void earthGloveUsesANonCollectibleStoneDisplayModel() throws IOException {
        final String glove = source(
                "../common/src/main/java/me/moros/hyperion/abilities/earthbending/EarthGlove.java");
        final String build = method(glove, "private BlockDisplay buildGlove",
                "private boolean advanceGlove");
        final String movement = method(glove, "private boolean advanceGlove",
                "private void teleportGlove");

        assertTrue(glove.contains("private BlockDisplay glove")
                        && glove.contains("private static final List<GlovePart> GLOVE_MODEL")
                        && build.contains("spawn(spawnLocation, BlockDisplay.class)"),
                "EarthGlove must render as a composite BlockDisplay fist");
        assertFalse(glove.contains("dropItem(") || glove.contains("new ItemStack("),
                "the visual must never be an inventory-pickup entity");
        assertTrue(build.contains("display.setBlock(stone)")
                        && build.contains("display.setGravity(false)"),
                "every palm, cuff, finger, and thumb piece must use the stone display material");
        assertTrue(movement.contains("COLLISION_SAMPLE_DISTANCE")
                        && movement.contains("!point.getBlock().isPassable()"),
                "changing to a no-hitbox display must retain the projectile's wall collision");
    }

    @Test
    void cooldownVetoStillRunsPaperComboBookkeeping() throws IOException {
        final String paper = source("../bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
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
        final String velocity = source("src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/movement/ClientVelocityAuthority.java");
        final String authoritative = method(velocity, "public boolean acceptAuthoritative",
                "public void recordOwner");
        final String tick = method(runtime, "private void tick0", "private void reconcileAuthoritativeCooldowns");
        final String paper = source("../bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        final String paperVelocity = method(paper, "public void onVelocity(Ability ability,",
                "public void beforeWrite(final CoreAbility ability");

        assertTrue(authoritative.contains("stageExternal") && authoritative.contains("return true;"),
                "an externally owned velocity packet for the local player must be staged instead of being overwritten by this tick's scooter/jet progress");
        assertTrue(authoritative.contains("stageUnowned"),
                "vanilla/outside knockback without an ownership receipt must use the same server-authority fence");
        assertTrue(runtime.contains("velocityAuthority.blocksPredictedWrite")
                        && velocity.contains("externalFence.blocksPredictedWrite"),
                "a late Scooter/Jet progress write must remain fenced through the movement-consumption heartbeat");
        assertTrue(tick.indexOf("this.platform.tick();")
                        < tick.indexOf("this.velocityAuthority.afterLocalProgress"),
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
        assertTrue(Files.exists(path), "missing source: " + path);
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
