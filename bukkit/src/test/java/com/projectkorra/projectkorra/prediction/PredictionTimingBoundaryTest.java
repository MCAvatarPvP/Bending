package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictionTimingBoundaryTest {
    @Test
    void predictedServerAbilitiesStartAtTheMappedActionAge() throws IOException {
        String core = read("../common/src/main/java/com/projectkorra/projectkorra/ability/CoreAbility.java",
                "common/src/main/java/com/projectkorra/projectkorra/ability/CoreAbility.java");
        String timing = read("../common/src/main/java/com/projectkorra/projectkorra/prediction/PredictionTiming.java",
                "common/src/main/java/com/projectkorra/projectkorra/prediction/PredictionTiming.java");
        String paper = read("src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/PaperPredictionServer.java");
        String fabric = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java");

        assertTrue(core.contains("public void alignPredictedStart(final long elapsedMillis)"));
        assertTrue(core.contains("this.startTime = Math.max(0L, this.startTime - bounded)"));
        assertTrue(core.contains("this.startTick -= Math.max(0L, (bounded + 49L) / 50L)"));
        assertTrue(timing.contains("ability.alignPredictedStart(compensation(ability))"));
        assertTrue(paper.contains("PredictionTiming.alignStart(candidate)"));
        assertTrue(fabric.contains("PredictionTiming.alignStart(candidate)"));
    }

    private static String read(String moduleRelative, String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }
}
