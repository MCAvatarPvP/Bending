package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards intentional server cooldown replacement across the prediction boundary. */
class CooldownSynchronizationBoundaryTest {
    @Test
    void explicitServerSynchronizationOverridesLocalPredictionGenerations() throws IOException {
        final String player = read("../common/src/main/java/com/projectkorra/projectkorra/BendingPlayer.java",
                "common/src/main/java/com/projectkorra/projectkorra/BendingPlayer.java");
        final String server = read("src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");
        final String protocol = read("src/main/java/com/projectkorra/projectkorra/prediction/protocol/PaperPredictionProtocol.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/protocol/PaperPredictionProtocol.java");
        final String payloads = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/protocol/PredictionPayloads.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/protocol/PredictionPayloads.java");
        final String client = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        final String runtime = read("../fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");

        assertTrue(player.contains("public void clearCooldowns()")
                        && player.contains("this.synchronizeCooldowns();")
                        && player.contains("CooldownSync.synchronize(this)"));
        assertTrue(server.contains("public static void synchronizeCooldowns(final Player player)")
                        && server.contains("session.predictedCooldowns.clear()")
                        && server.contains("PaperPredictionSnapshot.cooldowns(bending)"));
        assertTrue(protocol.contains("projectkorra:cooldown_sync")
                        && payloads.contains("id(\"cooldown_sync\")"));
        assertTrue(client.contains("rememberAuthoritativeCooldowns(authoritative)")
                        && client.contains("ExactPredictionRuntime.synchronizeCooldowns(authoritative)"));
        assertTrue(runtime.contains("this.bendingPlayer.getCooldowns().clear()")
                        && runtime.contains("this.cooldownAuthority.clear()"),
                "an explicit server synchronization must replace, rather than reconcile, local cooldown generations");
    }

    private static String read(final String moduleRelative, final String rootRelative) throws IOException {
        Path path = Path.of(moduleRelative);
        if (!Files.exists(path)) path = Path.of(rootRelative);
        assertTrue(Files.exists(path));
        return com.projectkorra.projectkorra.testutil.PredictionSourceBundle.read(path);
    }
}
