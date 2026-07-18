package com.projectkorra.projectkorra.region;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.prediction.RegionProtectionAuthority;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.session.SessionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

class WorldGuard extends RegionProtectionBase {
    private static final int MAX_PREDICTION_CELLS = 512;

    protected WorldGuard() {
        super("WorldGuard");
    }

    @Override
    public boolean isRegionProtectedReal(Player player, com.projectkorra.projectkorra.platform.mc.Location reallocation, CoreAbility ability, boolean igniteAbility, boolean explosiveAbility) {
        World world = reallocation.getWorld();

        final com.sk89q.worldguard.WorldGuard wg = com.sk89q.worldguard.WorldGuard.getInstance();
        final Location location = BukkitAdapter.adapt(BukkitMC.locationHandle(reallocation));
        final LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer((org.bukkit.entity.Player) player.handle());
        final SessionManager sessionManager = wg.getPlatform().getSessionManager();

        if (sessionManager.hasBypass(localPlayer, localPlayer.getWorld())) {
            return false;
        }

        if (igniteAbility) {
            if (!player.hasPermission("worldguard.override.lighter")) {
                if (wg.getPlatform().getGlobalStateManager().get(BukkitAdapter.adapt((org.bukkit.World) world.handle())).blockLighter) {
                    return true;
                }
            }
        }

        if (explosiveAbility) {
            if (wg.getPlatform().getGlobalStateManager().get(BukkitAdapter.adapt((org.bukkit.World) world.handle())).blockTNTExplosions) {
                return true;
            }
            final StateFlag.State tntflag = wg.getPlatform().getRegionContainer().createQuery().queryState(location, localPlayer, Flags.TNT);
            if (tntflag != null && tntflag.equals(StateFlag.State.DENY)) {
                return true;
            }
        }
        final Flag<?> flag = wg.getFlagRegistry().get("bending");
        if (flag instanceof StateFlag bendingflag) {
            final StateFlag.State bendingflagstate = wg.getPlatform().getRegionContainer().createQuery().queryState(location, localPlayer, bendingflag);
            if (bendingflagstate == null && !wg.getPlatform().getRegionContainer().createQuery().testState(location, localPlayer, Flags.BUILD)) {
                return true;
            }
            if (bendingflagstate != null && bendingflagstate.equals(StateFlag.State.DENY)) {
                return true;
            }
        } else {
            if (!wg.getPlatform().getRegionContainer().createQuery().testState(location, localPlayer, Flags.BUILD)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Partitions nearby cuboid regions at every inclusive block boundary, then
     * asks this adapter for the real effective decision in each constant cell.
     * Polygonal regions are deliberately left to exact current-block state;
     * their bounding boxes would over-protect space outside the polygon.
     */
    static List<RegionProtectionAuthority.Box> predictionBoxes(
            final Player player, final List<String> abilities, final int radius) {
        if (player == null || player.getWorld() == null || abilities == null || abilities.isEmpty()) {
            return List.of();
        }
        final Object registered = RegionProtection.getActiveProtections().get("WorldGuard");
        if (!(registered instanceof WorldGuard hook)) return List.of();
        final org.bukkit.World bukkitWorld = (org.bukkit.World) player.getWorld().handle();
        final RegionManager manager = com.sk89q.worldguard.WorldGuard.getInstance().getPlatform()
                .getRegionContainer().get(BukkitAdapter.adapt(bukkitWorld));
        if (manager == null) return List.of();

        final int centerX = player.getLocation().getBlockX();
        final int centerZ = player.getLocation().getBlockZ();
        final int minX = centerX - radius, maxXExclusive = centerX + radius + 1;
        final int minZ = centerZ - radius, maxZExclusive = centerZ + radius + 1;
        final int minY = player.getWorld().getMinHeight();
        final int maxYExclusive = player.getWorld().getMaxHeight();
        final TreeSet<Integer> xs = boundaries(minX, maxXExclusive);
        final TreeSet<Integer> ys = boundaries(minY, maxYExclusive);
        final TreeSet<Integer> zs = boundaries(minZ, maxZExclusive);

        for (ProtectedRegion region : manager.getRegions().values()) {
            if (!region.isPhysicalArea()) continue;
            final BlockVector3 minimum = region.getMinimumPoint();
            final BlockVector3 maximum = region.getMaximumPoint();
            if (maximum.x() < minX || minimum.x() >= maxXExclusive
                    || maximum.z() < minZ || minimum.z() >= maxZExclusive
                    || maximum.y() < minY || minimum.y() >= maxYExclusive) continue;
            if (!(region instanceof ProtectedCuboidRegion)) return List.of();
            addBoundary(xs, minimum.x(), minX, maxXExclusive);
            addBoundary(xs, (long) maximum.x() + 1L, minX, maxXExclusive);
            addBoundary(ys, minimum.y(), minY, maxYExclusive);
            addBoundary(ys, (long) maximum.y() + 1L, minY, maxYExclusive);
            addBoundary(zs, minimum.z(), minZ, maxZExclusive);
            addBoundary(zs, (long) maximum.z() + 1L, minZ, maxZExclusive);
        }

        final List<Integer> x = List.copyOf(xs), y = List.copyOf(ys), z = List.copyOf(zs);
        final long cells = (long) (x.size() - 1) * (y.size() - 1) * (z.size() - 1);
        if (cells <= 0 || cells > MAX_PREDICTION_CELLS) return List.of();
        final List<RegionProtectionAuthority.Box> boxes = new ArrayList<>();
        for (int xi = 0; xi + 1 < x.size(); xi++) {
            for (int yi = 0; yi + 1 < y.size(); yi++) {
                for (int zi = 0; zi + 1 < z.size(); zi++) {
                    final int cellX = x.get(xi), cellY = y.get(yi), cellZ = z.get(zi);
                    final com.projectkorra.projectkorra.platform.mc.Location sample =
                            new com.projectkorra.projectkorra.platform.mc.Location(
                                    player.getWorld(), cellX + 0.5, cellY + 0.5, cellZ + 0.5);
                    long mask = 0L;
                    for (int abilityIndex = 0; abilityIndex < abilities.size() && abilityIndex < 63; abilityIndex++) {
                        final String abilityName = abilities.get(abilityIndex);
                        final int policy = RegionProtectionAuthority.policyCode(abilityName);
                        if (policy >= 0) {
                            if (hook.isRegionProtected(player, sample,
                                    (policy & 1) != 0, (policy & 2) != 0, (policy & 4) != 0)) {
                                mask |= 1L << abilityIndex;
                            }
                            continue;
                        }
                        final CoreAbility ability = abilityName == null || abilityName.isBlank()
                                ? null : CoreAbility.getAbility(abilityName);
                        if (abilityName != null && !abilityName.isBlank() && ability == null) continue;
                        if (hook.isRegionProtected(player, sample, ability)) mask |= 1L << abilityIndex;
                    }
                    if (mask != 0L) {
                        boxes.add(new RegionProtectionAuthority.Box(mask, cellX, cellY, cellZ,
                                x.get(xi + 1) - 1, y.get(yi + 1) - 1, z.get(zi + 1) - 1));
                    }
                }
            }
        }
        return List.copyOf(boxes);
    }

    private boolean isRegionProtected(final Player player,
                                        final com.projectkorra.projectkorra.platform.mc.Location location,
                                        final boolean harmless, final boolean ignite, final boolean explosive) {
        if (harmless && ConfigManager.defaultConfig.get().getBoolean(
                "Properties.RegionProtection.AllowHarmlessAbilities")) return false;
        return isRegionProtectedReal(player, location, null, ignite, explosive);
    }

    private static TreeSet<Integer> boundaries(final int minimum, final int maximumExclusive) {
        final TreeSet<Integer> result = new TreeSet<>();
        result.add(minimum);
        result.add(maximumExclusive);
        return result;
    }

    private static void addBoundary(final TreeSet<Integer> boundaries, final long value,
                                    final int minimum, final int maximumExclusive) {
        boundaries.add((int) Math.max(minimum, Math.min((long) maximumExclusive, value)));
    }
}
