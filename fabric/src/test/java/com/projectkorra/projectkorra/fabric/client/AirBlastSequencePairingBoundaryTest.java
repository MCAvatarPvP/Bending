package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the diagnostic pairing used when native event ordinals drift. */
class AirBlastSequencePairingBoundaryTest {
    @Test
    void paperTraceIsComparedWithTheSemanticLocalCastInsteadOfAnEmptyRawOrdinal() throws IOException {
        final String runtime = read(
                "src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java",
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");

        assertTrue(runtime.contains("findUnpairedNativeAction(receipt)"));
        assertTrue(runtime.contains("nativePosePairingScore(candidate, receipt)"),
                "an unmatched older animation must not shift every comparison back by one cast");
        assertTrue(runtime.contains("AirBlastTraceSync.signedAngleDelta(local.yaw, paper.yaw())"));
        assertTrue(runtime.contains("nativeActionAliases.put(receipt.actionSequence(), action.sequence)"),
                "same-cast correlation must drive gameplay receipts, not only the debug trace");
        assertTrue(runtime.contains("airBlastSequenceAliases.put(receipt.actionSequence(), action.sequence)"));
        assertTrue(runtime.contains("aliasLocalAirBlastTraces(receipt.actionSequence(), action.sequence)"));
        assertTrue(runtime.contains("airBlastInputDifferences.put(receipt.actionSequence()"),
                "input parity must be reported under Paper's receipt identity");
        assertTrue(runtime.contains("Native sequence pairing: Paper="),
                "the command must expose the raw ordinal disagreement rather than hiding it");
    }

    private static String read(final String first, final String second) throws IOException {
        Path path = Path.of(first);
        if (!Files.exists(path)) path = Path.of(second);
        assertTrue(Files.exists(path), "missing source " + first);
        return Files.readString(path);
    }
}
