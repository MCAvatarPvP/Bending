package com.projectkorra.projectkorra.earthbending;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Small, dependency-free policies for keeping Shockwave's radial work bounded. */
final class ShockwaveExecutionPolicy {
    private ShockwaveExecutionPolicy() {
    }

    static int directionCount(final double range) {
        if (!Double.isFinite(range) || range <= 0.0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(2.0 * Math.PI * range));
    }

    static final class MovedColumns {
        private final Set<ColumnKey> columns = ConcurrentHashMap.newKeySet();

        boolean mark(final String world, final int x, final int z) {
            return this.columns.add(new ColumnKey(world, x, z));
        }

        boolean contains(final String world, final int x, final int z) {
            return this.columns.contains(new ColumnKey(world, x, z));
        }

        void clear() {
            this.columns.clear();
        }
    }

    private record ColumnKey(String world, int x, int z) {
    }
}
