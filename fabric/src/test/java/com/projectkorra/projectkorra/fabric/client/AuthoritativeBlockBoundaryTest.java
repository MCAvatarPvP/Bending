package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the no-rollback authority boundary after prediction was modularized. */
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
                && direct.contains("world.setBlockState(pos, state, 19)"));
        assertTrue(direct.contains("DirectBlockAuthorityPolicy.mayConceal(")
                && direct.contains("local != null, receipt.movedEarthLifecycle(), knownCause"));
        assertTrue(direct.contains("concealed unmatched moved-earth")
                        && direct.contains("concealed divergent causal write"),
                "latency-offset moved-earth writes must preserve the common client's complete visual transaction");
        assertFalse(direct.contains("releaseUnconfirmed("),
                "an authoritative receipt must not erase a divergent RaiseEarth or EarthSmash visual");
        assertTrue(direct.contains("Expiry is bookkeeping only")
                        && direct.contains("predictedWrites.remove(entry.getKey(), entry.getValue())"),
                "unconfirmed identity records may expire, but expiry must never repaint the saved before-state");
        assertTrue(direct.contains("action != 0L && action == mutation.lastAction"));
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
        assertTrue(temp.contains("world.setBlockState(pos, visibleState, 19)"));
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
    void serverBlockTrafficIsMaskedBeforeEnteringClientWorld() throws IOException {
        final String temp = tempAuthority();
        final String mixin = source("src/main/java/com/projectkorra/projectkorra/fabric/mixin/client/ClientPlayNetworkHandlerPredictionMixin.java");

        assertTrue(temp.contains("teardownFences.maskIncoming(key, state)"));
        assertTrue(temp.contains("takeCompletedRestore(key, state)"));
        assertTrue(temp.contains("final boolean[] masked"));
        assertTrue(temp.contains("masked[index] ? retainedStates[index] : states.get(index)"));
        assertTrue(temp.contains("directBlocks.restoreChunk"));
        assertTrue(temp.contains("TempBlock.getActiveLayers()"));
        assertFalse(mixin.contains("method = \"onChunkDeltaUpdate\", at = @At(\"TAIL\")"));
        assertFalse(temp.contains("discardUnconfirmedClientTempStack"));
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
    void onlyForcedAbilityRemovalArmsDurableTempBlockTeardown() throws IOException {
        final String runtime = runtime();
        final String temp = tempAuthority();
        assertFalse(method(temp, "public void onChange", "public void beforeWorldChange")
                .contains("teardownFences.arm"));
        assertTrue(temp.contains("teardownFences.arm(key, lifecycle.staleStates"));
        assertTrue(runtime.contains("tempBlockAuthority.removeAbility"));
        assertTrue(runtime.contains("tempBlockAuthority.afterLocalProgress(client.world)"));
        assertTrue(temp.contains("teardownFences.expireBefore(tick - ACTION_RETENTION_TICKS)"));
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
        assertTrue(direct.contains("recent.revision > packet.observedVisualRevision")
                && direct.contains("return packet.serverUnderlay"));
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
        assertTrue(direct.contains("local != null, receipt.movedEarthLifecycle(), knownCause")
                        && direct.contains("concealed divergent causal write")
                        && direct.contains("concealed unmatched moved-earth physical write"),
                "Paper's delayed coordinates and ordinals must fence packets without replacing the local visual");
        final String expiry = method(direct, "public void expire", "public void rollbackAction");
        assertFalse(expiry.contains("world.setBlockState")
                        || expiry.contains("releaseUnconfirmed"),
                "identity expiry must not turn a raised wall or sampled smash back into air");
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
