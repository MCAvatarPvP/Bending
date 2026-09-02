package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.prediction.protocol.PaperPredictionProtocol;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards permission-gated constructor branches such as WaterSpoutWave. */
class PredictionPermissionParityBoundaryTest {

    @Test
    void waterSpoutWaveProbeUsesTheSynchronizedDecision() throws IOException {
        String spout = read("../common/src/main/java/com/projectkorra/projectkorra/waterbending/WaterSpout.java",
                "common/src/main/java/com/projectkorra/projectkorra/waterbending/WaterSpout.java");
        String wave = read("../common/src/main/java/com/projectkorra/projectkorra/waterbending/WaterSpoutWave.java",
                "common/src/main/java/com/projectkorra/projectkorra/waterbending/WaterSpoutWave.java");

        assertTrue(spout.contains("new WaterSpoutWave(player, WaterSpoutWave.AbilityType.CLICK)")
                        && spout.contains("spoutWave.isStarted() && !spoutWave.isRemoved()"),
                "the Wave probe is the branch that decides whether normal WaterSpout starts");
        assertTrue(wave.contains("bPlayer.isOnCooldown(\"WaterSpoutWave\")")
                        && wave.contains("player.hasPermission(\"bending.ability.WaterSpout.Wave\")"),
                "cooldown-dependent WaterSpout behavior must share the same Wave permission decision on both loaders");
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from));
        assertTrue(from >= 0 && to > from);
        return source.substring(from, to);
    }

    private static String read(String moduleRelative, String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return Files.readString(path);
    }
}
