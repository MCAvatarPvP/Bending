package com.projectkorra.projectkorra.prediction.state;

import com.projectkorra.projectkorra.prediction.authority.PredictionServices;
import com.projectkorra.projectkorra.BendingPlayer;

/** Publishes authoritative player-status transitions used by prediction. */
public final class PlayerStatusSync {
    private static volatile Listener listener;

    private PlayerStatusSync() {
    }

    public static void install(final Listener value) {
        PredictionServices.requireGlobalMutation();
        listener = value;
    }

    public static void clear(final Listener value) {
        PredictionServices.requireGlobalMutation();
        if (listener == value) listener = null;
    }

    public static void chiBlockedChanged(final BendingPlayer player, final boolean chiBlocked) {
        final Listener current = PredictionServices.current(Listener.class, listener);
        if (current != null && player != null) {
            current.onChiBlockedChanged(player, chiBlocked);
        }
    }

    public interface Listener {
        void onChiBlockedChanged(BendingPlayer player, boolean chiBlocked);
    }
}
