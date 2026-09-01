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

        assertTrue(transfer.contains("action.previousAbilityActions.containsKey(candidate)")
                        && transfer.contains("abilityTransitionActions.getOrDefault(candidate, Set.of())")
                        && transfer.contains(".contains(localSequence)"),
                "a checkpoint must find the exact smash even after reconciliation cleared a newer transition's rollback map");
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
        assertTrue(smash.contains("rememberAuthoritativeBridge(cluster.ownerId(), solid)")
                        && smash.contains("this.authoritativeBridgeBlocks.contains")
                        && smash.contains("this.authoritativeBridgeOwner"),
                "the brief transfer ordering gap may ignore only the exact foreign bridge that this preview adopted");
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
    void waterSpoutCloseRemainsAResolvableRenderOnlyLayerUntilThePacketArrives() throws IOException {
        final String tempBlocks = source("src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        final String metadata = method(tempBlocks, "private void applyAuthoritativeOperations",
                "private boolean advanceStream");
        final String packetFence = method(tempBlocks, "private CompletedRestore takeCompletedRestore",
                "public static <T> T completedRestoreState");
        final String packetObserver = method(tempBlocks, "public boolean acceptBlock",
                "/** Observes a vanilla chunk delta");
        final String visualState = method(tempBlocks, "private TempVisual tempVisual",
                "private void refreshVisual");

        assertTrue(metadata.contains("final boolean hiddenClosingLayer = hiddenBefore"),
                "an authenticated owned layer must keep its existing concealment fence through close even when coordinate drift prevented pairing");
        assertTrue(metadata.contains("followLiveClientState = local.key.equals(key) && localLayer != null"),
                "a paired close must remember whether its visible state still comes from a live local layer");
        assertTrue(packetFence.contains("completed.followLiveClientState")
                        && packetFence.contains("clientState(key.world, key.pos)")
                        && packetFence.contains("completedRestores.get(key)")
                        && packetFence.contains("completedRestores.remove(key, completed)"),
                "the removable close overlay must resolve live water or its final underlay at packet time");
        assertFalse(packetFence.contains(
                        "final CompletedRestore completed = completedRestores.remove(key)"),
                "an intermediate fluid update must not consume the expected same-coordinate close fence");
        assertTrue(visualState.contains("final CompletedRestore completed = completedRestores.get(key)")
                        && visualState.contains("TempVisual.handoff(completed.state)")
                        && packetObserver.contains("takeCompletedRestore(key, state)")
                        && packetObserver.contains("return false;"),
                "the overlay may bridge packet ordering, but vanilla must still install the authoritative close");
        assertFalse(tempBlocks.contains("setBlockState("),
                "a delayed WaterSpout close can no longer strand a physical client block");
    }

    @Test
    void raiseEarthHidesItsAuthenticatedPaperFootprintAcrossCoordinateDrift()
            throws IOException {
        final String tempBlocks = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
        final String concealment = method(tempBlocks, "private boolean hidesServerLayer",
                "private void indexAuthoritative");
        final String metadata = method(tempBlocks, "private void applyAuthoritativeOperations",
                "private boolean advanceStream");

        assertTrue(concealment.contains(
                        "serverLayers.hidesServerWorld(key, player.getUuid())")
                        && concealment.contains(
                        "!hasLocalActionConcealment(server.actionSequence)"),
                "Paper's ownership may hide its delayed footprint only while the mapped local action remains live/recent");
        assertFalse(concealment.contains("hasSemanticPair(key)"),
                "coordinate drift must not expose Paper's duplicate while semantic reconciliation catches up");
        assertTrue(metadata.contains("if (effect != null && locallyOwned)")
                        && metadata.contains("tryMatchServer(operation.layerId(), server)")
                        && metadata.contains("final boolean hiddenAfter = hidesServerLayer(key)")
                        && metadata.contains("refreshVisual(key)"),
                "ownership controls concealment immediately while exact effect identity remains the lifecycle reconciliation key");
    }

    @Test
    void raiseEarthWallGeometryIsCapturedByTheShiftAction() throws IOException {
        final String wall = source(
                "../common/src/main/java/com/projectkorra/projectkorra/earthbending/RaiseEarthWall.java");
        final String constructor = method(wall, "public RaiseEarthWall(final Player player)",
                "private boolean captureWall()");
        final String capture = method(wall, "private boolean captureWall()",
                "private static Vector getDegreeRoundedVector");
        final String progress = method(wall, "public void progress()", "public Location getLocation()");

        assertTrue(constructor.indexOf("this.captureWall()") < constructor.indexOf("this.start()"),
                "the source and wall basis must be fixed while the exact input pose is current");
        assertTrue(capture.contains("this.player.getEyeLocation().getDirection()")
                        && capture.contains("BlockSource.getEarthSourceBlock(")
                        && capture.contains("this.wallDirection = orthogonal"),
                "the input frame must retain both source selection and horizontal wall direction");
        assertFalse(progress.contains("getEyeLocation()")
                        || progress.contains("BlockSource.getEarthSourceBlock("),
                "a latency-shifted progress tick must never resample player aim or source");
    }

    @Test
    void raiseEarthWallStartsOnlyOnePillarPerSurfaceColumn() throws IOException {
        final String wall = source(
                "../common/src/main/java/com/projectkorra/projectkorra/earthbending/RaiseEarthWall.java");
        final String progress = method(wall, "public void progress()", "public Location getLocation()");
        final String normalized = progress.replace("\r\n", "\n");

        assertTrue(progress.contains("final Set<Block> startedSources = new HashSet<>()")
                        && count(progress, "this.raiseColumn(") == 3,
                "all terrain branches must share one source-coordinate deduplication set");
        final int firstSurface = normalized.indexOf("this.raiseColumn(block, startedSources)");
        final int firstBranchEnd = normalized.indexOf(
                "} else if (!this.isTransparent(block))", firstSurface);
        final int raisedSurface = normalized.indexOf(
                "block.getRelative(BlockFace.DOWN), startedSources");
        final int raisedBranchEnd = normalized.indexOf(
                "} else if (!this.isEarthbendable(block))", raisedSurface);
        final int firstBreak = normalized.indexOf("break;", firstSurface);
        final int raisedBreak = normalized.indexOf("break;", raisedSurface);
        assertTrue(firstSurface >= 0 && firstBreak > firstSurface
                        && firstBreak < firstBranchEnd
                        && raisedSurface >= 0 && raisedBreak > raisedSurface
                        && raisedBreak < raisedBranchEnd,
                "surface searches must stop after their first usable source instead of stacking overlapping pillars");
        assertTrue(progress.contains("!startedSources.add(source)")
                        && count(progress, "new RaiseEarth(") == 1,
                "rounded wall samples that resolve to the same block must start only one pillar");
    }

    @Test
    void earthSmashCannotStartASecondTransactionDuringItsLift() throws IOException {
        final String smash = source(
                "../common/src/main/java/com/projectkorra/projectkorra/earthbending/EarthSmash.java");
        final String constructor = method(smash,
                "public EarthSmash(final Player player, final ClickType type)",
                "private void markActivationHandled");
        final int guard = constructor.indexOf(
                "smash.state == State.START || smash.state == State.LIFTING");
        final int start = constructor.indexOf("this.start();", guard);

        assertTrue(guard >= 0 && start > guard,
                "another shift-down must be consumed before a second EarthSmash can start");
        final String guardBody = constructor.substring(guard, start);
        assertFalse(guardBody.contains("markActivationHandled"),
                "a no-op press must not rebind the rising smash to a newer action sequence");
    }

    @Test
    void iceSpikeAlwaysClosesItsExactLayers() throws IOException {
        final String spike = source(
                "../common/src/main/java/com/projectkorra/projectkorra/waterbending/ice/IceSpikePillar.java");
        final String sink = method(spike, "public boolean sinkPillar()", "public String getName()");

        assertTrue(spike.contains("getIceMaterial().createBlockData(), this"),
                "field-created spikes must advertise their explicit IceSpike TempBlock owner");
        assertTrue(sink.contains("this.ice_blocks.remove(this.location.getBlock())")
                        && sink.contains("this.location.add(direction)"),
                "an overlap-created gap must not stall the sinking cursor forever");
        assertTrue(sink.contains("public void remove()")
                        && sink.contains("new ArrayList<>(this.ice_blocks.values())")
                        && sink.contains("layer.revertBlock()")
                        && sink.contains("this.ice_blocks.clear()"),
                "forced rejection/removal must close every remaining exact layer");
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
                        && !handoff.contains("tempLayerActions.put(layer.getLayerId(), transferAction)"),
                "Paper's pre-transfer shape must remain outside the new action ordinal namespace");
        assertTrue(paper.contains("final UUID predictedOwner = unpredictedOwnershipTransfer")
                        && paper.contains("? null : predictedTempBlockOwner")
                        && !paper.contains("ownershipBridgeTempLayers"),
                "after the exact transfer is sent, the ownership refresh must make the old bridge concealable instead of leaving two visible smashes");
        final String concealment = method(tempBlocks, "private boolean hidesServerLayer",
                "private void indexAuthoritative");
        assertTrue(tempBlocks.contains("viewerId.equals(operation.ownerId())")
                        && tempBlocks.contains("tryMatchServer(operation.layerId(), server)")
                        && tempBlocks.contains("pairedServerLayers.put(serverLayerId, localLayerId)")
                        && concealment.contains(
                        "serverLayers.hidesServerWorld(key, player.getUuid())")
                        && concealment.contains(
                        "!hasLocalActionConcealment(server.actionSequence)"),
                "authenticated ownership hides the delayed bridge only while its mapped local continuation remains live/recent");
        assertFalse(concealment.contains("hasSemanticPair(key)"),
                "EarthSmash coordinate drift must not make the server duplicate visible before exact pairing completes");
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

    private static int count(final String source, final String needle) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
