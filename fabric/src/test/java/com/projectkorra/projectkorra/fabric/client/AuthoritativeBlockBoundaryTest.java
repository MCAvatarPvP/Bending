package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards block ownership and reconciliation after prediction was modularized. */
class AuthoritativeBlockBoundaryTest {
    @Test
    void ordinaryWritesUseCausalDirectBlockAuthority() throws IOException {
        final String direct = source("src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientDirectBlockAuthority.java");
        final String common = source("../common/src/main/java/com/projectkorra/projectkorra/prediction/block/DirectBlockSync.java");
        final String paper = source("../bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");

        assertTrue(direct.contains("DirectBlockSync.isPredictable(ability, abilityName)"));
        assertTrue(direct.indexOf("++causeState.lastOrdinal")
                < direct.indexOf("if (state.equals(before)) return"));
        assertTrue(direct.contains("predictedWrites.put(effect")
                        && direct.contains("visualOverlay.set(ClientBlockVisualOverlay.Layer.DIRECT"),
                "causal writes must update the removable direct-render overlay");
        assertFalse(direct.contains("setBlockState("),
                "direct prediction must never mutate authoritative ClientWorld block storage");
        assertTrue(direct.contains("DirectBlockAuthorityPolicy.mayConceal(")
                && direct.contains("normalized, local != null, receipt.movedEarthLifecycle(), knownCause"));
        assertTrue(direct.contains("concealed unmatched moved-earth")
                        && direct.contains("concealed divergent causal write"),
                "latency-offset moved-earth writes must preserve the common client's complete visual transaction");
        final String inferred = method(direct, "private void retireConvergedTransactions",
                "private static boolean requiresAuthoritativeHandoff");
        final String explicit = method(direct, "public int completeAuthoritativeFrames",
                "public int mutationCount()");
        assertTrue(direct.contains("retireConvergedTransactions(tick)")
                        && inferred.contains("serverState.equals(")
                        && !inferred.contains("setBlockState("),
                "elapsed-time convergence may release equal masks but must never repaint a delayed Paper frame");
        assertTrue(explicit.contains("state.authoritativeFrameComplete = true")
                        && explicit.contains("refreshVisual(key)")
                        && !explicit.contains("setBlockState("),
                "the ordered final RaiseEarth receipt may retire its overlay but may not repaint ClientWorld");
        assertTrue(direct.contains("lastReceiptTick = context.tick()")
                        && direct.contains("tick - lastActivity <= convergenceDelay"),
                "convergence must wait for both local progress and delayed Paper receipts to become quiet");
        final String expiry = method(direct, "public void expire(", "public void rollbackAction");
        assertTrue(expiry.contains("converged ? actionRetentionTicks : earthCauseRetentionTicks")
                        && expiry.contains("retainsActiveMask(entry.getKey(), mask, transactionWide)")
                        && expiry.contains("backingDiverged")
                        && expiry.contains("causalPacketPending")
                        && expiry.contains("!retainsObservedCoalescedFrame(mask)")
                        && expiry.contains("serverMasks.remove(entry.getKey(), mask)")
                        && expiry.contains("releaseVisual(entry.getKey(), mask, false)"),
                "a backing-state conflict must fail open only after pending causal packets and the coalescing window are exhausted");
        assertTrue(direct.contains("if (mask != null) return mask.viewerState")
                        && direct.contains("mutation != null && mutation.locallyPredicted")
                        && direct.contains("? mutation.predicted : world.getBlockState(pos)"),
                "common prediction reads must compose the durable direct visual over vanilla authority");
        assertTrue(common.contains("final boolean packetExpected")
                && common.indexOf("final boolean packetExpected")
                < common.indexOf("current.beforeChange"));
        assertTrue(paper.indexOf("session.directBlockOrdinals.merge")
                < paper.indexOf("if (!packetExpected) return;"));
    }

    @Test
    void commonTempBlocksBypassDirectMutationTracking() throws IOException {
        final String runtime = runtime();
        final String temp = tempAuthority();

        assertTrue(runtime.contains("TempBlockSync.currentWorldMutation() != null")
                && runtime.contains("tempBlockAuthority.predict"));
        assertTrue(temp.contains("directBlocks.removeMutation(world, pos)"));
        assertTrue(temp.contains("runtime applied render-only client TempBlock")
                        && temp.contains("visualOverlay.set(ClientBlockVisualOverlay.Layer.TEMP"));
        assertFalse(temp.contains("setBlockState("),
                "TempBlock prediction and reconciliation must never paint ClientWorld");
        assertFalse(temp.contains("mutations.computeIfAbsent"));
        assertTrue(temp.contains("pendingUnderlays")
                && temp.contains("change.underlayData()")
                && temp.contains("local.authoritativeUnderlay = authoritativeState"));
        assertTrue(runtime.contains("discardingWorldState")
                && runtime.contains("TempFallingBlock.discardAll()")
                && runtime.contains("CoreAbility.discardAllInstances()"));
    }

    @Test
    void initialResetCannotLoadGameplayBeforePlatformInstallation() throws IOException {
        final String runtime = runtime();
        final String stop = method(runtime, "private void stop0", "public boolean isAuthoritative()");
        final int stateCheck = stop.indexOf("if (!this.commonRuntimeInstalled)");
        final int installedBranch = stop.indexOf("} else {", stateCheck);
        final int coreAccess = stop.indexOf("CoreAbility.getAbilitiesByInstances()", installedBranch);
        assertTrue(stateCheck >= 0 && installedBranch > stateCheck && coreAccess > installedBranch);
        assertTrue(runtime.contains("Platform.install(this.platform)")
                && runtime.contains("this.commonRuntimeInstalled = true"));
    }

    @Test
    void serverBlockTrafficAlwaysEntersAuthoritativeClientWorld() throws IOException {
        final String temp = tempAuthority();
        final String mixin = source("src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ClientPlayNetworkHandlerPredictionMixin.java");

        final String blockUpdate = method(mixin,
                "@Inject(method = \"onBlockUpdate\"", "@Inject(method = \"onChunkDeltaUpdate\"");
        final String chunkDelta = method(mixin,
                "@Inject(method = \"onChunkDeltaUpdate\"", "@Inject(method = \"onChunkData\"");
        final String chunkData = method(mixin,
                "@Inject(method = \"onChunkData\"", "@Inject(method = \"onEntityVelocityUpdate\"");
        final String blockPacketHooks = blockUpdate + chunkDelta + chunkData;

        assertFalse(blockPacketHooks.contains("cancellable = true")
                        || blockPacketHooks.contains("ci.cancel()"),
                "block and chunk packets must always reach vanilla ClientWorld storage");
        assertTrue(blockUpdate.contains("ExactPredictionRuntime.authoritativeBlock(")
                        && chunkDelta.contains("ExactPredictionRuntime.authoritativeBlockBatch(")
                        && chunkData.contains("ExactPredictionRuntime.acceptAuthoritativeChunk("),
                "packet hooks may observe authority only for overlay bookkeeping");
        final String single = method(temp, "public boolean acceptBlock", "/** Observes a vanilla chunk delta");
        final String batch = method(temp, "public boolean acceptBatch", "public void acceptChunk");
        final String chunk = method(temp, "public void acceptChunk", "private CompletedRestore takeCompletedRestore");
        assertTrue(single.contains("return false;") && batch.contains("return false;"));
        assertFalse((single + batch + chunk).contains("setBlockState("),
                "packet observation may refresh overlays but may not replace vanilla state");
        assertTrue(temp.contains("directBlocks.restoreChunk"));
        assertTrue(temp.contains("TempBlock.getActiveLayers()"));
        assertFalse(temp.contains("discardUnconfirmedClientTempStack"));
    }

    @Test
    void visualOverlayComposesTempAboveDirectInChunkRendering() throws IOException {
        final String runtime = runtime();
        final String direct = directAuthority();
        final String temp = tempAuthority();
        final String overlay = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientBlockVisualOverlay.java");
        final String renderer = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ChunkRendererRegionPredictionMixin.java");
        final String mixins = source("src/main/resources/projectkorra.mixins.json");
        final String terrainState = method(runtime,
                "public static BlockState visualBlockState",
                "public static boolean hasBlockVisualOverrides");

        assertTrue(overlay.contains("final TempLayer tempLayer")
                        && overlay.contains("final BlockState directState")
                        && overlay.contains("!tempLayer.delegatesToDirect")
                        && overlay.contains("|| directState == null"),
                "ordinary TEMP must remain above DIRECT, while an explicitly concealed owner layer reveals DIRECT beneath it");
        assertTrue(overlay.contains("ConcurrentMap<BlockKey, BlockState>")
                        && overlay.contains("ConcurrentMap<BlockKey, TempLayer>")
                        && overlay.contains("scheduleBlockRenders("),
                "render workers need independently clearable, atomic, rebuild-aware overlay snapshots");
        assertTrue(renderer.contains("@Mixin(ChunkRendererRegion.class)")
                        && renderer.contains("ExactPredictionRuntime.visualBlockState(")
                        && renderer.contains("cir.setReturnValue")
                        && renderer.contains("getBlockState(pos).getFluidState()"),
                "terrain and fluid meshes must consume the composed render state");
        assertTrue(mixins.contains("client.ChunkRendererRegionPredictionMixin")
                        && runtime.contains("new ClientBlockVisualOverlay()")
                        && terrainState.contains(
                        "blockVisualOverlay.composeTerrain(world, pos, authoritativeState)"));
        assertFalse(direct.contains("setBlockState(") || temp.contains("setBlockState("),
                "neither prediction authority may retain a physical-paint escape hatch");
    }

    @Test
    void localTempModelsRenderEveryFrameWithoutWaitingForAChunkBuild() throws IOException {
        final String overlay = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientBlockVisualOverlay.java");
        final String authority = tempAuthority();
        final String renderer = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionBlockVisualRenderer.java");
        final String client = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/ProjectKorraClientMod.java");
        final String network = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ClientPlayNetworkHandlerPredictionMixin.java");

        assertTrue(overlay.contains("public void setImmediateTemp(")
                        && overlay.contains("public void beginTempHandoff(")
                        && overlay.contains("public BlockState composeTerrain(")
                        && overlay.contains("return Blocks.AIR.getDefaultState();"),
                "foreground TempBlocks must be cut out of the terrain mesh with an explicit handoff API");
        assertFalse(overlay.contains(
                        "visual.active() && visual.state.equals(world.getBlockState(key.pos))")
                        || overlay.contains("if (!state.equals(world.getBlockState(key.pos)))"),
                "a matching delayed server block must not retire an active local projectile into terrain");
        assertTrue(overlay.contains("foreground.put(key, ForegroundVisual.active(state))")
                        && overlay.contains("foregroundMode == ForegroundMode.HANDOFF")
                        && overlay.contains("ForegroundMode.HANDOFF, true"),
                "only explicit local lifecycle state may enter handoff, and a closed TEMP reveals the current lower layer");
        assertTrue(overlay.contains("foreground.awaitingTerrainRead()")
                        && overlay.contains("foreground.terrainRead(")
                        && overlay.contains("FOREGROUND_HANDOFF_FAILSAFE_NANOS")
                        && overlay.contains("removeDirectWithHandoff(")
                        && overlay.contains("scheduleRebuild(key)"),
                "release grace must begin after terrain consumption, survive logical removal only when states agree, and rebuild on expiry");
        assertTrue(authority.contains("TempVisualProvenance.ACTIVE_LOCAL")
                        && authority.contains("visualOverlay.setImmediateTemp(")
                        && authority.contains("TempVisualProvenance.LOCAL_HANDOFF")
                        && authority.contains("visualOverlay.beginTempHandoff("),
                "only provenance-proven local TEMP states may enter the foreground renderer");
        assertTrue(renderer.contains("WorldRenderEvents.END_EXTRACTION.register")
                        && renderer.contains("List.copyOf(extracted)")
                        && renderer.contains("WorldRenderEvents.BEFORE_ENTITIES.register")
                        && renderer.contains("context.commandQueue().submitBlockStateModel(")
                        && renderer.contains("FOREGROUND_SCALE"),
                "moving prediction must use immutable extraction data and Fabric's per-frame ordered queue");
        assertTrue(client.contains("PredictionBlockVisualRenderer.initialize();"),
                "the foreground renderer must be registered by the client entrypoint");
        assertTrue(network.contains("@Inject(method = \"onBlockUpdate\", at = @At(\"TAIL\"))")
                        && network.contains("@Inject(method = \"onChunkDeltaUpdate\", at = @At(\"TAIL\"))"),
                "packet convergence must be observed after ClientWorld installs its authoritative state");
    }

    @Test
    void predictedCollisionViewIsLimitedToTheLocalPlayerAndOwnedFallingBlocks()
            throws IOException {
        final String runtime = runtime();
        final String collisionState = method(runtime,
                "public static BlockState collisionBlockState",
                "public static void setPredictedBlock");
        final String collisionMixin = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/BlockCollisionSpliteratorPredictionMixin.java");
        final String mixins = source("src/main/resources/projectkorra.mixins.json");

        assertTrue(collisionMixin.contains("@Mixin(BlockCollisionSpliterator.class)")
                        && collisionMixin.contains("method = \"computeNext\"")
                        && collisionMixin.contains(
                        "Lnet/minecraft/world/BlockView;getBlockState")
                        && collisionMixin.contains("chunk.getBlockState(pos)"),
                "the collision hook must compose over the spliterator's original chunk result");
        assertTrue(collisionMixin.contains("this.world instanceof ClientWorld")
                        && collisionMixin.contains("this.context instanceof EntityShapeContext")
                        && collisionMixin.contains("entityContext.getEntity()"),
                "non-client and entity-free collision queries must retain vanilla authority");
        assertTrue(collisionState.contains("!INSTANCE.ready")
                        && collisionState.contains("entity != client.player")
                        && collisionState.contains("entity instanceof FallingBlockEntity")
                        && collisionState.contains("isPredictedOwned(entity)"),
                "remote entities and unrelated predicted visuals may not collide with local overlays");
        assertTrue(collisionState.contains(
                        "blockVisualOverlay.compose(world, pos, authoritativeState)"),
                "owned movement should consume the same immutable overlay used by rendering");
        assertFalse(collisionState.contains("blockState(world")
                        || collisionState.contains("setBlockState("),
                "collision composition must not recurse through the common block adapter or mutate chunks");
        assertTrue(mixins.contains("client.BlockCollisionSpliteratorPredictionMixin"));
    }

    @Test
    void metadataPairsOnlyExactSemanticTempBlockEffects() throws IOException {
        final String temp = tempAuthority();
        assertTrue(temp.contains("context.localActionSequence(operation.actionSequence())"));
        assertTrue(temp.contains("operation.effectAbility()")
                && temp.contains("operation.effectStep()")
                && temp.contains("operation.effectOrdinal()"));
        assertTrue(temp.contains("authoritativeEffects.get(local.effect)")
                && temp.contains("localEffects.get(server.effect)"));
        assertTrue(temp.contains("pairedCoordinates.computeIfAbsent(server.key"));
        assertTrue(temp.contains("local.serverClosed = true"));
        assertFalse(temp.contains("findLocalTempBlockCandidate")
                || temp.contains("MAX_TEMP_BLOCK_STEP_SKEW")
                || temp.contains("TempBlock.removeBlock("));
    }

    @Test
    void teardownClearsVisualLayersInsteadOfRepairingClientWorld() throws IOException {
        final String runtime = runtime();
        final String temp = tempAuthority();
        final String direct = directAuthority();
        final String tempClear = method(temp, "public void clear()", "private Map<BlockKey, CapturedLifecycle>");
        final String directClear = method(direct, "public void clear()", "private void record(");
        final String removal = method(temp, "private void finalizeAbilityRemoval",
                "private ServerLayer topAuthoritative");

        assertTrue(tempClear.contains("visualOverlay.clear(ClientBlockVisualOverlay.Layer.TEMP)")
                        && directClear.contains("visualOverlay.clear(ClientBlockVisualOverlay.Layer.DIRECT)"),
                "runtime teardown must invalidate both independently owned visual layers");
        assertTrue(removal.contains("refreshVisual(key)") && !removal.contains("setBlockState("),
                "ability removal may rebuild visuals but never restore a captured physical state");
        assertFalse(temp.contains("teardownFences") || temp.contains("TempBlockTeardownFence"),
                "render-only state does not need durable packet-rejection fences");
        assertTrue(runtime.contains("tempBlockAuthority.removeAbility"));
        assertTrue(runtime.contains("tempBlockAuthority.afterLocalProgress(client.world)"));
        assertTrue(runtime.contains("this.directBlockAuthority.clear()")
                        && runtime.contains("this.tempBlockAuthority.clear()"));
    }

    @Test
    void legacyRollbackMachineryCannotReturn() throws IOException {
        final String runtime = runtime();
        final String direct = directAuthority();
        final String temp = tempAuthority();
        assertFalse(runtime.contains("OwnedTempReceipt")
                || runtime.contains("invalidateClientTempStack")
                || runtime.contains("findNearestDirectBlock"));
        assertTrue(direct.contains("record EffectKey(long actionSequence, String ability, int mutationOrdinal)"));
        assertTrue(temp.contains("ClientTempBlockLedger<BlockKey, BlockState> serverLayers"));
        assertTrue(runtime.contains("return List.of()"));
    }

    @Test
    void directEarthViewSurvivesPacketsAndChunksUntilLifecycleRestore() throws IOException {
        final String direct = directAuthority();
        final String earthBlast = source("../common/src/main/java/com/projectkorra/projectkorra/earthbending/EarthBlast.java");
        assertTrue(direct.contains("Map<BlockKey, DirectMask> serverMasks"));
        assertTrue(direct.contains("serverMasks.put(serverKey, new DirectMask"));
        assertTrue(direct.contains("mask.serverState.equals(chunkState)"));
        assertTrue(direct.contains("context.hasActiveAbility")
                && direct.contains("EarthAbility.getMovedEarth()")
                && direct.contains("EarthAbility.getTempAirLocations()"));
        assertTrue(direct.contains("DirectVisualOrderPolicy.select(")
                        && direct.contains("hasPendingConfirmed(key, incoming)")
                        && direct.contains("invalidateReleasedLocalVisual"),
                "live revisions and pending physical receipts must be the only direct-view fences");
        assertTrue(earthBlast.contains("this.sourceBlock.setType(Material.STONE)")
                && earthBlast.contains("this.sourceBlock.setType(this.sourceType)"));
    }

    @Test
    void risingEarthAndSmashSourceHolesSurviveLatencyOffsetReceipts() throws IOException {
        final String direct = directAuthority();
        final String raise = source(
                "../common/src/main/java/com/projectkorra/projectkorra/earthbending/RaiseEarth.java");
        final String earth = source(
                "../common/src/main/java/com/projectkorra/projectkorra/ability/EarthAbility.java");
        final String smash = source(
                "../common/src/main/java/com/projectkorra/projectkorra/earthbending/EarthSmash.java");

        assertTrue(raise.contains("this.moveEarth(block, this.direction, this.distance)"));
        assertTrue(earth.contains("DirectBlockSync.runEarthLifecycle(info")
                        && earth.contains("DirectBlockSync.runEarthLifecycle(lifecycle"),
                "both the rising wall and temporary source air must retain their causal Earth transaction");
        assertTrue(smash.contains("addTempAirBlock(block)"),
                "EarthSmash's sampled shape depends on the same protected source-hole lifecycle");
        assertTrue(direct.contains("normalized, local != null, receipt.movedEarthLifecycle(), knownCause")
                        && direct.contains("concealed divergent causal write")
                        && direct.contains("concealed unmatched moved-earth physical write"),
                "Paper's delayed coordinates and ordinals must fence packets without replacing the local visual");
        final String expiry = method(direct, "public void expire", "public void rollbackAction");
        assertTrue(expiry.contains("retireConvergedTransactions(tick)"),
                "RaiseEarth reconciliation must run before durable mask expiry");
        assertTrue(direct.contains("requiresAuthoritativeHandoff(cause.ability)")
                        && direct.contains("context.hasActiveAbility(cause.actionSequence, cause.ability)")
                        && direct.contains("entry.getValue().authoritative")
                        && direct.contains("entry.getValue().serverState"),
                "the responsive wall must remain intact until both simulations independently converge");
        assertTrue(direct.contains("hasActiveCause(entry.getValue().ownerId, cause)")
                        && direct.contains("serverMasks.remove(entry.getKey(), entry.getValue())"),
                "convergence must wait for the complete moved-earth lifecycle and only retire bookkeeping");
        assertTrue(direct.contains("DirectVisualOrderPolicy.select(")
                        && direct.contains(
                        "final CauseKey maskCause = retainObserved ? observedVisualCause")
                        && direct.contains("existingMask.visualRevision"),
                "an older delayed receipt may not take an overlapping coordinate back from the newer wall transaction");
        assertTrue(direct.contains("PACKET mask coalesced")
                        && direct.contains("awaitsAuthoritativeFrame(key, mask)"),
                "a receipt-adjacent coalesced multi-write packet may retain the predicted frame");
        final String pendingFrame = method(direct,
                "private boolean awaitsAuthoritativeFrame", "private BlockState clientBaseState");
        assertTrue(pendingFrame.contains("\"raiseearth\".equals(mask.serverCause.ability)")
                        && pendingFrame.contains("context.tick() - mask.serverReceiptTick")
                        && pendingFrame.contains("<= COALESCED_PACKET_GRACE_TICKS"),
                "RaiseEarth's coalescing exception must be short-lived so a later external edit fails open");
        final String runtime = runtime();
        assertTrue(runtime.contains("completesRaiseEarthFrame(")
                        && runtime.contains("removed.abilityType(), removed.remainingActionInstances())")
                        && runtime.contains("completeAuthoritativeFrames("),
                "the final concrete RaiseEarth removal must drive the ordered frame completion");
        assertTrue(runtime.indexOf("completeAuthoritativeFrames(")
                        < runtime.indexOf("removalReceiptMayResolve(", runtime.indexOf(
                        "private void removeAuthoritativeAbility0")),
                "the ordered frame must still complete after the initiating action ages out");
    }

    @Test
    void fallingBlocksRequireExactCasterReceiptNotProximity() throws IOException {
        final String entity = source("src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/entity/ClientEntityReconciliation.java");
        final String payloads = source("src/main/java/com/projectkorra/projectkorra/fabric/prediction/protocol/PredictionPayloads.java");
        final String common = source("../common/src/main/java/com/projectkorra/projectkorra/util/TempFallingBlock.java");

        assertTrue(entity.contains("new TempFallingBlockKey(")
                && entity.contains("localActionSequence, receipt.spawnOrdinal())"));
        assertTrue(entity.contains("pending.ability.equalsIgnoreCase(receipt.ability())"));
        assertTrue(entity.contains("Block.getStateFromRawId(packet.getEntityData())"));
        assertTrue(entity.contains("close(spawn, expected, 1.0E-7)"));
        assertTrue(entity.contains("tempFallingAliases.contains(serverEntityId)")
                && entity.contains("? null : authoritativeAliases.get(serverEntityId)"));
        assertTrue(payloads.contains("record TempFallingBlockReceipt")
                && payloads.contains("record TempFallingBlockPrepare"));
        assertTrue(common.indexOf("TempFallingBlockSync.prepare")
                < common.indexOf("spawnFallingBlock"));
    }

    private static String runtime() throws IOException {
        return source("src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
    }

    private static String directAuthority() throws IOException {
        return source("src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientDirectBlockAuthority.java");
    }

    private static String tempAuthority() throws IOException {
        return source("src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/block/ClientTempBlockAuthority.java");
    }

    private static String source(final String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path)) path = Path.of("fabric").resolve(relative);
        assertTrue(Files.exists(path), "missing source: " + path);
        return Files.readString(path);
    }

    private static String method(final String source, final String start, final String end) {
        final int from = source.indexOf(start);
        final int to = source.indexOf(end, from);
        assertTrue(from >= 0 && to > from, "missing boundary " + start + " -> " + end);
        return source.substring(from, to);
    }
}
