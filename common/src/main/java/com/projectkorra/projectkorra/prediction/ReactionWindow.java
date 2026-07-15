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
        return intersects(box, HitGeometry.point(contact), tolerance);
    }

    /**
     * Tests every location of an ability against the defender. The configured
     * tolerance expands the defender only for network uncertainty; the
     * ability's own collision radius is then applied around each stream.
     */
    public static boolean intersects(final BoundingBox box, final HitGeometry geometry,
                                     final double tolerance) {
        if (box == null || geometry == null || geometry.isEmpty()) return false;
        final BoundingBox expanded = box.expand(Math.max(0D, tolerance));
        final double radiusSquared = geometry.radius() * geometry.radius();
        for (final Vector contact : geometry.contacts()) {
            if (!finite(contact)) continue;
            final double dx = distanceToInterval(contact.getX(), expanded.getMinX(), expanded.getMaxX());
            final double dy = distanceToInterval(contact.getY(), expanded.getMinY(), expanded.getMaxY());
            final double dz = distanceToInterval(contact.getZ(), expanded.getMinZ(), expanded.getMaxZ());
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) return true;
        }
        return false;
    }

    private static double distanceToInterval(final double value, final double minimum, final double maximum) {
        if (value < minimum) return minimum - value;
        return value > maximum ? value - maximum : 0D;
    }

    private static boolean finite(final Vector vector) {
        return Double.isFinite(vector.getX()) && Double.isFinite(vector.getY())
                && Double.isFinite(vector.getZ());
    }
}
