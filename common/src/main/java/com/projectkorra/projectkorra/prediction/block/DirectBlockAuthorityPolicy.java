package com.projectkorra.projectkorra.prediction.block;

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
}
