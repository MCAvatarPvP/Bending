package com.projectkorra.projectkorra.prediction.state;

import com.projectkorra.projectkorra.ability.CoreAbility;

/**
 * Publishes sparse authoritative lifecycle checkpoints for abilities whose
 * next state cannot be inferred from the native input receipt alone.
 *
 * <p>This is a correction boundary, not an alternate activation path. The
 * client still runs the common ability immediately; a checkpoint only repairs
 * the rare case where validation or a charge boundary produced a different
 * state on Paper.</p>
 */
public final class AbilityCheckpointSync {
    private static volatile Listener listener;

    private AbilityCheckpointSync() {
    }

    public static void install(final Listener value) {
        listener = value;
    }

    public static void clear(final Listener value) {
        if (listener == value) listener = null;
    }

    public static void publish(final CoreAbility ability) {
        final Listener current = listener;
        if (current != null && ability != null && ability.isStarted() && !ability.isRemoved()) {
            current.onCheckpoint(ability);
        }
    }

    public interface Listener {
        void onCheckpoint(CoreAbility ability);
    }
}
