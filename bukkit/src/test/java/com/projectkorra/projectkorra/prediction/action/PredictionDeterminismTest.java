package com.projectkorra.projectkorra.prediction.action;

import com.projectkorra.projectkorra.prediction.action.PredictionActionSeed;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;

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

    @Test
    void explicitActionKeepsDelayedWorkDeterministicOutsideTheInputThread() {
        UUID player = UUID.randomUUID();
        Random client = PredictionDeterminism.random(player, "Torrent:delayed-thaw", 91L);
        Random server = PredictionDeterminism.random(player, "Torrent:delayed-thaw", 91L);
        int[] clientValues = new int[16];
        int[] serverValues = new int[16];
        for (int index = 0; index < clientValues.length; index++) {
            clientValues[index] = client.nextInt(10_000);
            serverValues[index] = server.nextInt(10_000);
        }
        assertArrayEquals(clientValues, serverValues);
    }

    @Test
    void differentLoaderOrdinalsUseTheSameSemanticInputSeed() {
        final UUID player = UUID.randomUUID();
        final long seed = PredictionActionSeed.from("LEFT_CLICK", 2, "AirBlast",
                -2243.59530, 80.62000, 1157.74720, 355.885589599609F, 55.307F);
        final int[] fabric = seededSequence(player, 31L, seed, "PhaseChange");
        final int[] paper = seededSequence(player, 30L, seed, "PhaseChange");

        assertArrayEquals(fabric, paper);
    }

    @Test
    void equivalentWrappedYawProducesTheSameSemanticSeed() {
        final long local = PredictionActionSeed.from("LEFT_CLICK", 0, "AirBlast",
                1.25, 64.0, -9.5, 355.885589599609F, 55.307F);
        final long paper = PredictionActionSeed.from("LEFT_CLICK", 0, "AirBlast",
                1.25, 64.0, -9.5, -4.114410400391F, 55.307F);

        org.junit.jupiter.api.Assertions.assertEquals(local, paper);
    }

    private static int[] sequence(UUID player, long action, String scope) {
        int[] values = new int[16];
        PredictionDeterminism.run(action, () -> {
            Random random = PredictionDeterminism.random(player, scope);
            for (int index = 0; index < values.length; index++) values[index] = random.nextInt(10_000);
        });
        return values;
    }

    private static int[] seededSequence(UUID player, long action, long seed, String scope) {
        int[] values = new int[16];
        PredictionDeterminism.run(action, seed, () -> {
            Random random = PredictionDeterminism.random(player, scope);
            for (int index = 0; index < values.length; index++) values[index] = random.nextInt(10_000);
        });
        return values;
    }
}
