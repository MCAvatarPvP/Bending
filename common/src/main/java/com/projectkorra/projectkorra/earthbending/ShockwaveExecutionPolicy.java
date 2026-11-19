package com.projectkorra.projectkorra.earthbending;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Small, dependency-free policies for keeping Shockwave's radial work bounded. */
public final class ShockwaveExecutionPolicy {
    private ShockwaveExecutionPolicy() {
    }

    static int directionCount(final double range) {
        if (!Double.isFinite(range) || range <= 0.0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(2.0 * Math.PI * range));
    }

    public static final class MovedColumns {
        private final Map<String, Set<Long>> worlds = new HashMap<>();

        public boolean mark(final String world, final int x, final int z) {
            return worlds.computeIfAbsent(world, unused -> new HashSet<>()).add(key(x, z));
        }

        public boolean contains(final String world, final int x, final int z) {
            var columns = worlds.get(world);
            return columns != null && columns.contains(key(x, z));
        }

        public void remove(final String world, final int x, final int z) {
            var columns = worlds.get(world);
            if (columns != null && columns.remove(key(x, z)) && columns.isEmpty()) worlds.remove(world);
        }

        public void clear() { worlds.clear(); }

        private static long key(int x, int z) { return (long) x << 32 | Integer.toUnsignedLong(z); }
    }
}
