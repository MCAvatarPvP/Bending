package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps prediction responsibilities discoverable and the transport topology unambiguous. */
class PredictionArchitectureBoundaryTest {
    @Test
    void commonPredictionContractsAreGroupedByBehavior() throws IOException {
        final Path prediction = existingDirectory(
                "../common/src/main/java/com/projectkorra/projectkorra/prediction",
                "common/src/main/java/com/projectkorra/projectkorra/prediction");

        try (var rootFiles = Files.list(prediction)) {
            assertFalse(rootFiles.anyMatch(path -> path.getFileName().toString().endsWith(".java")),
                    "loader-neutral prediction contracts must not return to one flat package");
        }
        for (String behavior : new String[]{"action", "authority", "block", "hit", "movement", "state"}) {
            assertTrue(Files.isDirectory(prediction.resolve(behavior)), "missing behavior package: " + behavior);
        }
    }

    @Test
    void fabricContainsClientAuthoritiesButNoPredictionServer() throws IOException {
        final String runtime = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");

        for (String authority : new String[]{
                "ClientDirectBlockAuthority", "ClientTempBlockAuthority",
                "ClientEntityReconciliation", "ClientVelocityAuthority",
                "ClientPlayerStateAuthority", "PredictionCooldownAuthority"}) {
            assertTrue(runtime.contains(authority), "runtime must delegate to " + authority);
        }
        assertFalse(exists(
                "src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/PredictionServer.java"),
                "prediction authority is Paper-only");
    }

    @Test
    void airBlastUsesGenericCorrelationWithoutATraceProtocol() throws IOException {
        final String runtime = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        final String correlation = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/action/ClientNativeActionCorrelation.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/action/ClientNativeActionCorrelation.java");
        final String payloads = source(
                "src/main/java/com/projectkorra/projectkorra/fabric/prediction/protocol/PredictionPayloads.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/prediction/protocol/PredictionPayloads.java");

        assertTrue(runtime.contains("nativeActions.correlate")
                && correlation.contains("posePairingScore"));
        assertFalse(runtime.contains("AirBlastTrace") || payloads.contains("AirBlastTrace"));
    }

    private static Path existingDirectory(final String first, final String second) {
        final Path firstPath = Path.of(first);
        final Path path = Files.isDirectory(firstPath) ? firstPath : Path.of(second);
        assertTrue(Files.isDirectory(path), "missing directory: " + path);
        return path;
    }

    private static String source(final String first, final String second) throws IOException {
        final Path firstPath = Path.of(first);
        final Path path = Files.exists(firstPath) ? firstPath : Path.of(second);
        assertTrue(Files.exists(path), "missing source: " + path);
        return Files.readString(path);
    }

    private static boolean exists(final String first, final String second) {
        return Files.exists(Path.of(first)) || Files.exists(Path.of(second));
    }
}
