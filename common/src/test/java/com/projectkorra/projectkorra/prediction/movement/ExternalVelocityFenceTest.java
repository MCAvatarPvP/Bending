package com.projectkorra.projectkorra.prediction.movement;

import com.projectkorra.projectkorra.prediction.movement.ExternalVelocityFence;
import com.projectkorra.projectkorra.prediction.movement.VelocityReceiptPolicy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalVelocityFenceTest {
    @Test
    void paperImpulseSurvivesTheLateLocomotionWriteAfterItsFirstCommit() {
        final ExternalVelocityFence<String> fence = new ExternalVelocityFence<>();
        fence.receive(7, "paper-knockback", true);

        assertTrue(fence.blocksPredictedWrite(7),
                "a Scooter/Jet write in the packet's current tick must not replace Paper");
        assertEquals("paper-knockback", fence.afterLocalProgress(7, 100L).orElseThrow());
        assertTrue(fence.blocksPredictedWrite(7),
                "the next prediction heartbeat occurs after movement consumes the impulse and must also be fenced");
        assertTrue(fence.afterLocalProgress(7, 101L).isEmpty(),
                "the authoritative vector is committed once, not reapplied as a second push");
        assertTrue(fence.blocksPredictedWrite(7),
                "packet jitter cannot expire a live locomotion writer before Paper resolves it");
        fence.release(7);
        assertFalse(fence.blocksPredictedWrite(7),
                "ordinary prediction may resume after exact authoritative resolution");
    }

    @Test
    void hitWithoutALiveVelocityWriterReleasesAfterMovementConsumption() {
        final ExternalVelocityFence<String> fence = new ExternalVelocityFence<>();
        fence.receive(7, "paper-knockback", false);
        assertEquals("paper-knockback", fence.afterLocalProgress(7, 100L).orElseThrow());
        assertTrue(fence.blocksPredictedWrite(7));
        assertTrue(fence.afterLocalProgress(7, 101L).isEmpty());
        assertFalse(fence.blocksPredictedWrite(7));
    }

    @Test
    void externalHitDoesNotRequireTheAttackersPredictionAction() {
        assertTrue(VelocityReceiptPolicy.accepts(false, 0L, 1, 7),
                "the target needs ownership metadata even when the attacker has no exact-prediction session/action");
        assertFalse(VelocityReceiptPolicy.accepts(true, 0L, 1, 7),
                "an uncorrelated self-owned packet cannot safely suppress vanilla velocity");
    }

    @Test
    void announcedVelocityPairsOnlyWithItsQuantizedVanillaPacket() {
        assertTrue(VelocityReceiptPolicy.matchesPacket(
                0.40004, 0.12504, -0.25004, 0.4, 0.125, -0.25));
        assertTrue(VelocityReceiptPolicy.matchesPacket(
                5.0, -5.0, 0.0, 3.9, -3.9, 0.0),
                "Minecraft clamps the announced write before encoding the packet");
        assertFalse(VelocityReceiptPolicy.matchesPacket(
                0.4, 0.125, -0.25, -0.4, 0.125, 0.25),
                "a stale ownership receipt must not consume a later impulse");
    }

    @Test
    void missingMutationSuppressesOnlyASelfOwnedLocalPlayerEcho() {
        assertTrue(VelocityReceiptPolicy.suppressesMissingMutation(true, true),
                "the local player already executed its own predicted push");
        assertFalse(VelocityReceiptPolicy.suppressesMissingMutation(true, false),
                "remote target movement is server-owned unless explicitly predicted");
        assertFalse(VelocityReceiptPolicy.suppressesMissingMutation(false, true),
                "another player's push must remain authoritative");
    }
}
