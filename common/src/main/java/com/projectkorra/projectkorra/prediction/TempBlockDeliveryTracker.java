package com.projectkorra.projectkorra.prediction;

import java.util.HashSet;
import java.util.Set;

/**
 * Tracks which TempBlock layers were announced to one client. Once CREATE is
 * delivered, its REVERT or DISCARD closure is mandatory even if the player has
 * left view distance.
 */
public final class TempBlockDeliveryTracker {
    private final Set<Long> delivered = new HashSet<>();

    public boolean route(final long layerId, final boolean closesLayer, final boolean inView) {
        final boolean tracked = this.delivered.contains(layerId);
        if (!inView && !tracked) return false;
        if (closesLayer) this.delivered.remove(layerId);
        else this.delivered.add(layerId);
        return true;
    }

    public void markActive(final long layerId) {
        this.delivered.add(layerId);
    }

    public boolean tracks(final long layerId) {
        return this.delivered.contains(layerId);
    }

    /**
     * Retires the delivery ledger when its viewer leaves the physical world.
     * The client discards that world's complete visual ledger at the same
     * boundary, so forwarding its later closes into another world would be both
     * unnecessary and unsafe when the two worlds share a vanilla dimension key.
     */
    public void clear() {
        this.delivered.clear();
    }
}
