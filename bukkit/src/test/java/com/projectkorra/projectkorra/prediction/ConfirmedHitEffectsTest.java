package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmedHitEffectsTest {
    @Test
    void authoritativeSoundContextIncludesCasterAndRestoresAfterPlayback() {
        assertFalse(ConfirmedHitEffects.isBroadcastingAuthoritativeSound());
        ConfirmedHitEffects.broadcastSound(() -> {
            assertTrue(ConfirmedHitEffects.isBroadcastingAuthoritativeSound());
            ConfirmedHitEffects.broadcastSound(
                    () -> assertTrue(ConfirmedHitEffects.isBroadcastingAuthoritativeSound()));
        });
        assertFalse(ConfirmedHitEffects.isBroadcastingAuthoritativeSound());
    }

    @Test
    void authoritativeSoundContextRestoresAfterFailure() {
        assertThrows(IllegalStateException.class,
                () -> ConfirmedHitEffects.broadcastSound(() -> {
                    throw new IllegalStateException("sound failure");
                }));
        assertFalse(ConfirmedHitEffects.isBroadcastingAuthoritativeSound());
    }
}
