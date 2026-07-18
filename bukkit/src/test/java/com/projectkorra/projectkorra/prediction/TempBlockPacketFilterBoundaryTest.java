package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards ordered action ownership without a server-side vanilla packet filter. */
class TempBlockPacketFilterBoundaryTest {
    @Test
    void paperAnnouncesActionOwnershipBeforeEveryPhysicalWrite() throws IOException {
        String plugin = source("src/main/java/com/projectkorra/projectkorra/BukkitProjectKorraPlugin.java");
        String server = source("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");

        assertFalse(plugin.contains("TempBlockPacketFilter"));
        assertFalse(server.contains("TempBlockPacketFilter"));
        assertFalse(server.contains("event.setCancelled(true)"));
        assertTrue(plugin.contains("action-owned Fabric TempBlock lifecycles"));
        assertTrue(server.contains("currentAction != null && currentAction.owner.equals(effectOwner)"),
                "every layer caused by an accepted native action must inherit that action");
        assertTrue(server.contains("flushTempBlocks();"),
                "ownership metadata must be queued before the vanilla block write");
    }

    @Test
    void tempBlockPublishesSemanticMetadataBeforeEveryPhysicalWorldMutation() throws IOException {
        String tempBlock = source("../common/src/main/java/com/projectkorra/projectkorra/util/TempBlock.java");

        int writeMethod = tempBlock.indexOf("private void writeTopLocked");
        int publishIntent = tempBlock.indexOf(
                "TempBlockSync.beforeWorldChange(operation, this, effectiveData)", writeMethod);
        int worldMutation = tempBlock.indexOf(
                "TempBlockSync.runWorldMutation(operation, this, effectiveData, worldWrite)", writeMethod);
        assertTrue(writeMethod >= 0 && publishIntent > writeMethod && worldMutation > publishIntent);
        assertTrue(tempBlock.contains("getEffectAbility()")
                        && tempBlock.contains("getEffectStep()")
                        && tempBlock.contains("getEffectOrdinal()"));

        String server = source("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        assertTrue(server.contains("effectAbility, effectStep, effectOrdinal"));
        assertTrue(server.contains("tempLayerEffects")
                        && server.contains("++action.tempBlockOrdinal"),
                "every predicted TempBlock must receive a causal action-local semantic identity");
        int before = server.indexOf("public void beforeWorldChange");
        int queue = server.indexOf("queueTempBlock(change)", before);
        int flush = server.indexOf("flushTempBlocks();", queue);
        assertTrue(before >= 0 && queue > before && flush > queue);
    }

    private static String source(final String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path) && relative.startsWith("../")) path = Path.of(relative.substring(3));
        if (!Files.exists(path)) path = Path.of("bukkit").resolve(relative);
        assertTrue(Files.exists(path), path.toString());
        return Files.readString(path);
    }
}
