package com.projectkorra.projectkorra.prediction.block;

import java.util.Locale;

/** Authority rule for direct moved-earth and source-hole world writes. */
public final class DirectBlockAuthorityPolicy {
    private DirectBlockAuthorityPolicy() {
    }

    /**
     * A confirmed causal Earth transaction remains client-visual authority.
     * RaiseEarth and EarthSmash can legitimately execute different physical
     * coordinates or ordinals across the network delay; requiring coordinate
     * or state equality lets Paper's intermediate air replace the local move.
     */
    public static boolean mayConceal(final boolean exactEffect,
                                     final boolean movedEarthLifecycle,
                                     final boolean knownCause) {
        return exactEffect || movedEarthLifecycle || knownCause;
    }

    /**
     * Ability-aware overload used by the runtime. Concealment still follows
     * causal ownership for every Earth ability; the name identifies the small
     * subset whose latency-offset coordinate set needs an explicit handoff.
     */
    public static boolean mayConceal(final String ability,
                                     final boolean exactEffect,
                                     final boolean movedEarthLifecycle,
                                     final boolean knownCause) {
        return mayConceal(exactEffect, movedEarthLifecycle, knownCause);
    }

    /**
     * These abilities write a multi-coordinate shape over several progress
     * frames, so their masks need a transaction-wide convergence fence. The
     * fence may be retired after both simulations independently reach the same
     * final state; it must never repaint an intermediate Paper frame.
     */
    public static boolean requiresAuthoritativeHandoff(final String ability) {
        if (ability == null) return false;
        return switch (ability.toLowerCase(Locale.ROOT)) {
            case "raiseearth", "earthsmash" -> true;
            default -> false;
        };
    }
}
