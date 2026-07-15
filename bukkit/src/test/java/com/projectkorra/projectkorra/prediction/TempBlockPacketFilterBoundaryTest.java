package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempBlockPacketFilterBoundaryTest {
    @Test
    void filterSuppressesTheWholeOwnedCoordinateLifecycleAndPreservesMixedAuthority() throws IOException {
        Path filterSource = Path.of("src/main/java/com/projectkorra/projectkorra/prediction/TempBlockPacketFilter.java");
        if (!Files.exists(filterSource)) filterSource = Path.of("bukkit").resolve(filterSource);
        String filter = Files.readString(filterSource);

        assertFalse(filter.contains("TempBlock.hasOwnedLayer"),
                "ability ownership alone must never hide a server-only TempBlock");
        assertTrue(filter.contains("Set<Long> layers")
                        && filter.contains("viewer.equals(predictedOwner)"),
                "the gate must track exact locally-predicted layer ids per viewer");
        assertTrue(filter.contains("CLOSING_FENCE_MILLIS"));
        assertTrue(filter.contains("if (isHidden(viewer"),
                "single block updates must be hidden by coordinate, independent of encoded state");
        assertTrue(filter.contains("event.markForReEncode(true)"));
        assertFalse(filter.contains("physicalState).equals"),
                "fluid and neighbor variants must not escape exact-state receipt matching");
        assertTrue(filter.contains("if (!isHidden(viewer"));
        assertTrue(filter.contains("packet.setBlocks"),
                "mixed updates must retain every non-TempBlock entry");
        assertTrue(filter.contains("gate.viewerData") && filter.contains("gate.active"),
                "chunk snapshots must use the routed predicted-layer view, not raw ability ownership");
        assertTrue(filter.contains("public void recordSnapshot"),
                "clients joining during an active layer must arm the same coordinate fence");
        assertTrue(filter.contains("public void resetViewer"),
                "a reconnect must not inherit active gates from an older connection");
        assertTrue(filter.contains("public void pruneExpired"),
                "closing fences must be removed even if no later packet touches that coordinate");
        assertTrue(filter.contains("ownsOperation && closes && packetExpected"),
                "only a restoring write for a delivered predicted layer may arm a closing fence");
    }

    @Test
    void tempBlockArmsTheFilterBeforeEveryPhysicalWorldMutation() throws IOException {
        Path source = Path.of("../common/src/main/java/com/projectkorra/projectkorra/util/TempBlock.java");
        if (!Files.exists(source)) source = Path.of("common/src/main/java/com/projectkorra/projectkorra/util/TempBlock.java");
        String tempBlock = Files.readString(source);

        int writeMethod = tempBlock.indexOf("private void writeTopLocked");
        int publishIntent = tempBlock.indexOf("TempBlockSync.beforeWorldChange(operation, this, effectiveData)", writeMethod);
        int worldMutation = tempBlock.indexOf("TempBlockSync.runWorldMutation(operation, this, effectiveData, worldWrite)", writeMethod);
        assertTrue(writeMethod >= 0 && publishIntent > writeMethod && worldMutation > publishIntent,
                "client hide metadata must be queued before every physical world write");
        int constructor = tempBlock.indexOf("private TempBlock(final Block block");
        int revision = tempBlock.indexOf("advanceRevisionLocked();", constructor);
        int firstWrite = tempBlock.indexOf("writeTopLocked(TempBlockSync.Operation.CREATE", constructor);
        assertTrue(revision > constructor && firstWrite > revision,
                "the pre- and post-mutation publications must share one non-zero revision");

        Path serverSource = Path.of("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        if (!Files.exists(serverSource)) serverSource = Path.of("bukkit").resolve(serverSource);
        String server = Files.readString(serverSource);
        assertTrue(server.contains("TempBlockSync.encode(change.data()),")
                && server.contains("change.packetExpected()), Map.copyOf(ownerViews)"));
        assertFalse(server.contains("change.packetExpected() && this.tempBlockPacketFilter == null"),
                "packetExpected describes a physical lifecycle write, not whether PacketEvents delivers it");
    }

    @Test
    void chunkPatchUsesThePlatformNativeCompleteBlockState() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/prediction/TempBlockPacketFilter.java");
        if (!Files.exists(source)) source = Path.of("bukkit").resolve(source);
        String filter = Files.readString(source);

        assertTrue(filter.contains("BukkitMC.blockDataHandle(data)"));
        assertTrue(filter.contains("nativeData.getAsString()"),
                "chunk patching must use Bukkit's complete native state encoding");
    }
}
