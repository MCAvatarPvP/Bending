package com.projectkorra.projectkorra.prediction;

/**
 * Ordering policy for client-predicted block changes echoed by the server.
 */
public final class PredictionEchoPolicy {
    private PredictionEchoPolicy() {
    }

    public static boolean shouldSuppress(boolean explicitlyForced, boolean hasNewerPredictedState,
                                         boolean clientAlreadyMatches) {
        // Explicitly-forced echoes are only emitted after reconciliation has
        // proven this packet belongs to an older layer of the same accepted
        // action. Applying it would erase a newer client redraw.
        return clientAlreadyMatches || explicitlyForced || hasNewerPredictedState;
    }

    public static boolean mayApplyLocalMutationOverServerTemp(boolean serverTempActive,
                                                              boolean requestedMatchesServerTemp) {
        // Once the server has confirmed a temporary layer, only its REVERT
        // operation may uncover what is beneath it. This prevents client
        // ability cleanup from creating air/solid ghost blocks.
        return !serverTempActive || requestedMatchesServerTemp;
    }
}
