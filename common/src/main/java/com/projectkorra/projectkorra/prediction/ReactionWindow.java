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

    /**
     * Returns how much longer a provisional hit must wait so the defender has
     * received both the configured visible reaction budget and their bounded
     * network round trip. Time already spent running visibly on the server is
     * deducted, so sufficiently telegraphed projectiles gain no extra delay.
     */
    public static int visibilityDelayTicks(final long alreadyVisibleTicks,
                                           final int minimumVisibleMillis,
                                           final int defenderRoundTripMillis,
                                           final int maximumCompensationMillis,
                                           final int jitterMillis) {
        final long visibleMillis = Math.max(0L, alreadyVisibleTicks) * TICK_MILLIS;
        final long boundedPing = Math.min(Math.max(0L, defenderRoundTripMillis),
                Math.max(0L, maximumCompensationMillis));
        final long requiredMillis = Math.max(0L, minimumVisibleMillis)
                + boundedPing + Math.max(0L, jitterMillis);
        final long missingMillis = Math.max(0L, requiredMillis - visibleMillis);
        final long ticks = (missingMillis + TICK_MILLIS - 1L) / TICK_MILLIS;
        return (int) Math.min(Integer.MAX_VALUE, ticks);
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
