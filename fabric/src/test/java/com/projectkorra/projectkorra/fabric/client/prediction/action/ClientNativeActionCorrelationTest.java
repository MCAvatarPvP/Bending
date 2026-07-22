package com.projectkorra.projectkorra.fabric.client.prediction.action;

import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.InputKind;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.NativeAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientNativeActionCorrelationTest {
    @Test
    void closestSemanticPoseWinsWhenAnOlderMatchingSwingWasMissedByPaper() {
        final ClientNativeActionCorrelation correlation = new ClientNativeActionCorrelation();
        final NativeAction paper = receipt(41L, "AirBlast", 10.0, 64.0, 4.0, -179.0F);
        final List<ClientNativeActionCorrelation.Candidate> candidates = List.of(
                candidate(50L, "AirBlast", 2.0, 64.0, 2.0, 20.0F),
                candidate(51L, "AirBlast", 10.0, 64.0, 4.0, 181.0F));

        assertEquals(51L, correlation.correlate(paper, candidates));
        assertEquals(51L, correlation.localSequence(41L));
        assertEquals(41L, correlation.paperSequence(51L));
    }

    @Test
    void priorPaperPairFormsAFloorAndAcknowledgementUsesOnlyPairedActions() {
        final ClientNativeActionCorrelation correlation = new ClientNativeActionCorrelation();
        assertEquals(10L, correlation.correlate(receipt(7L, "AirSwipe", 0, 64, 0, 0),
                List.of(candidate(10L, "AirSwipe", 0, 64, 0, 0))));
        assertEquals(12L, correlation.correlate(receipt(8L, "AirSwipe", 1, 64, 0, 0),
                List.of(
                        candidate(9L, "AirSwipe", 1, 64, 0, 0),
                        candidate(12L, "AirSwipe", 1, 64, 0, 0))));

        assertEquals(12L, correlation.acknowledgedLocalSequence(8L));
        assertEquals(10L, correlation.acknowledgedLocalSequence(7L));
    }

    @Test
    void exactClientTagWinsWhenPoseScoringWouldCrossPairRepeatedEarthSmashInputs() {
        final ClientNativeActionCorrelation correlation = new ClientNativeActionCorrelation();
        final List<ClientNativeActionCorrelation.Candidate> candidates = List.of(
                candidate(50L, "EarthSmash", 0, 64, 0, 0),
                candidate(51L, "EarthSmash", 10, 64, 0, 0));
        final NativeAction first = receipt(41L, 50L, "EarthSmash", 10, 64, 0, 0);
        final NativeAction second = receipt(42L, 51L, "EarthSmash", 0, 64, 0, 0);

        assertEquals(50L, correlation.correlate(first, candidates));
        assertEquals(51L, correlation.correlate(second, candidates));
        assertEquals(50L, correlation.localSequence(41L));
        assertEquals(51L, correlation.localSequence(42L));
    }

    private static NativeAction receipt(final long sequence, final String ability,
                                        final double x, final double y, final double z,
                                        final float yaw) {
        return receipt(sequence, 0L, ability, x, y, z, yaw);
    }

    private static NativeAction receipt(final long sequence, final long clientSequence,
                                        final String ability,
                                        final double x, final double y, final double z,
                                        final float yaw) {
        return new NativeAction(UUID.randomUUID(), sequence, clientSequence, 100L, InputKind.LEFT_CLICK,
                2, ability, x, y, z, yaw, 0.0F, true);
    }

    private static ClientNativeActionCorrelation.Candidate candidate(
            final long sequence, final String ability,
            final double x, final double y, final double z, final float yaw) {
        return new ClientNativeActionCorrelation.Candidate(sequence, InputKind.LEFT_CLICK,
                2, ability, x, y, z, yaw, 0.0F);
    }
}
