package com.projectkorra.projectkorra.prediction;

/**
 * Selects the final rendered state after an authoritatively removed ability
 * has synchronously closed its locally predicted TempBlock layers.
 */
public final class TempBlockTeardownPolicy {
    private TempBlockTeardownPolicy() {
    }

    public static <S> S select(final S remainingLocalLayer,
                               final S hiddenOwnedServerViewer,
                               final S visibleServerPhysical,
                               final S directBlockViewer,
                               final S capturedLocalUnderlay,
                               final S currentWorldState) {
        if (remainingLocalLayer != null) return remainingLocalLayer;
        if (hiddenOwnedServerViewer != null) return hiddenOwnedServerViewer;
        if (visibleServerPhysical != null) return visibleServerPhysical;
        if (directBlockViewer != null) return directBlockViewer;
        if (capturedLocalUnderlay != null) return capturedLocalUnderlay;
        return currentWorldState;
    }
}
