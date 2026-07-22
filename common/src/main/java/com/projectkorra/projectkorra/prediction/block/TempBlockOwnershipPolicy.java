package com.projectkorra.projectkorra.prediction.block;

import java.util.List;
import java.util.UUID;

/** Pure ownership rules shared by the TempBlock registry and its tests. */
public final class TempBlockOwnershipPolicy {
    private TempBlockOwnershipPolicy() {
    }

    public static boolean topLayerOwnedBy(final List<UUID> owners, final UUID viewer) {
        return viewer != null && owners != null && !owners.isEmpty()
                && viewer.equals(owners.get(owners.size() - 1));
    }

    /** Returns the highest layer visible to the viewer, or -1 for original state. */
    public static int visibleLayerIndex(final List<UUID> owners, final UUID viewer) {
        if (owners == null || owners.isEmpty()) return -1;
        for (int index = owners.size() - 1; index >= 0; index--) {
            if (viewer == null || !viewer.equals(owners.get(index))) return index;
        }
        return -1;
    }

    public static boolean clientDisplaysLayer(final boolean ownedByViewer,
                                              final boolean locallyPredicted) {
        return !ownedByViewer || locallyPredicted;
    }
}
