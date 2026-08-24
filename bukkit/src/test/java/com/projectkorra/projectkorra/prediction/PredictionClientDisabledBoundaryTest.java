package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents disabled Fabric clients from remaining S2C prediction subscribers. */
class PredictionClientDisabledBoundaryTest {
    @Test
    void explicitDisablePacketRemovesThePaperSession() throws IOException {
        final String server = source(
                "src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java",
                "bukkit/src/main/java/com/projectkorra/projectkorra/prediction/server/PaperPredictionServer.java");

        assertTrue(server.contains("registerIncomingPluginChannel(plugin, PaperPredictionProtocol.CLIENT_DISABLED, this)"),
                "Paper must advertise the client-disabled channel");
        assertTrue(server.contains("case PaperPredictionProtocol.CLIENT_DISABLED -> onClientDisabled"),
                "Paper must route the disable packet");
        assertTrue(server.contains("sessions.remove(player.getUniqueId())"),
                "disabling prediction must remove the subscription instead of retaining a non-ready session");
    }

    private static String source(final String first, final String second) throws IOException {
        Path path = Path.of(first);
        if (!Files.exists(path)) path = Path.of(second);
        assertTrue(Files.exists(path), "missing source: " + path);
        return Files.readString(path);
    }
}
