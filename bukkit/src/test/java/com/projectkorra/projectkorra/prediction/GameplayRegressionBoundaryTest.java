package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-level boundaries for regressions reported during the TempBlock rewrite. */
class GameplayRegressionBoundaryTest {
    @Test
    void completedRaiseEarthWallsRemainCollapsibleUntilMovedEarthRetires() throws IOException {
        String raise = common("com/projectkorra/projectkorra/earthbending/RaiseEarth.java");
        String earth = common("com/projectkorra/projectkorra/ability/EarthAbility.java");
        String collapse = common("com/projectkorra/projectkorra/earthbending/Collapse.java");
        String progress = method(raise, "public void progress()", "private void untrackAffectedBlocks");
        String remove = method(raise, "public void remove()", "public boolean isRaisedByWall()");

        assertTrue(progress.indexOf("this.completed = true") < progress.indexOf("this.remove()"));
        assertTrue(remove.contains("if (this.raisedByWall && this.completed)"));
        assertTrue(remove.contains("this.wallBlocks.clear()"));
        assertTrue(remove.contains("else") && remove.contains("this.clearTrackedWallBlocks()"),
                "cancelled and incomplete raises must not leak wall identity");
        assertTrue(raise.contains("ConcurrentHashMap<WallKey, Integer> WALL_BLOCKS")
                        && raise.contains("WALL_BLOCKS.merge(wallKey(affected), 1, Integer::sum)")
                        && raise.contains("WALL_BLOCKS.computeIfPresent(wallKey(wallBlock)"),
                "overlapping raises must share value-based, reference-counted wall ownership");
        assertTrue(earth.contains("RaiseEarth.revertWallAffectedBlock(block)"));
        assertTrue(earth.contains("RaiseEarth.clearWallBlocks()"));
        assertTrue(collapse.contains("RaiseEarth.blockInWallAffectedBlocks(this.block)"));
        assertTrue(collapse.contains("RaiseEarth.revertWallAffectedBlock(thisBlock)"));
    }

    @Test
    void wallOfFireSweepsEveryEntityAcrossTicks() throws IOException {
        String wall = common("com/projectkorra/projectkorra/firebending/WallOfFire.java");
        String damage = method(wall, "private void damage()", "private void display()");

        assertTrue(wall.contains("private Map<UUID, BoundingBox> previousEntityBounds"));
        assertTrue(wall.contains("private Set<UUID> predictedContacts"));
        assertTrue(wall.contains("!this.predictedContacts.add(entity.getUniqueId())"),
                "one predicted remote contact must not starve later wall targets every tick");
        assertTrue(damage.contains("this.previousEntityBounds.getOrDefault"));
        assertTrue(damage.contains("this.previousEntityBounds.put(entityId, curBB)"));
        assertTrue(damage.contains("this.previousEntityBounds.keySet().retainAll(observed)"));
        assertFalse(damage.contains("lastBoundingBB") || damage.contains("break;"),
                "one intersecting or already-affected entity must not starve every later target");
    }

    @Test
    void dischargeNeverReusesADeadBranchId() throws IOException {
        String discharge = common("com/jedk1/jedcore/ability/firebending/Discharge.java");
        String advance = method(discharge, "private void advanceLocation()", "private double createBranch()");

        assertTrue(discharge.contains("private int nextBranchId = 1"));
        assertTrue(advance.contains("branches.put(nextBranchId++, fork.clone())"));
        assertFalse(advance.contains("branches.put(branches.size() + 1"),
                "removing an old branch must not overwrite a live predicted branch");
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
