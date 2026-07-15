package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;

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

    /**
     * Stamina is authoritative game state. The exact client may simulate
     * stamina spending, but it must never manufacture regeneration from a
     * predicted contact. Deterministic passive regeneration remains locally
     * predicted.
     */
    public static boolean isAuthoritative() {
        final Listener current = listener;
        return current == null || current.isAuthoritative();
    }

    /** Awards hit regeneration in the same confirmed bundle as damage/velocity. */
    public static void regenerateAirBlastOnConfirmedHit(final Ability ability, final Entity target,
                                                        final BendingPlayer player, final double amount,
                                                        final double maximum) {
        if (!isAuthoritative() || ability == null || target == null || player == null
                || !Double.isFinite(amount) || amount <= 0) {
            return;
        }
        final Runnable commit = () -> {
            final double before = player.getAirBlastDecay();
            player.regenerateAirBlastDecay(amount, maximum);
            if (player.getAirBlastDecay() != before) airBlastRegenerated(player);
        };
        if (!HitResolutionSync.defer(HitResolutionSync.Effect.STAMINA, ability, target, commit)) {
            commit.run();
        }
    }

    public interface Listener {
        default boolean isAuthoritative() {
            return true;
        }

        void onAdded(BendingPlayer player, String ability, long expiresAtMillis);

        void onRemoved(BendingPlayer player, String ability);

        default void onAirBlastReset(BendingPlayer player) {
        }

        default void onAirBlastRegenerated(BendingPlayer player) {
        }
    }
}
