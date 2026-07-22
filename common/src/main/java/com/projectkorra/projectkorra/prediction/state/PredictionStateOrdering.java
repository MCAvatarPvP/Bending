package com.projectkorra.projectkorra.prediction.state;

/** Ordering rules for periodic authority snapshots versus local inputs. */
public final class PredictionStateOrdering {
    private PredictionStateOrdering() {
    }

    public static boolean snapshotCoversLatestInput(final long acknowledgedSequence,
                                                     final long latestLocalSequence) {
        return latestLocalSequence <= acknowledgedSequence;
    }
}
