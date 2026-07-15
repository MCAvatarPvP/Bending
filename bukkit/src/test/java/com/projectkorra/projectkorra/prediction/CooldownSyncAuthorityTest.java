package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.BendingPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownSyncAuthorityTest {
    private CooldownSync.Listener installed;

    @AfterEach
    void clearListener() {
        if (installed != null) CooldownSync.clear(installed);
    }

    @Test
    void exactClientCannotAwardContactStamina() {
        installed = new CooldownSync.Listener() {
            @Override
            public boolean isAuthoritative() {
                return false;
            }

            @Override
            public void onAdded(BendingPlayer player, String ability, long expiresAtMillis) {
            }

            @Override
            public void onRemoved(BendingPlayer player, String ability) {
            }
        };
        CooldownSync.install(installed);

        assertFalse(CooldownSync.isAuthoritative());
    }

    @Test
    void serverAndNonPredictionRuntimeRemainAuthoritative() {
        assertTrue(CooldownSync.isAuthoritative());
    }
}
