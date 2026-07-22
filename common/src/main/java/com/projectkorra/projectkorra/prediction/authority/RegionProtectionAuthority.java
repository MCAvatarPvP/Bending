package com.projectkorra.projectkorra.prediction.authority;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.hooks.RegionProtectionHook;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.region.RegionProtection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable server-evaluated region decisions used only by the predicting
 * owner. The normal server RegionProtection hooks remain gameplay authority.
 */
public final class RegionProtectionAuthority {
    private static final String HOOK = "ProjectKorraPredictionAuthority";
    private static final String POLICY_PREFIX = "@policy:";
    private static volatile Snapshot snapshot = Snapshot.empty();
    private static final RegionProtectionHook AUTHORITY_HOOK =
            (player, location, ability) -> snapshot.isProtected(location, ability);

    private RegionProtectionAuthority() {
    }

    public static void install(final Player player, final Snapshot next) {
        snapshot = next == null ? Snapshot.empty() : next;
        RegionProtection.registerRegionProtection(HOOK, AUTHORITY_HOOK);
        RegionProtection.clearCache(player);
    }

    public static void clear(final Player player) {
        snapshot = Snapshot.empty();
        RegionProtection.unloadPlugin(HOOK);
        RegionProtection.clearCache(player);
    }

    /** Builds exact one-block decisions for integrations without spatial exports. */
    public static Snapshot currentPoint(final Player player, final String world,
                                        final Collection<String> abilityNames) {
        if (player == null || player.getLocation() == null) return Snapshot.empty();
        final List<String> abilities = normalizedAbilities(abilityNames);
        long mask = 0L;
        for (int index = 0; index < abilities.size(); index++) {
            final String name = abilities.get(index);
            final CoreAbility ability = name.isEmpty() ? null : CoreAbility.getAbility(name);
            if (!name.isEmpty() && ability == null) continue;
            if (RegionProtection.isRegionProtectedUncached(player, player.getLocation(), ability)) {
                mask |= 1L << index;
            }
        }
        final Location location = player.getLocation();
        final List<Box> boxes = mask == 0L ? List.of() : List.of(new Box(mask,
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        return new Snapshot(world, abilities, boxes);
    }

    public static List<String> normalizedAbilities(final Collection<String> names) {
        final LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add(""); // null-ability RegionProtection queries use bit zero.
        // WorldGuard's decision depends on these three public ability traits,
        // so all locally registered abilities (including a newly-created
        // combo) can use an exact spatial decision without listing 229 names.
        for (int policy = 0; policy < 8; policy++) result.add(POLICY_PREFIX + policy);
        if (names != null) {
            names.stream().filter(name -> name != null && !name.isBlank())
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .sorted().forEach(result::add);
        }
        if (result.size() > 63) {
            return List.copyOf(new ArrayList<>(result).subList(0, 63));
        }
        return List.copyOf(result);
    }

    public record Snapshot(String world, List<String> abilities, List<Box> boxes) {
        public Snapshot {
            world = world == null ? "" : world;
            abilities = normalizedAbilities(abilities);
            final long validMask = abilities.size() == 63
                    ? Long.MAX_VALUE : (1L << abilities.size()) - 1L;
            final Map<Coordinates, Long> merged = new LinkedHashMap<>();
            if (boxes != null) {
                for (Box box : boxes) {
                    if (box == null) continue;
                    final long masked = box.abilityMask() & validMask;
                    if (masked == 0L) continue;
                    final Coordinates key = new Coordinates(box.minX(), box.minY(), box.minZ(),
                            box.maxX(), box.maxY(), box.maxZ());
                    merged.merge(key, masked, (left, right) -> left | right);
                }
            }
            final List<Box> normalized = new ArrayList<>(merged.size());
            merged.forEach((key, mask) -> normalized.add(new Box(mask,
                    key.minX, key.minY, key.minZ, key.maxX, key.maxY, key.maxZ)));
            boxes = List.copyOf(normalized);
        }

        public static Snapshot empty() {
            return new Snapshot("", List.of(""), List.of());
        }

        public boolean isProtected(final Location location, final CoreAbility ability) {
            if (location == null || location.getWorld() == null || boxes.isEmpty()) return false;
            if (!sameWorld(world, location.getWorld().getName())) return false;
            final String name = ability == null || ability.getName() == null
                    ? "" : ability.getName().toLowerCase(Locale.ROOT);
            long bits = bitFor(abilities, name);
            if (ability != null) bits |= bitFor(abilities, policyName(ability));
            if (bits == 0L) return false;
            final int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
            for (Box box : boxes) {
                if ((box.abilityMask() & bits) != 0L && box.contains(x, y, z)) return true;
            }
            return false;
        }

        private static long bitFor(final List<String> abilities, final String name) {
            final int index = abilities.indexOf(name);
            return index < 0 || index >= 63 ? 0L : 1L << index;
        }

        private static boolean sameWorld(final String expected, final String actual) {
            if (expected == null || actual == null) return false;
            if (expected.equalsIgnoreCase(actual)) return true;
            final int separator = expected.indexOf(':');
            return separator >= 0 && expected.substring(separator + 1).equalsIgnoreCase(actual);
        }
    }

    public static String policyName(final CoreAbility ability) {
        if (ability == null) return "";
        int policy = ability.isHarmlessAbility() ? 1 : 0;
        if (ability.isIgniteAbility()) policy |= 2;
        if (ability.isExplosiveAbility()) policy |= 4;
        return POLICY_PREFIX + policy;
    }

    public static int policyCode(final String name) {
        if (name == null || !name.startsWith(POLICY_PREFIX)) return -1;
        try {
            final int value = Integer.parseInt(name.substring(POLICY_PREFIX.length()));
            return value >= 0 && value < 8 ? value : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** Inclusive block-coordinate AABB with one bit per Snapshot ability. */
    public record Box(long abilityMask, int minX, int minY, int minZ,
                      int maxX, int maxY, int maxZ) {
        public Box {
            if (minX > maxX) { int swap = minX; minX = maxX; maxX = swap; }
            if (minY > maxY) { int swap = minY; minY = maxY; maxY = swap; }
            if (minZ > maxZ) { int swap = minZ; minZ = maxZ; maxZ = swap; }
        }

        public boolean contains(final int x, final int y, final int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }
    }

    private record Coordinates(int minX, int minY, int minZ,
                               int maxX, int maxY, int maxZ) {
    }
}
