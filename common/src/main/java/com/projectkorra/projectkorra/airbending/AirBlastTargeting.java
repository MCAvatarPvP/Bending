package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

/** Pure geometry used to keep the pre-split AirBlast ray within its range. */
final class AirBlastTargeting {
    private static final double RANGE_MARGIN = 0.1;

    private AirBlastTargeting() {
    }

    static Location clampToRange(final Location origin, final Location target, final double range) {
        if (origin == null || target == null || origin.getWorld() == null || target.getWorld() == null
                || !origin.getWorld().equals(target.getWorld())) {
            return target;
        }

        final double maximumRange = Math.max(0.0, range);
        final Vector offset = target.toVector().subtract(origin.toVector());
        final double distanceSquared = offset.lengthSquared();
        if (!Double.isFinite(distanceSquared) || distanceSquared <= maximumRange * maximumRange) {
            return target;
        }

        if (distanceSquared == 0.0) {
            return origin.clone();
        }
        return origin.clone().add(offset.normalize().multiply(Math.max(0.0, maximumRange - RANGE_MARGIN)));
    }
}
