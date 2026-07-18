package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeActionCorrelationTest {
    @Test
    void paperOrdinalIsNeverTreatedAsLocalOrdinalWithoutAReceiptPair() {
        final Map<Long, Long> aliases = new LinkedHashMap<>();
        aliases.put(30L, 31L);

        assertEquals(31L, ExactPredictionRuntime.mappedActionSequence(aliases, 30L));
        assertEquals(0L, ExactPredictionRuntime.mappedActionSequence(aliases, 31L));
    }

    @Test
    void paperHighWaterMarkTranslatesThroughThePairedNativeStream() {
        final Map<Long, Long> aliases = new LinkedHashMap<>();
        aliases.put(12L, 14L);
        aliases.put(16L, 18L);
        aliases.put(30L, 31L);

        assertEquals(14L, ExactPredictionRuntime.mappedAcknowledgedSequence(aliases, 15L));
        assertEquals(18L, ExactPredictionRuntime.mappedAcknowledgedSequence(aliases, 29L));
        assertEquals(31L, ExactPredictionRuntime.mappedAcknowledgedSequence(aliases, 30L));
    }

    @Test
    void everyOwnedGameplayReceiptUsesTheCorrelatedLocalIdentity() throws IOException {
        Path path = Path.of("src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        if (!Files.exists(path)) path = Path.of(
                "fabric/src/main/java/com/projectkorra/projectkorra/fabric/client/ExactPredictionRuntime.java");
        final String runtime = Files.readString(path);

        assertTrue(runtime.contains("localActionSequence(operation.actionSequence())"));
        assertTrue(runtime.contains("localActionSequence(receipt.actionSequence())"));
        assertTrue(runtime.contains("localActionSequence(removed.actionSequence())"));
        assertTrue(runtime.contains("localActionSequence(owner.actionSequence())"));
        assertTrue(runtime.contains("localActionSequence(prepare.actionSequence())"));
        assertTrue(runtime.contains("localAcknowledgedSequence(removed.acknowledgedSequence())"));
        assertTrue(runtime.contains("localAcknowledgedSequence(acknowledgedSequence)"));
        assertFalse(runtime.contains("actions.get(removed.actionSequence())"));
        assertFalse(runtime.contains("abilityCreationActions.get(ability), removed.actionSequence()"));
    }
}
