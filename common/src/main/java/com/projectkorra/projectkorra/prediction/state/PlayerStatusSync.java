package com.projectkorra.projectkorra.prediction.state;

import com.projectkorra.projectkorra.BendingPlayer;

/** Publishes authoritative player-status transitions used by prediction. */
public final class PlayerStatusSync {
    private static volatile Listener listener;

    private PlayerStatusSync() {
    }

    public static void install(final Listener value) {
        listener = value;
    }

    public static void clear(final Listener value) {
        if (listener == value) listener = null;
    }

    public static void chiBlockedChanged(final BendingPlayer player, final boolean chiBlocked) {
        final Listener current = listener;
        if (current != null && player != null) {
            current.onChiBlockedChanged(player, chiBlocked);
        }
    }

    public interface Listener {
        void onChiBlockedChanged(BendingPlayer player, boolean chiBlocked);
    }
}
