package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps the optional UI setting connected to the prediction lifecycle. */
class ClientBendingSettingBoundaryTest {
    @Test
    void modMenuSettingIsPersistentAndStopsPredictionImmediately() throws IOException {
        final String metadata = source("src/main/resources/fabric.mod.json");
        final String config = source("src/main/java/com/projectkorra/projectkorra/fabric/client/config/ClientBendingConfig.java");
        final String prediction = source("src/main/java/com/projectkorra/projectkorra/fabric/client/PredictionClient.java");
        final String payloads = source("src/main/java/com/projectkorra/projectkorra/fabric/prediction/protocol/PredictionPayloads.java");

        assertTrue(metadata.contains("\"modmenu\"")
                        && metadata.contains("ProjectKorraModMenu"),
                "Fabric metadata must expose the config screen through Mod Menu");
        assertTrue(config.contains("projectkorra-client.properties")
                        && config.contains("clientSideBending")
                        && config.contains("private static boolean enabled = true"),
                "client-side bending must remain enabled by default and persist locally");
        assertTrue(prediction.contains("onClientSideBendingSettingChanged")
                        && prediction.contains("if (!enabled) {")
                        && prediction.contains("PredictionClient.instance().reset(client)")
                        && prediction.contains("new PredictionPayloads.ClientDisabled")
                        && payloads.contains("id(\"client_disabled\")")
                        && prediction.contains("if (!ClientBendingConfig.isEnabled())"),
                "disabling the setting must stop and gate both ends of the prediction runtime");
    }

    private static String source(final String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.exists(path)) path = Path.of("fabric").resolve(relative);
        assertTrue(Files.exists(path), "missing source: " + path);
        return com.projectkorra.projectkorra.testutil.PredictionSourceBundle.read(path);
    }
}
