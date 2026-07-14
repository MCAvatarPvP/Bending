package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.BendingPlayer;

/**
 * Loader-neutral observation point for cooldowns created by exact prediction.
 */
public final class CooldownSync {
    private static volatile Listener listener;

    private CooldownSync() {
    }

    public static void install(Listener newListener) {
        listener = newListener;
    }

    public static void clear(Listener expected) {
        if (listener == expected) listener = null;
    }

    public static void added(BendingPlayer player, String ability, long expiresAtMillis) {
        Listener current = listener;
        if (current != null) current.onAdded(player, ability, expiresAtMillis);
    }

    public static void removed(BendingPlayer player, String ability) {
        Listener current = listener;
        if (current != null) current.onRemoved(player, ability);
    }

    public static void airBlastReset(BendingPlayer player) {
        Listener current = listener;
        if (current != null) current.onAirBlastReset(player);
    }

    public static void airBlastRegenerated(BendingPlayer player) {
        Listener current = listener;
        if (current != null) current.onAirBlastRegenerated(player);
    }

    public interface Listener {
        void onAdded(BendingPlayer player, String ability, long expiresAtMillis);

        void onRemoved(BendingPlayer player, String ability);

        default void onAirBlastReset(BendingPlayer player) {
        }

        default void onAirBlastRegenerated(BendingPlayer player) {
        }
    }
}
