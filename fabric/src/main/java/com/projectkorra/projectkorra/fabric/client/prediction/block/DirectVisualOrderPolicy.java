package com.projectkorra.projectkorra.fabric.client.prediction.block;

/**
 * Chooses which client visual may survive a delayed direct-block receipt.
 *
 * <p>Action sequence is useful only between receipt-only masks. A long-lived
 * ability can keep producing local writes after a numerically newer action has
 * started, so local visual revisions are the actual render chronology.</p>
 */
final class DirectVisualOrderPolicy {
    enum Source {
        RECEIPT,
        EXISTING,
        OBSERVED
    }

    private DirectVisualOrderPolicy() {
    }

    static Source select(final boolean existingPresent,
                         final boolean existingLocallyPredicted,
                         final long existingActionSequence,
                         final long existingVisualRevision,
                         final boolean observedPresent,
                         final long observedVisualRevision,
                         final long incomingActionSequence,
                         final long exactLocalRevision) {
        final boolean retainExisting = existingPresent
                && (existingLocallyPredicted
                ? exactLocalRevision <= 0L
                || existingVisualRevision >= exactLocalRevision
                : exactLocalRevision <= 0L
                && existingActionSequence >= incomingActionSequence);
        final long selectedRevision = retainExisting
                ? existingVisualRevision : exactLocalRevision;
        if (observedPresent && observedVisualRevision > selectedRevision) {
            return Source.OBSERVED;
        }
        return retainExisting ? Source.EXISTING : Source.RECEIPT;
    }
}
