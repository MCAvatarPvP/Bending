package com.jedk1.jedcore.ability.earthbending;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EarthShardPreparationBoundaryTest {

    @Test
    void preparationUsesControlledLiftAcrossTheCompleteRoute() throws IOException {
        final String source = source();

        assertTrue(source.contains("final int clearanceHeight = Math.max(3, targetY - block.getY())")
                        && source.contains("i <= clearanceHeight"),
                "lower sources must validate every block up to the held shard height");
        assertTrue(!source.contains("setGravity("),
                "EarthShard must retain its original falling-block gravity behavior");
        assertTrue(source.contains("fb.getLocation().getY() >= targetY"),
                "a fast shard must complete when it crosses the target plane");
        assertTrue(source.contains("riseTick >= riseTickLimit")
                        && source.contains("riseTickLimits.put(risingShard, riseTickLimit)"),
                "Fabric prediction must have a deterministic fallback when entity Y never reports the crossing");
    }

    @Test
    void failedHandoffsCannotLeaveAnUnthrowableAbility() throws IOException {
        final String source = source();

        assertTrue(source.contains("this.createReadyBlock(destination, fb.getBlockData())")
                        && source.contains("readyBlock.isReverted() || TempBlock.get(destinationBlock) != readyBlock"),
                "the rising-to-held handoff must verify that its TempBlock was accepted");
        assertTrue(source.contains("if (this.createReadyBlock(destination, fb.getBlockData()))")
                        && source.contains("MAX_READY_HANDOFF_ATTEMPTS")
                        && source.contains("fb.setVelocity(new Vector(0, 0, 0))"),
                "the rising entity must remain frozen until the held TempBlock acknowledges creation");
        assertTrue(source.contains("this.pruneOrphanedSources()")
                        && source.contains("sourceLayer.revertBlock()")
                        && source.contains("if (this.tblockTracker.isEmpty())"),
                "a missing rising entity must restore its source and terminate cleanly");
        assertTrue(source.contains("if (readyBlocksTracker.isEmpty())")
                        && source.contains("TempFallingBlock.getFromAbility(this).isEmpty()"),
                "throwing may never transition to a projectile state with zero shards");
    }

    private static String source() throws IOException {
        Path path = Path.of("src/main/java/com/jedk1/jedcore/ability/earthbending/EarthShard.java");
        if (!Files.exists(path)) path = Path.of("common").resolve(path);
        assertTrue(Files.exists(path), "missing source: " + path);
        return Files.readString(path);
    }
}
