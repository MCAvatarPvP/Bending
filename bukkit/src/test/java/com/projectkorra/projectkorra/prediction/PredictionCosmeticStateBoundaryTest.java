package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards authoritative cosmetic parity for the local prediction runtime. */
class PredictionCosmeticStateBoundaryTest {
    @Test
    void cosmeticDefinitionsAndPlayerSelectionsReachTheClientRuntime() throws IOException {
        final String protocol = read(
                "src/main/java/com/projectkorra/projectkorra/prediction/protocol/PaperPredictionProtocol.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/protocol/PaperPredictionProtocol.java");
        final String paper = read(
                "src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        final String payloads = read(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/protocol/PredictionPayloads.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/protocol/PredictionPayloads.java");
        final String client = read(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        final String runtime = read(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        final String config = read(
                "../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/config/ClientPredictionConfig.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/config/ClientPredictionConfig.java");
        final String bendingPlayer = read(
                "../common/src/main/java/com/projectkorra/projectkorra/OfflineBendingPlayer.java",
                "common/src/main/java/com/projectkorra/projectkorra/OfflineBendingPlayer.java");

        assertTrue(protocol.contains("record PlayerCosmetics")
                        && protocol.contains("writePlayerCosmetics(out, cosmetics)"));
        assertTrue(payloads.contains("record PlayerCosmetics")
                        && payloads.contains("new PlayerCosmetics(buf)"));
        assertTrue(paper.contains("bending.getFireColor()")
                        && paper.contains("bending.getAirColor()")
                        && paper.contains("bending.getGliderColor()")
                        && paper.contains("bending.getWaterCosmetic()")
                        && paper.contains("bending.getEarthCosmetic()")
                        && paper.contains("bending.isSprinkleEnabled()")
                        && paper.contains("cosmetics.hashCode()"));
        assertTrue(client.contains("cosmetics = snapshot.cosmetics()")
                        && client.contains("cosmetics = state.cosmetics()"));
        assertTrue(config.contains("CosmeticColor.reloadColors()")
                        && config.contains("GliderColor.reloadColors()")
                        && config.contains("WaterCosmetic.reloadCosmetics()")
                        && config.contains("EarthCosmetic.reloadCosmetics()"));
        assertTrue(runtime.contains("this.bendingPlayer.applyCosmeticState(")
                        && runtime.contains("CosmeticColor.getFireColor")
                        && runtime.contains("CosmeticColor.getAirColor")
                        && runtime.contains("GliderColor.getColor")
                        && runtime.contains("WaterCosmetic.getCosmetic")
                        && runtime.contains("EarthCosmetic.getCosmetic"));

        final String inMemoryApply = between(bendingPlayer,
                "public void applyCosmeticState", "public void setWaterCosmetic");
        assertFalse(inMemoryApply.contains("updatePlayerColumn"),
                "client mirrors must never persist the server-owned cosmetic snapshot");
    }

    private static String between(final String source, final String start, final String end) {
        final int from = source.indexOf(start);
        final int to = source.indexOf(end, Math.max(0, from));
        assertTrue(from >= 0 && to > from);
        return source.substring(from, to);
    }

    private static String read(final String moduleRelative, final String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return com.projectkorra.projectkorra.testutil.PredictionSourceBundle.read(path);
    }
}
