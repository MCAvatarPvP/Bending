package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempBlockPacketFilterBoundaryTest {
    @Test
    void filterSuppressesOnlyPrearmedPhysicalReceiptsAndPreservesMixedAuthority() throws IOException {
        Path filterSource = Path.of("src/main/java/com/projectkorra/projectkorra/prediction/TempBlockPacketFilter.java");
        if (!Files.exists(filterSource)) filterSource = Path.of("bukkit").resolve(filterSource);
        String filter = Files.readString(filterSource);

        assertTrue(filter.contains("!change.packetExpected()"));
        assertTrue(filter.contains("event.markForReEncode(true)"));
        assertTrue(filter.contains("receipt.physicalState).equals(incoming)"),
                "a same-material but different fluid level must remain authoritative");
        assertTrue(filter.contains("if (receipt == null) visible.add(block)"));
        assertTrue(filter.contains("packet.setBlocks"),
                "mixed updates must retain every non-TempBlock entry");
        assertTrue(filter.contains("queue.removeIf(receipt -> receipt.layerId == change.layerId())"),
                "a REVERT or revision must close the persistent receipt for that exact layer");
        assertTrue(filter.contains("if (matched != null) return matched;"),
                "duplicate fluid/neighbor packets must not consume CREATE ownership after the first packet");
        assertFalse(filter.contains("queue.pollFirst()"),
                "one owned world mutation can emit more than one exact packet");
    }

    @Test
    void tempBlockArmsTheFilterBeforeEveryPhysicalWorldMutation() throws IOException {
        Path source = Path.of("../common/src/main/java/com/projectkorra/projectkorra/util/TempBlock.java");
        if (!Files.exists(source)) source = Path.of("common/src/main/java/com/projectkorra/projectkorra/util/TempBlock.java");
        String tempBlock = Files.readString(source);

        assertTrue(tempBlock.indexOf("TempBlockSync.beforeWorldChange(TempBlockSync.Operation.CREATE, this, newData)")
                < tempBlock.indexOf("applyWorldData(this, newData)"));
        assertTrue(tempBlock.indexOf("TempBlockSync.beforeWorldChange(TempBlockSync.Operation.REVERT, this, effectiveData)")
                < tempBlock.indexOf("if (instances_.containsKey(this.block)) applyWorldData(this, effectiveData)"));
    }

    @Test
    void waterLevelRemainsNumericWhenPacketStateIsBuilt() throws IOException {
        Path source = Path.of("src/main/java/com/projectkorra/projectkorra/prediction/TempBlockPacketFilter.java");
        if (!Files.exists(source)) source = Path.of("bukkit").resolve(source);
        String filter = Files.readString(source);

        assertTrue(filter.contains("Integer.parseInt(value)"));
        assertTrue(filter.contains("state.hasProperty(StateValue.WATERLOGGED)"),
                "unsupported properties must not turn WATER or LAVA into AIR");
    }
}
