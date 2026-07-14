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
}
