package com.projectkorra.projectkorra.prediction;

/**
 * Ordering policy for client-predicted block changes echoed by the server.
 */
public final class PredictionEchoPolicy {
    private PredictionEchoPolicy() {
    }

    public static boolean shouldSuppress(boolean explicitlyForced, boolean hasNewerPredictedState,
                                         boolean clientAlreadyMatches) {
        // Ordered moving abilities can advance several block states during one
        // round trip. A server packet matching an earlier predicted write is an
        // acknowledgement of that step, not permission to repaint the client
        // backwards over a newer step. A genuinely client-only final step is
        // still retired by the measured negative-receipt deadline.
        return explicitlyForced || hasNewerPredictedState || clientAlreadyMatches;
    }

    public static boolean confirmedByLatestAuthority(boolean previouslyConfirmed,
                                                       boolean latestAuthorityMatchesPrediction) {
        // Confirmation describes the latest server state, never historical
        // agreement with an earlier packet.
        return latestAuthorityMatchesPrediction;
    }

    public static boolean mayApplyLocalMutationOverServerTemp(boolean serverTempActive,
                                                              boolean requestedMatchesServerTemp) {
        // Once the server has confirmed a temporary layer, only its REVERT
        // operation may uncover what is beneath it. This prevents client
        // ability cleanup from creating air/solid ghost blocks.
        return !serverTempActive || requestedMatchesServerTemp;
    }
}
