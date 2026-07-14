package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

/** Pure timing and geometry rules shared by the Paper and Fabric endpoints. */
public final class ReactionWindow {
    public static final long TICK_MILLIS = 50L;

    private ReactionWindow() {
    }

    public static int compensationTicks(final int roundTripMillis, final int maximumMillis,
                                        final int jitterMillis) {
        final long boundedPing = Math.min(Math.max(0L, roundTripMillis), Math.max(0L, maximumMillis));
        final long total = boundedPing + Math.max(0L, jitterMillis);
        return (int) Math.max(1L, (total + TICK_MILLIS - 1L) / TICK_MILLIS);
    }

    public static boolean containsContact(final BoundingBox box, final Vector contact,
                                          final double tolerance) {
        if (box == null || contact == null || !finite(contact)) return false;
        final BoundingBox expanded = box.expand(Math.max(0D, tolerance));
        return contact.getX() >= expanded.getMinX() && contact.getX() <= expanded.getMaxX()
                && contact.getY() >= expanded.getMinY() && contact.getY() <= expanded.getMaxY()
                && contact.getZ() >= expanded.getMinZ() && contact.getZ() <= expanded.getMaxZ();
    }

    private static boolean finite(final Vector vector) {
        return Double.isFinite(vector.getX()) && Double.isFinite(vector.getY())
                && Double.isFinite(vector.getZ());
    }
}
