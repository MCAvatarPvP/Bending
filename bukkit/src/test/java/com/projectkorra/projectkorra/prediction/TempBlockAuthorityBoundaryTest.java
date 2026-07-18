package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempBlockAuthorityBoundaryTest {
    @Test
    void staleStaticCleanupCannotReplaceAnUnregisteredRealBlock() throws IOException {
        Path source = Path.of("../common/src/main/java/com/projectkorra/projectkorra/util/TempBlock.java");
        if (!Files.exists(source)) source = Path.of("common/src/main/java/com/projectkorra/projectkorra/util/TempBlock.java");
        assertTrue(Files.exists(source));

        String code = Files.readString(source);
        int start = code.indexOf("public static void revertBlock(final Block block");
        int end = code.indexOf("public static boolean applyPhysics", start);
        assertTrue(start >= 0 && end > start);
        String method = code.substring(start, end);
        assertFalse(method.contains("block.setType(defaulttype"));
        assertFalse(method.contains("block.setBlockData("));
    }

    @Test
    void authorityDiscardCannotRunRevertOrAttachmentCallbacks() throws IOException {
        Path source = Path.of("../common/src/main/java/com/projectkorra/projectkorra/util/TempBlock.java");
        if (!Files.exists(source)) source = Path.of("common/src/main/java/com/projectkorra/projectkorra/util/TempBlock.java");
        String code = Files.readString(source);
        int start = code.indexOf("public static void discardBlock");
        int end = code.indexOf("private static List<TempBlock> invalidateStackLocked", start);
        assertTrue(start >= 0 && end > start);
        String method = code.substring(start, end);

        assertTrue(method.contains("invalidateStackLocked"));
        assertTrue(method.contains("true"),
                "discard must close delivered layer metadata even though it skips callbacks");
        assertFalse(method.contains("finishLayers"));
        assertFalse(method.contains("setBlockData"));
    }

    @Test
    void longLivedTempBlocksRetainTheirExactInputAction() throws IOException {
        String paper = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        String fabric = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java");

        assertTrue(paper.contains("candidate.ability.equalsIgnoreCase(ability.getName())"));
        assertTrue(fabric.contains("candidate.abilityName.equalsIgnoreCase(ability.getName())"));
        assertTrue(paper.contains("tempLayerActions.put(change.layerId(), currentAction)")
                        && paper.contains("tempLayerActions.remove(change.layerId())"));
        assertTrue(fabric.contains("tempLayerActions.put(change.layerId(), currentAction)")
                        && fabric.contains("tempLayerActions.remove(change.layerId())"));
        String paperQueue = paper.substring(paper.indexOf("private PendingTempBlock queueTempBlock"),
                paper.indexOf("private Map<UUID, BlockData> predictedOwnerViews"));
        String fabricQueue = fabric.substring(fabric.indexOf("private void queueTempBlock"),
                fabric.indexOf("private Map<UUID, String> predictedOwnerViews"));
        assertFalse(paperQueue.contains("currentAction.locallyPredicted"),
                "a delayed common layer must not lose ownership because its action had no immediate first-frame block");
        assertFalse(fabricQueue.contains("currentAction.locallyPredicted"));
        assertTrue(paper.contains("serverOwnedTempLayers.add(change.layerId())")
                        && paper.contains("predictedTempBlockOwner(change.ownerId(), action, effectAbility)"));
        assertTrue(fabric.contains("serverOwnedTempLayers.add(change.layerId())")
                        && fabric.contains("predictedTempBlockOwner(change.ownerId(), action, effectAbility)"));
        for (String endpoint : new String[]{paper, fabric}) {
            assertTrue(endpoint.contains("layer.getOwnerId().orElse(null), action, effectAbility"),
                    "join snapshots must retain a long-lived layer's authenticated owner after its input Action retires");
            assertTrue(endpoint.contains("TempBlock.getOwnerViews(block, closingOwner)")
                            && endpoint.contains("TempBlock.getVisibleData(block, viewer)"),
                    "owner views must come from the actual TempBlock stack rather than inferred action ordinals");
            assertTrue(endpoint.contains("session.supportedAbilities.contains(abilityName.toLowerCase(Locale.ROOT))"),
                    "only an exact client that supports the owning ability may receive owner-hide metadata");
        }
    }

    @Test
    void shutdownPublishesClosuresBeforePredictionTransportStops() throws IOException {
        String paper = read("src/main/java/com/projectkorra/projectkorra/BukkitProjectKorraPlugin.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/BukkitProjectKorraPlugin.java");
        String fabric = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/ProjectKorraFabricMod.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/ProjectKorraFabricMod.java");

        assertTrue(paper.indexOf("GeneralMethods.stopBending()") < paper.lastIndexOf("this.prediction.stop()"));
        assertTrue(fabric.indexOf("GeneralMethods.stopBending()") < fabric.lastIndexOf("this.prediction.stop()"));
    }

    @Test
    void everyPlatformWriteHandsOffWithoutRestoringARegisteredLayer() throws IOException {
        String paper = read("src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java");
        String fabric = read("../fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricMC.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricMC.java");
        String client = read("../fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricPredictionMC.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/platform/fabric/FabricPredictionMC.java");
        String tempBlock = read("../common/src/main/java/com/projectkorra/projectkorra/util/TempBlock.java",
                "common/src/main/java/com/projectkorra/projectkorra/util/TempBlock.java");

        for (String source : new String[]{paper, fabric}) {
            assertTrue(source.contains("prepareExternalWrite("));
            assertTrue(source.contains("TempBlockSync.currentWorldMutation() != null"));
            assertTrue(source.contains("TempBlock.removeBlockBeforeWrite"));
            assertTrue(source.contains("DirectBlockSync.beforeWorldChange"),
                    "ordinary earth movement metadata must be emitted only after a TempBlock handoff");
        }
        assertTrue(client.contains("ExactPredictionRuntime.setPredictedBlock"));
        assertTrue(client.contains("TempBlockSync.currentWorldMutation() != null"));
        assertTrue(client.contains("TempBlock.removeBlockBeforeWrite"),
                "Fabric must perform the same semantic handoff as Bukkit before an external write");
        assertTrue(tempBlock.contains("TempBlockSync.beforeWorldChange(TempBlockSync.Operation.DISCARD"),
                "DISCARD metadata must flush before the replacement's vanilla block packet");
        assertTrue(paper.contains("final Block block = getBlock()"),
                "BlockState updates must use the same authority boundary as direct writes");
        assertTrue(fabric.contains("block.prepareExternalWrite(FabricMC.blockData(state))"));
        assertTrue(client.contains("block.prepareExternalWrite(FabricMC.blockData(state))"),
                "Fabric BlockState updates must use the same authority boundary as direct writes");
    }

    @Test
    void nativeBukkitMutationsCannotLeaveARegisteredGhostLayer() throws IOException {
        String listener = read("src/main/java/com/projectkorra/projectkorra/PKListener.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/PKListener.java");

        assertTrue(listener.contains("onBlockPlaceAuthority"));
        assertTrue(listener.contains("onEntityChangeBlockAuthority"));
        assertTrue(listener.contains("onEntityExplodeAuthority"));
        assertTrue(listener.contains("priority = EventPriority.MONITOR, ignoreCancelled = true"));
        assertTrue(listener.contains("onTempBlockBurn"));
        assertTrue(listener.contains("onTempBlockGrow"));
        assertTrue(listener.contains("onTempBlockSpread"));
        assertTrue(listener.contains("onTempLeavesDecay"));
        assertTrue(listener.contains("onTempMoistureChange"));
        assertTrue(listener.contains("b.getRelative(event.getDirection())"),
                "pistons must protect both the source and destination coordinate");
    }

    private static String read(String moduleRelative, String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }
}
