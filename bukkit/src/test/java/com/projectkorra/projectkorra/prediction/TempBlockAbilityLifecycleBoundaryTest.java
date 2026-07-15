package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression boundaries for the two abilities that exposed the lifecycle defects. */
class TempBlockAbilityLifecycleBoundaryTest {
    @Test
    void earthSmashUsesItsRegisteredLayersInsteadOfLeakedWorldState() throws IOException {
        String source = common("com/projectkorra/projectkorra/earthbending/EarthSmash.java");
        String revert = method(source, "public void revert()", "private Block getVisibleEarthSourceBlock");
        String remaining = method(source, "public void checkRemainingBlocks()", "public void remove()");

        assertTrue(revert.contains("List.copyOf(this.affectedBlocks)"));
        assertTrue(revert.indexOf("this.affectedBlocks.clear()") < revert.indexOf("tblock.revertBlock()"),
                "callbacks must not mutate the list being iterated");
        assertTrue(remaining.contains("final List<TempBlock> layers = TempBlock.getAll(block)"));
        assertTrue(remaining.contains("candidate.getAbility().orElse(null) == this"));
        assertTrue(remaining.contains("smashLayer.getBlockData().getMaterial()"));
    }

    @Test
    void phaseChangeNeverReusesARevertedLayerOrImmediatelyRollsBackMelt() throws IOException {
        String source = common("com/projectkorra/projectkorra/waterbending/ice/PhaseChange.java");
        String freeze = method(source, "public void freeze(final Block b)", "private void trackFrozen");
        String meltedCleanup = method(source, "public void revertMeltedBlocks()", "public void remove()");

        assertTrue(source.contains("private static final long MELT_REVERT_MILLIS"));
        assertTrue(freeze.contains("tb.isReverted()"));
        assertTrue(freeze.contains("tb.getAbility().orElse(null) != this"));
        assertFalse(freeze.contains("tb.revertBlock();") || freeze.contains("tb.setType("),
                "refreeze must create/reuse a live ICE layer, never mutate a retired object");
        assertTrue(source.contains("final List<TempBlock> layers = TempBlock.getAll(b)"),
                "thaw must locate a PhaseChange layer even when another layer overlaps it");
        assertTrue(source.contains("block.setRevertTask(() -> untrackFrozen(block))"),
                "external layer retirement must clean the static PhaseChange indexes");
        assertTrue(source.contains("if (block.isReverted()) untrackFrozen(block)"),
                "callback-free client DISCARD must still be purged on the next ability tick");
        assertTrue(source.contains("this.melted_blocks.addIfAbsent(b)"));
        assertTrue(source.contains("final BlockData visibleData = tb.getBlockData()"),
                "melt decisions must use the registered layer, not leaked physical world state");
        assertTrue(source.contains("isRegisteredMeltable(l.getBlock())"));
        String melt = method(source, "public void melt(final Block b)", "private static BlockData meltedData");
        assertTrue(melt.contains("if (thaw(b))"),
                "melt must find the exact PhaseChange layer through overlaps");
        assertTrue(melt.contains("new TempBlock(b, melted, MELT_REVERT_MILLIS, this)"));
        assertFalse(melt.contains("tb.revertBlock()"),
                "melt must layer over foreign TempBlocks instead of destroying their lifecycle");
        assertTrue(source.contains("visibleData.getMaterial() == Material.SNOW_BLOCK")
                        && source.contains("return Material.AIR.createBlockData()"),
                "full snow blocks and layered snow must both melt");
        assertFalse(meltedCleanup.contains("revertBlock(") || meltedCleanup.contains("setType("),
                "ending the input must not immediately undo the melt");
        assertTrue(source.contains("Material.WATER.createBlockData(), MELT_REVERT_MILLIS, this"));
        assertTrue(source.contains("Material.AIR.createBlockData(), MELT_REVERT_MILLIS, this"));
    }

    @Test
    void phaseChangeMeltAdvancesTheClientLifecycleToo() throws IOException {
        Path path = Path.of("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        if (!Files.exists(path)) path = Path.of("fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        assertTrue(Files.exists(path));
        String runtime = Files.readString(path);
        String decision = method(runtime, "public static boolean shouldPredictInput",
                "public static boolean canActivate");

        assertTrue(decision.contains("return supports(abilityName)"));
        assertFalse(decision.contains("PhaseChange") && decision.contains("LEFT_CLICK"),
                "server-only melt would strand the client's frozen TempBlock and collision");
    }

    private static String common(String relative) throws IOException {
        Path path = Path.of("../common/src/main/java").resolve(relative);
        if (!Files.exists(path)) path = Path.of("common/src/main/java").resolve(relative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }

    private static String method(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start);
        return source.substring(start, end);
    }
}
