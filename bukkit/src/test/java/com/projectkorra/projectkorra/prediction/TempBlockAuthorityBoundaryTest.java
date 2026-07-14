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
        int end = code.indexOf("private static void remove(TempBlock", start);
        assertTrue(start >= 0 && end > start);
        String method = code.substring(start, end);

        assertTrue(method.contains("invalidateStack"));
        assertFalse(method.contains("finishInvalidated"));
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
    }

    private static String read(String moduleRelative, String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }
}
