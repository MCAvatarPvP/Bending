package com.projectkorra.projectkorra.prediction.state;

import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Loader-neutral observation point for cooldowns created by exact prediction.
 */
public final class CooldownSync {
    public static final long INPUT_LENIENCY_MILLIS = 100L;

    private static volatile Listener listener;
    private static final ThreadLocal<InputVeto> INPUT_VETO = new ThreadLocal<>();
    private static final ThreadLocal<InputLeniency> INPUT_LENIENCY = new ThreadLocal<>();

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
        if (current != null) current.onAdded(AbilityExecutionContext.current(), player, ability, expiresAtMillis);
    }

    public static void removed(BendingPlayer player, String ability) {
        Listener current = listener;
        if (current != null) current.onRemoved(player, ability);
    }

    /** Publishes a complete authoritative cooldown replacement. */
    public static void synchronize(BendingPlayer player) {
        Listener current = listener;
        if (current != null && current.isAuthoritative()) current.onSynchronize(player);
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

    /**
     * Runs one delayed native input with the cooldown state observed by the
     * predicting client when it sent that input. The normal input handler still
     * executes, so Paper records combo steps and performs unrelated input
     * transitions; only constructors/checks for the vetoed cooldown names see
     * an active cooldown.
     */
    public static <T> T runInputVeto(final UUID playerId, final Collection<String> abilities,
                                     final Supplier<T> input) {
        if (input == null) return null;
        if (playerId == null || abilities == null || abilities.isEmpty()) return input.get();
        final Set<String> normalized = new HashSet<>();
        for (String ability : abilities) {
            if (ability != null && !ability.isBlank()) {
                normalized.add(ability.toLowerCase(Locale.ROOT));
            }
        }
        if (normalized.isEmpty()) return input.get();
        final InputVeto previous = INPUT_VETO.get();
        INPUT_VETO.set(new InputVeto(playerId, Set.copyOf(normalized)));
        try {
            return input.get();
        } finally {
            if (previous == null) INPUT_VETO.remove();
            else INPUT_VETO.set(previous);
        }
    }

    public static boolean isInputVetoed(final UUID playerId, final String ability) {
        final InputVeto veto = INPUT_VETO.get();
        return veto != null && playerId != null && playerId.equals(veto.playerId)
                && ability != null && veto.abilities.contains(ability.toLowerCase(Locale.ROOT));
    }

    /**
     * Runs one trusted prediction input with a small cooldown-boundary grace.
     * The allowance applies only to the named cooldowns for this exact player
     * and disappears as soon as the native input callback returns.
     */
    public static <T> T runInputLeniency(final UUID playerId, final Collection<String> abilities,
                                         final long leniencyMillis, final Supplier<T> input) {
        if (input == null) return null;
        if (playerId == null || abilities == null || abilities.isEmpty() || leniencyMillis <= 0L) {
            return input.get();
        }
        final Set<String> normalized = new HashSet<>();
        for (String ability : abilities) {
            if (ability != null && !ability.isBlank()) {
                normalized.add(ability.toLowerCase(Locale.ROOT));
            }
        }
        if (normalized.isEmpty()) return input.get();
        final InputLeniency previous = INPUT_LENIENCY.get();
        INPUT_LENIENCY.set(new InputLeniency(playerId, Set.copyOf(normalized), leniencyMillis));
        try {
            return input.get();
        } finally {
            if (previous == null) INPUT_LENIENCY.remove();
            else INPUT_LENIENCY.set(previous);
        }
    }

    /** Returns the comparison time for a cooldown check in the current input. */
    public static long effectiveInputTime(final UUID playerId, final String ability,
                                          final long currentTimeMillis) {
        final InputLeniency leniency = INPUT_LENIENCY.get();
        if (leniency == null || playerId == null || !playerId.equals(leniency.playerId)
                || ability == null || !leniency.abilities.contains(ability.toLowerCase(Locale.ROOT))) {
            return currentTimeMillis;
        }
        if (currentTimeMillis > Long.MAX_VALUE - leniency.millis) return Long.MAX_VALUE;
        return currentTimeMillis + leniency.millis;
    }

    /** Awards hit regeneration immediately from the authoritative server contact. */
    public static void regenerateAirBlastOnConfirmedHit(final Ability ability, final Entity target,
                                                        final BendingPlayer player, final double amount,
                                                        final double maximum) {
        if (!isAuthoritative() || ability == null || target == null || player == null
                || !Double.isFinite(amount) || amount <= 0) {
            return;
        }
        final double before = player.getAirBlastDecay();
        player.regenerateAirBlastDecay(amount, maximum);
        if (player.getAirBlastDecay() != before) airBlastRegenerated(player);
    }

    public interface Listener {
        default boolean isAuthoritative() {
            return true;
        }

        void onAdded(CoreAbility source, BendingPlayer player, String ability, long expiresAtMillis);

        void onRemoved(BendingPlayer player, String ability);

        default void onSynchronize(BendingPlayer player) {
        }

        default void onAirBlastReset(BendingPlayer player) {
        }

        default void onAirBlastRegenerated(BendingPlayer player) {
        }
    }

    private record InputVeto(UUID playerId, Set<String> abilities) {
    }

    private record InputLeniency(UUID playerId, Set<String> abilities, long millis) {
    }
}
