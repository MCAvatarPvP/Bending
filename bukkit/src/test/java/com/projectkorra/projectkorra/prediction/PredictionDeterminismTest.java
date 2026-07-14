package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PredictionDeterminismTest {
    @Test
    void samePlayerActionAndScopeProduceTheSameGameplayChoices() {
        UUID player = UUID.randomUUID();
        int[] client = sequence(player, 41L, "PhaseChange");
        int[] server = sequence(player, 41L, "PhaseChange");
        int[] nextAction = sequence(player, 42L, "PhaseChange");

        assertArrayEquals(client, server);
        assertFalse(java.util.Arrays.equals(client, nextAction));
    }

    private static int[] sequence(UUID player, long action, String scope) {
        int[] values = new int[16];
        PredictionDeterminism.run(action, () -> {
            Random random = PredictionDeterminism.random(player, scope);
            for (int index = 0; index < values.length; index++) values[index] = random.nextInt(10_000);
        });
        return values;
    }
}
