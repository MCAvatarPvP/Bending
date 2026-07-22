package com.projectkorra.projectkorra.fabric.client.prediction.action;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards generic native-action correlation when client and Paper ordinals drift. */
class NativeActionSequencePairingBoundaryTest {
    @Test
    void paperReceiptMapsToTheSemanticLocalCastInsteadOfTheRawOrdinal() throws IOException {
        final String correlation = read(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/action/ClientNativeActionCorrelation.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/prediction/action/ClientNativeActionCorrelation.java");

        assertTrue(correlation.contains("aliases.get(receipt.actionSequence())"));
        assertTrue(correlation.contains("posePairingScore(candidate, receipt)"),
                "an unmatched older animation must not shift every comparison back by one cast");
        assertTrue(correlation.contains("CapturedInputPose.signedAngleDelta(local.yaw, paper.yaw())"));
        assertTrue(correlation.contains("aliases.put(receipt.actionSequence(), closest.sequence)"),
                "same-cast correlation must drive every authoritative gameplay receipt");
        assertTrue(correlation.contains("mappedActionSequence(aliases, paperSequence)"));
    }

    private static String read(final String first, final String second) throws IOException {
        Path path = Path.of(first);
        if (!Files.exists(path)) path = Path.of(second);
        assertTrue(Files.exists(path), "missing source " + first);
        return Files.readString(path);
    }
}
