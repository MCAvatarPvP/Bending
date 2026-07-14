package com.projectkorra.projectkorra.prediction;

/**
 * Ordering policy for client-predicted block changes echoed by the server.
 */
public final class PredictionEchoPolicy {
    private PredictionEchoPolicy() {
    }

    public static boolean shouldSuppress(boolean explicitlyForced, boolean hasNewerPredictedState,
                                         boolean clientAlreadyMatches) {
        // A matching predicted write does not prove that a newer local write
        // will also happen on the server. Tiny timing differences in moving
        // water/earth routinely produce one extra local state. Suppressing the
        // older server packet in that situation can preserve the extra state
        // forever. Authority is therefore skipped only when applying it would
        // be a literal no-op in the client world.
        return clientAlreadyMatches;
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
