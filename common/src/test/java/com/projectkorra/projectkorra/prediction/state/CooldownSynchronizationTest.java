package com.projectkorra.projectkorra.prediction.state;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownSynchronizationTest {
    @Test
    void explicitSynchronizationIsPublishedOnlyByTheAuthority() {
        final AtomicBoolean synchronizedState = new AtomicBoolean();
        final CooldownSync.Listener authority = listener(true, synchronizedState);
        CooldownSync.install(authority);
        try {
            CooldownSync.synchronize(null);
            assertTrue(synchronizedState.get());
        } finally {
            CooldownSync.clear(authority);
        }

        synchronizedState.set(false);
        final CooldownSync.Listener prediction = listener(false, synchronizedState);
        CooldownSync.install(prediction);
        try {
            CooldownSync.synchronize(null);
            assertFalse(synchronizedState.get(),
                    "client prediction must not publish its local cooldown map as authoritative");
        } finally {
            CooldownSync.clear(prediction);
        }
    }

    private static CooldownSync.Listener listener(final boolean authoritative,
                                                  final AtomicBoolean synchronizedState) {
        return new CooldownSync.Listener() {
            @Override
            public boolean isAuthoritative() {
                return authoritative;
            }

            @Override
            public void onAdded(final CoreAbility source, final BendingPlayer player,
                                final String ability, final long expiresAtMillis) {
            }

            @Override
            public void onRemoved(final BendingPlayer player, final String ability) {
            }

            @Override
            public void onSynchronize(final BendingPlayer player) {
                synchronizedState.set(true);
            }
        };
    }
}
