package com.projectkorra.projectkorra.prediction;

import java.util.HashSet;
import java.util.Set;

/**
 * Tracks which TempBlock layers were announced to one client. Once CREATE is
 * delivered, REVERT is mandatory even if the player has left view distance.
 */
public final class TempBlockDeliveryTracker {
    private final Set<Long> delivered = new HashSet<>();

    public boolean route(final long layerId, final boolean revert, final boolean inView) {
        final boolean tracked = this.delivered.contains(layerId);
        if (!inView && !tracked) return false;
        if (revert) this.delivered.remove(layerId);
        else this.delivered.add(layerId);
        return true;
    }

    public void markActive(final long layerId) {
        this.delivered.add(layerId);
    }

    public boolean tracks(final long layerId) {
        return this.delivered.contains(layerId);
    }
}
