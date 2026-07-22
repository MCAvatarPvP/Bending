package com.projectkorra.projectkorra.prediction.movement;

/** Exact validation rules for velocity-ownership metadata. */
public final class VelocityReceiptPolicy {
    private VelocityReceiptPolicy() {
    }

    public static boolean accepts(final boolean locallyOwned, final long actionSequence,
                                  final int impulseOrdinal, final int targetEntityId) {
        return (!locallyOwned || actionSequence > 0L)
                && impulseOrdinal > 0 && targetEntityId >= 0;
    }
}
