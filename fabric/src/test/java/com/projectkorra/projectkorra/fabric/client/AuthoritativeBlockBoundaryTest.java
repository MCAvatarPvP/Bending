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
    void ordinaryWritesPaintOnlyCausallyReceiptedEarthMovement() throws IOException {
        String runtime = runtime();
        String ordinary = method(runtime, "private void setBlock0", "private BlockState simulatedBlockState0");
        String authority = method(runtime, "private boolean authoritativeBlock0",
                "private boolean authoritativeBlockBatch0");
        String directSync = source("../common/src/main/java/com/projectkorra/projectkorra/prediction/DirectBlockSync.java");

        assertTrue(ordinary.contains("blocks.computeIfAbsent"));
        assertTrue(ordinary.contains("mutation.predicted = state"));
        assertTrue(ordinary.contains("DirectBlockSync.isPredictable(ability, abilityName)"),
                "the shared EarthAbility policy must include core and addon earth movement without predicting FireBlast destruction");
        assertTrue(ordinary.contains("new DirectBlockEffectKey")
                        && ordinary.contains("world.setBlockState(pos, state, 19)"),
                "earth movement must rend`er immediately under an exact causal identity");
        int noOp = ordinary.indexOf("if (state.equals(before)) return;");
        int ordinal = ordinary.indexOf("++causeState.lastOrdinal");
        assertTrue(noOp >= 0 && ordinal >= 0 && ordinal < noOp,
                "semantic write attempts must stay aligned even when only one loader sees an Earth call as a no-op");
        assertTrue(directSync.contains("final boolean packetExpected")
                        && directSync.contains("current.beforeChange")
                        && directSync.indexOf("final boolean packetExpected")
                        < directSync.indexOf("current.beforeChange"),
                "Paper must advance the semantic ordinal for a no-op without arming a vanilla packet fence");
        String paper = source("../bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        String paperDirect = method(paper, "public void beforeChange", "private void scheduleTicker");
        assertTrue(paperDirect.indexOf("session.directBlockOrdinals.merge")
                        < paperDirect.indexOf("if (!packetExpected) return;"),
                "the packet gate must occur after the shared semantic ordinal advances");
        assertTrue(directSync.contains("runEarthLifecycle")
                        && directSync.contains("currentEarthLifecycle"),
                "moved-earth restores must retain causality after their creating ability has ended");
        assertTrue(runtime.contains("action == mutation.lastAction")
                        && runtime.contains("? mutation.predicted : world.getBlockState(pos)"),
                "only the owning common simulation may observe its read-after-write overlay");
        assertTrue(authority.contains("preserveClientTempBlockAuthority(key,"),
                "packet timing must not tear down a running client TempBlock");
        assertTrue(authority.contains("takeConfirmedDirectBlock(key, state)"),
                "only a server receipt with the same action/name/ordinal may hide its vanilla echo");
        assertTrue(authority.contains("confirmDirectBlockFromVanilla(key, state)"),
                "receipt loss must not later restore over a matching vanilla result");
    }

    @Test
    void commonTempBlocksBypassTheGenericPredictionLedger() throws IOException {
        String runtime = runtime();
        String direct = method(runtime, "private void setTempBlock0", "private void setBlock0");

        assertTrue(runtime.contains("if (TempBlockSync.currentWorldMutation() != null)"));
        assertTrue(runtime.contains("INSTANCE.setTempBlock0(world, pos.toImmutable(), state)"));
        assertTrue(direct.contains("world.setBlockState(pos, visibleState, 19)"));
        assertTrue(direct.contains("blocks.remove(key)"));
        assertFalse(direct.contains("blocks.computeIfAbsent"),
                "TempBlocks must never enter the timeout/correction ledger");
        assertFalse(direct.contains("new BlockEcho"),
                "TempBlocks must never manufacture vanilla receipt expectations");
        assertTrue(runtime.contains("clientTempBlockActions"),
                "local layer identity must be retained for semantic confirmation");
        assertTrue(runtime.contains("actionSequence = abilityActions.getOrDefault(change.ability(), 0L)"),
                "manager/callback-created layers must inherit their explicit ability's prediction action");
        assertTrue(runtime.contains("new LocalTempBlockPrediction(actionSequence, key, effect, tick"));
        assertTrue(runtime.contains("TempBlockEffectKey"));
        assertTrue(runtime.contains("discardingWorldState = true")
                        && runtime.contains("GeneralMethods.stopBending()")
                        && runtime.contains("TempFallingBlock.discardAll()")
                        && runtime.contains("CoreAbility.discardAllInstances()")
                        && runtime.contains("AirAbility.discardAllAirbendingState()")
                        && runtime.contains("EarthAbility.discardAllEarthbendingState()")
                        && runtime.contains("WaterAbility.discardAllWaterbendingState()")
                        && runtime.contains("FireAbility.discardAllFirebendingState()")
                        && runtime.contains("BlockSource.clearAll()")
                        && runtime.contains("RevertChecker.discardAll()"),
                "world/runtime shutdown must drain every elemental and falling-block registry while block writes are disabled");
        assertTrue(runtime.contains("if (INSTANCE.discardingWorldState) return;"),
                "normal cleanup callbacks may run on shutdown but may never repaint the outgoing ClientWorld");
        assertTrue(direct.contains("BlockState visibleState = state"),
                "a predicted close must keep its own underlay instead of replaying Paper's delayed physical layer");
        assertTrue(runtime.contains("closedClientTempBlockState"),
                "closed short-lived layers must remain semantic tombstones until Paper confirms their close");
        assertTrue(runtime.contains("pendingTempBlockUnderlays")
                        && runtime.contains("public void beforeWorldChange(final TempBlockSync.Change change)"),
                "pre-write DISCARD must retain the local underlay before ordinary writes invalidate the stack");
        assertTrue(runtime.contains("change.underlayData()")
                        && runtime.contains("local.authoritativeUnderlay = authoritativeState"),
                "overlapping external handoffs must reveal the one rebased stack base, never an intermediate layer");
        assertTrue(runtime.contains("local.closedState = local.authoritativeUnderlay != null")
                        && runtime.contains("runtime closed external TempBlock handoff"),
                "a shifted direct-write handoff must close the client coordinate without painting a guessed permanent edit");
    }

    @Test
    void initialJoinResetCannotInitializeElementalAbilityBeforeTheClientPlatform() throws IOException {
        String stop = method(runtime(), "private void stop0", "public boolean isAuthoritative()");

        int stateCheck = stop.indexOf("if (!commonRuntimeInstalled)");
        int preStartReturn = stop.indexOf("return;", stateCheck);
        int coreAccess = stop.indexOf("CoreAbility.getAbilitiesByInstances()", preStartReturn);
        int elementalCleanup = stop.indexOf("AirAbility.discardAllAirbendingState()", coreAccess);

        assertTrue(stateCheck >= 0 && preStartReturn > stateCheck
                        && coreAccess > preStartReturn && elementalCleanup > coreAccess,
                "the unauthenticated initial reset must return before loading any common gameplay class");
        assertTrue(runtime().contains("Platform.install(platform);")
                        && runtime().contains("commonRuntimeInstalled = true;"),
                "partial common startup must enter the exhaustive cleanup path only after Platform.install");
    }

    @Test
    void physicalServerTrafficIsMaskedPerOwnedLifecycleWithoutPostPacketRollback() throws IOException {
        String runtime = runtime();
        String single = method(runtime, "private boolean authoritativeBlock0",
                "private boolean authoritativeBlockBatch0");
        String batch = method(runtime, "private boolean authoritativeBlockBatch0",
                "private CompletedTempBlockRestore takeCompletedTempBlockRestore");
        String chunk = method(runtime, "private void acceptAuthoritativeChunk0",
                "private void applyTempBlockBatch0");
        String mixin = source("src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ClientPlayNetworkHandlerPredictionMixin.java");

        assertTrue(single.contains("hidesServerTempBlock(key)"));
        assertTrue(single.contains("takeCompletedTempBlockRestore(key, state)"),
                "a completed-layer fence must match the exact announced physical state");
        assertTrue(single.contains("takeConfirmedDirectBlock(key, state)"));
        assertTrue(batch.contains("takeCompletedTempBlockRestore(key, incoming)"),
                "single and chunk-delta packets must use the same exact close fence");
        assertTrue(single.contains("tempBlockTeardownFences.maskIncoming(key, state)")
                        && batch.contains("tempBlockTeardownFences.maskIncoming(key, incoming)"),
                "late completed lifecycle states must be rejected before entering ClientWorld");
        assertTrue(batch.contains("final boolean[] masked")
                        && batch.contains("retainedStates[i] = desiredTempBlockState(key)"));
        assertTrue(batch.contains("desiredConfirmedDirectState(confirmed)"),
                "a receipted Earth entry in a mixed chunk delta must retain its causal client state");
        assertTrue(batch.contains("if (maskedEntries == 0) return false")
                        && batch.contains("masked[i] ? retainedStates[i] : states.get(i)")
                        && batch.contains("world.setBlockState(pos, selected, 19)")
                        && batch.contains("return true;"),
                "a mixed delta must be cancelled and only its unrelated authoritative entries installed");
        assertFalse(runtime.contains("tempBlockBatchRestores")
                        || runtime.contains("acceptAuthoritativeBlockBatch0"),
                "owned server entries may never be installed and rolled back at a packet-tail hook");
        assertFalse(mixin.contains("projectkorra$acceptAuthoritativeBlockBatch")
                        || mixin.contains("method = \"onChunkDeltaUpdate\", at = @At(\"TAIL\")"));
        assertTrue(chunk.contains("TempBlock.getActiveLayers()"));
        assertTrue(chunk.contains("tempBlockTeardownFences.maskIncoming(entry.getKey(), entry.getValue())"),
                "full chunks must obey the same durable exact-state teardown fence");
        assertTrue(chunk.contains("desiredTempBlockState(key)"),
                "chunk reapplication must preserve a newer remote/server overlay above a local layer");
        assertTrue(chunk.contains("authoritativeTempBlockLayers.values()")
                        && chunk.contains("server.hiddenForLocalViewer"),
                "every Paper layer attributed to the local predicted action must stay hidden even when layer counts diverge");
        assertTrue(chunk.contains("rebaseClientTempBlockUnderlay(key, world.getBlockState(pos))"),
                "chunk authority must become the hidden underlay without deleting the local lifecycle");
        assertFalse(chunk.contains("List.copyOf(clientTempBlockActions.values())")
                        || chunk.contains("local.authoritativeUnderlay = world.getBlockState(local.key.pos)"),
                "an unpaired closed tombstone is metadata only and must never survive chunk authority as a visual block");
        assertFalse(runtime.contains("discardUnconfirmedClientTempStack"),
                "vanilla packet timing must never discard an unconfirmed client TempBlock");
        String expiry = method(runtime, "private void expireUnconfirmedTempBlocks",
                "private boolean preserveClientTempBlockAuthority");
        assertFalse(expiry.contains("world.setBlockState") || expiry.contains("detached.authoritativeUnderlay"),
                "expiring metadata must never repaint a coordinate seconds after its local lifecycle ended");
        assertTrue(expiry.contains("if (!local.closed) continue;"),
                "an accepted live client TempBlock must always finish its own lifecycle");
        String preserve = method(runtime, "private boolean preserveClientTempBlockAuthority",
                "private void rebaseClientTempBlockUnderlay");
        assertFalse(preserve.contains("newestClosedClientTempBlock") || preserve.contains("closedState"),
                "only a currently active common TempBlock may hide an otherwise unrelated vanilla update");
        assertTrue(runtime.contains("local.closedRevision = change.revision()")
                        && runtime.contains("local.closedRevision < newestRevision"),
                "overlapping layers must restore the state from the final close event, not the newest-created layer");
    }

    @Test
    void lifecycleMetadataReconcilesOnlyTheExactSemanticClientLayer() throws IOException {
        String runtime = runtime();
        String metadata = method(runtime, "private void applyTempBlockBatch0",
                "private void setVelocity0");

        assertTrue(metadata.contains("serverTempBlocks.apply"));
        assertTrue(metadata.contains("desiredTempBlockState(key)"));
        assertTrue(runtime.contains("serverTempBlocks.overlayState(key, player.getUuid())"),
                "a newer server-only/remote layer must overlay and later reveal the client layer");
        assertTrue(metadata.contains("pairedServerTempBlocks.get(operation.layerId())"));
        assertTrue(metadata.contains("viewerId != null && viewerId.equals(operation.ownerId())")
                        && runtime.contains("server.hiddenForLocalViewer && key.equals(server.key)"),
                "authenticated layer ownership, not action age, receipt timing, or local Action retention, is the concealment boundary");
        assertFalse(metadata.contains("viewerId.equals(operation.ownerId())\n                            && operation.actionSequence() > 0L"),
                "long-lived TempBlocks must remain hidden after their originating input Action retires");
        assertTrue(metadata.contains("local.serverClosed = true"),
                "an authoritative close must let the exact client layer finish on its own clock");
        assertTrue(metadata.contains("new CompletedTempBlockRestore(physicalState, completedRestore,")
                        && metadata.contains("followLiveClientState, tick"),
                "the close fence must identify Paper's exact following physical write");
        assertTrue(metadata.contains("hiddenClosingLayer")
                        && metadata.contains("completedRestore == null")
                        && metadata.contains("followLiveClientState = activeLocal != null")
                        && runtime.contains("completed.followLiveClientState")
                        && runtime.contains("clientTempBlockState(key.world, key.pos)"),
                "an action-owned partial close must fence its vanilla write even when layer ordinals diverge");
        assertTrue(runtime.contains("updateCompletedTempBlockRestores(change.layerId(), local.key"),
                "a local lifecycle that finishes after Paper metadata must update the pending repaint");
        String repaint = method(runtime, "private void repaintAuthoritativeTempBlock",
                "private void expireUnconfirmedTempBlocks");
        assertTrue(repaint.indexOf("if (hidesServerTempBlock(key))") < repaint.indexOf("else if (local != null)"),
                "partially closing an owned Paper stack must not expose its next still-hidden physical layer");
        assertFalse(metadata.contains("localLayer.revertBlock()") || metadata.contains("localLayer.discard()"),
                "Paper lifecycle timing must never roll the client TempBlock backward");
        assertTrue(runtime.contains("clientPos=") && runtime.contains("serverPos=")
                        && runtime.contains("pairedTempBlockCoordinates.computeIfAbsent(server.key"),
                "coordinate divergence must hide Paper's shifted coordinate and retain the client visual");
        assertTrue(runtime.contains("topAuthoritativeTempBlock")
                        && runtime.contains("authoritativeEffectAbility"),
                "common ability ownership checks must see remote server-only TempBlock layers");
        assertFalse(metadata.contains("TempBlock.removeBlock("),
                "metadata must never tear down an entire coordinate stack");
        assertFalse(metadata.contains("blocks.computeIfAbsent"));
        assertFalse(metadata.contains("blockEchoes.add("));
    }

    @Test
    void onlyAuthoritativeTeardownOwnsACompletedTempBlockFence() throws IOException {
        final String runtime = runtime();
        final String tick = method(runtime, "private void tick0", "private void reconcileAuthoritativeCooldowns");
        final String localChanges = method(runtime, "public void onChange(final TempBlockSync.Change change)",
                "public void beforeWorldChange(final TempBlockSync.Change change)");
        final String forcedRemoval = method(runtime, "private void forceRemoveAbility",
                "private Map<BlockKey, CapturedAbilityTempBlock> captureAbilityTempBlocks");
        final String teardown = method(runtime, "private void finalizeAbilityTempBlockTeardown",
                "private BlockState composeTempBlockTeardownView");

        assertFalse(localChanges.contains("tempBlockTeardownFences.arm")
                        || localChanges.contains("armLocalTempBlockTeardown"),
                "moving WaterFlow/IceWave replacement stacks must not leave authority fences on ordinary closes");
        assertTrue(runtime.contains("local.createdStates.add(createdState)"),
                "TempBlock#setType variants must be captured as exact stale states");
        assertTrue(teardown.contains("tempBlockTeardownFences.arm(key, lifecycle.staleStates, selected, tick)"),
                "an authoritative forced teardown must retain its exact late-state fence");
        assertTrue(forcedRemoval.contains("captureAbilityTempBlocks(ability)")
                        && forcedRemoval.contains("finalizeAbilityTempBlockTeardown(ability, capturedTempBlocks)"),
                "the durable fence must be scoped to the authoritative force-removal transaction");
        assertTrue(tick.contains("auditTempBlockTeardownFences(client.world)")
                        && tick.indexOf("auditTempBlockTeardownFences(client.world)")
                        < tick.indexOf("blocks.clear()"),
                "late client fluid writes must be rejected in the same client heartbeat before rendering");
        assertTrue(runtime.contains("tempBlockTeardownFences.expireBefore(tick - ACTION_RETENTION_TICKS)"),
                "completed exact-state ownership must be bounded without a short latency timeout");
    }

    @Test
    void legacyTempBlockRollbackMachineryCannotReturn() throws IOException {
        String runtime = runtime();

        assertFalse(runtime.contains("OwnedTempReceipt"));
        assertFalse(runtime.contains("invalidateClientTempStack"));
        assertFalse(runtime.contains("localBlockHistory"));
        assertFalse(runtime.contains("serverTempActive"));
        assertFalse(runtime.contains("physicalAuthority"));
        assertFalse(runtime.contains("findNearestDirectBlock"),
                "direct earth writes must never be paired by coordinate proximity");
        assertTrue(runtime.contains("DirectBlockEffectKey(long actionSequence, String ability, int mutationOrdinal)"));
        assertTrue(runtime.contains("private final ClientTempBlockLedger<BlockKey, BlockState> serverTempBlocks"));
        assertTrue(runtime.contains("private List<PredictionDesyncBlock> ownedTempDesyncs0"));
        assertTrue(runtime.contains("return List.of();"),
                "an intentionally hidden physical TempBlock is not a rollback/desync marker");
    }

    @Test
    void ownedDirectWritesNeverRollbackAndHideTheWholeKnownEarthLifecycle() throws IOException {
        String runtime = runtime();
        String directReceipt = method(runtime, "private void noteDirectBlock0",
                "private ConfirmedDirectBlockPacket takeConfirmedDirectBlock");
        String directExpiry = method(runtime, "private void expireUnconfirmedDirectBlocks",
                "private void setVelocity0");
        assertFalse(directReceipt.contains("local.key.world.setBlockState")
                        || directReceipt.contains("world.setBlockState(serverKey.pos"),
                "a divergent direct receipt may fence Paper's echo but may not undo or hand off the local visual");
        int missingLocal = directReceipt.indexOf("if (local == null && !receipt.movedEarthLifecycle()");
        int missingLocalReturn = directReceipt.indexOf("return;", missingLocal);
        int packetFence = directReceipt.indexOf("confirmedDirectBlockPackets.add", missingLocal);
        assertTrue(missingLocal >= 0 && missingLocalReturn > missingLocal
                        && packetFence > missingLocalReturn,
                "a genuinely server-only permanent branch may pass, while every owner-stamped moved-earth write receives a physical packet fence");
        assertFalse(directReceipt.contains("hasActiveClientEarthLifecycle")
                        || directReceipt.contains("activeMovedEarthLifecycle"),
                "Paper's moved-earth echo may not become visible because Fabric chose another ordinal, coordinate, or lifecycle duration");
        assertTrue(runtime.contains("EarthAbility.getMovedEarth().entrySet()")
                        && runtime.contains("EarthAbility.getTempAirLocations().values()"),
                "moved and temp-air halves of the client lifecycle must both retain visual ownership");
        assertTrue(directReceipt.contains("packet.serverTick == receipt.serverTick()")
                        && directReceipt.contains("packet.key.equals(serverKey)")
                        && directReceipt.indexOf("confirmedDirectBlockPackets.removeIf")
                        < directReceipt.indexOf("confirmedDirectBlockPackets.add"),
                "same-tick writes to one Earth coordinate must collapse to the final observable packet fence");
        assertTrue(runtime.contains("ConfirmedDirectBlockPacket(long serverTick"),
                "a direct-write fence must retain the server tick needed to discard coalesced intermediate writes");
        assertTrue(directReceipt.contains("final BlockState serverUnderlay = world.getBlockState(serverKey.pos)"),
                "the fence must capture the coordinate before its exact announced Paper write");
        String desired = method(runtime, "private BlockState desiredConfirmedDirectState",
                "private boolean hasActiveClientEarthCoordinate");
        assertTrue(desired.contains("recent.revision > packet.observedVisualRevision")
                        && desired.contains("return packet.serverUnderlay"),
                "later local movement wins; otherwise the exact pre-write underlay is revealed");
        assertFalse(desired.contains("tempBlockBatchRestores") || desired.contains("blocks.computeIfAbsent"));
        assertFalse(directExpiry.contains("setBlockState"),
                "direct-write expiry is bookkeeping and can never resurrect a saved block snapshot");

        String prepare = method(runtime, "private void noteTempFallingBlockPrepare0",
                "private void noteTempFallingBlock0");
        String receipt = method(runtime, "private void noteTempFallingBlock0",
                "private boolean reconcileSpawn0");
        assertFalse(prepare.contains("actions.get") || prepare.contains("locallyPredicted"));
        assertFalse(receipt.contains("actions.get") || receipt.contains("locallyPredicted"));
        assertTrue(receipt.contains("hiddenTempFallingEntities.add(receipt.serverEntityId())"),
                "an authoritative owner receipt must hide Paper even when the local alias has retired");
    }

    @Test
    void focusedEarthSourceSurvivesPacketsAndChunksUntilItsLocalLifecycleRestores() throws IOException {
        String runtime = runtime();
        String localWrite = method(runtime, "private void setBlock0",
                "private BlockState simulatedBlockState0");
        String receipt = method(runtime, "private void noteDirectBlock0",
                "private static Set<DirectBlockCauseKey> activeClientEarthCauses");
        String single = method(runtime, "private boolean authoritativeBlock0",
                "private boolean authoritativeBlockBatch0");
        String batch = method(runtime, "private boolean authoritativeBlockBatch0",
                "private CompletedTempBlockRestore takeCompletedTempBlockRestore");
        String chunk = method(runtime, "private void acceptAuthoritativeChunk0",
                "private void applyTempBlockBatch0");
        String tick = method(runtime, "private void tick0",
                "private void reconcileAuthoritativeCooldowns");
        String earthBlast = source("../common/src/main/java/com/projectkorra/projectkorra/earthbending/EarthBlast.java");

        assertTrue(runtime.contains("private final Map<BlockKey, ServerDirectBlockMask> serverDirectBlockMasks"));
        assertTrue(localWrite.indexOf("updateLocalDirectView(")
                        < localWrite.indexOf("if (state.equals(before)) return;"),
                "the focused-source view must exist before Paper can replay its old chunk state");
        assertTrue(receipt.contains("serverDirectBlockMasks.put(serverKey, new ServerDirectBlockMask(")
                        && receipt.contains("clientDirectBaseState(serverKey, serverUnderlay)"),
                "Paper's focused stone must be retained only as a hidden physical comparison state");
        assertTrue(single.contains("serverDirectMaskForIncoming(key, state)")
                        && batch.contains("serverDirectMaskForIncoming(key, incoming)"),
                "single and multi-block updates must share the durable direct-Earth mask");
        assertTrue(chunk.contains("serverDirectBlockMasks.entrySet()")
                        && chunk.contains("mask.serverState.equals(chunkState)")
                        && chunk.contains("world.setBlockState(key.pos, mask.viewerState, 19)"),
                "a chunk resend may rebuild the client owner view without exposing Paper's stone source");
        assertTrue(chunk.indexOf("serverDirectBlockMasks.entrySet()")
                        < chunk.indexOf("TempBlock.getActiveLayers()"),
                "direct Earth is the underlay; client TempBlocks must remain the top visual layer");
        assertTrue(tick.contains("mask.serverState.equals(mask.viewerState)")
                        && tick.contains("!hasActiveClientDirectCause(mask.ownerId, mask.cause)"),
                "only an already-equal, inactive mask may age out; a divergent source can never time out into stone");
        assertTrue(earthBlast.contains("this.sourceBlock.setType(Material.STONE)")
                        && earthBlast.contains("this.sourceBlock.setType(this.sourceType)"),
                "the common EarthBlast lifecycle still owns both focus and restoration");
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
        String commonFalling = source("../common/src/main/java/com/projectkorra/projectkorra/util/TempFallingBlock.java");

        assertTrue(receipt.contains("localPlayer.getUuid().equals(receipt.abilityOwner())"));
        assertTrue(receipt.contains("new TempFallingBlockKey(")
                && receipt.contains("localSequence, receipt.spawnOrdinal()")
                && receipt.contains("localActionSequence(receipt.actionSequence())"),
                "Paper's falling-block action must be correlated before matching the local ordinal");
        assertTrue(receipt.contains("pending.ability.equalsIgnoreCase(receipt.ability())"),
                "an ordinal mismatch must never alias a different ability's falling block");
        assertTrue(receipt.contains("world.getEntityById(receipt.serverEntityId())"));
        assertTrue(receipt.contains("authority.discard()")
                        && receipt.contains("tempFallingEntityAliases.add"),
                "packet race order must never swap a smooth predicted falling block for its delayed duplicate");
        assertTrue(receipt.contains("observedFallingBlockSpawns.remove(receipt.serverEntityId())")
                        && receipt.contains("vanillaSpawnSeen"),
                "a receipt arriving after a short-lived vanilla entity must not create a ghost alias");
        assertTrue(spawn.contains("authoritativeEntityAliases.containsKey(packet.getEntityId())")
                && spawn.contains("return true"));
        assertTrue(spawn.contains("packet.getEntityType() == net.minecraft.entity.EntityType.FALLING_BLOCK"));
        assertTrue(spawn.contains("serverTempFallingPrepares.entrySet()")
                        && spawn.contains("Block.getStateFromRawId(packet.getEntityData())")
                        && spawn.contains("materialState(prepare.material()).equals(spawnedState)")
                        && spawn.contains("close(spawn, expected, 1.0E-7)"),
                "the pre-spawn receipt must close the packet race using exact state and encoded coordinates, never proximity");
        assertTrue(spawn.contains("return false"),
                "an unreceipted remote falling block must always take the vanilla spawn path");
        assertTrue(runtime.contains("INSTANCE.tempFallingEntityAliases.contains(serverEntityId)")
                        && runtime.contains("? null : INSTANCE.authoritativeEntityAliases.get(serverEntityId)"),
                "server movement packets must never snap the client-owned falling-block simulation");
        assertTrue(payloads.contains("record TempFallingBlockReceipt"));
        assertTrue(payloads.contains("record TempFallingBlockPrepare"));
        assertTrue(payloads.contains("playS2C().register(TempFallingBlockPrepare.ID"));
        assertTrue(payloads.contains("playS2C().register(TempFallingBlockReceipt.ID"));
        assertTrue(client.contains("registerGlobalReceiver(PredictionPayloads.TempFallingBlockPrepare.ID"));
        assertTrue(client.contains("registerGlobalReceiver(PredictionPayloads.TempFallingBlockReceipt.ID"));
        assertTrue(commonFalling.indexOf("TempFallingBlockSync.prepare")
                        < commonFalling.indexOf("spawnFallingBlock"),
                "ownership metadata must be queued before the vanilla entity can be tracked");
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
