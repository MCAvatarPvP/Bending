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
        assertTrue(paper.contains("currentAction.locallyPredicted")
                        && paper.contains("serverOwnedTempLayers.add(change.layerId())")
                        && paper.contains("final UUID predictedOwner = action == null ? null : action.owner"));
        assertTrue(fabric.contains("currentAction.locallyPredicted")
                        && fabric.contains("serverOwnedTempLayers.add(change.layerId())")
                        && fabric.contains("final UUID predictedOwner = action == null ? null : action.owner"));
        assertTrue(paper.contains("predictedOwnerViews(block, action, change.data())")
                        && fabric.contains("predictedOwnerViews(block, action, change.data())"),
                "pre-write REVERT metadata must use the computed underlay, not the still-physical TempBlock");
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

        for (String source : new String[]{paper, fabric, client}) {
            assertTrue(source.contains("prepareExternalWrite()"));
            assertTrue(source.contains("TempBlockSync.currentWorldMutation() == null"));
            assertTrue(source.contains("TempBlock.removeBlock"));
        }
        assertTrue(paper.contains("final Block block = getBlock()"),
                "BlockState updates must use the same authority boundary as direct writes");
        assertTrue(fabric.contains("block.prepareExternalWrite()"));
        assertTrue(client.contains("block.prepareExternalWrite()"));
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
