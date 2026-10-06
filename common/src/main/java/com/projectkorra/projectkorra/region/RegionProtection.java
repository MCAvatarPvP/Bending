package com.projectkorra.projectkorra.region;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.hooks.RegionProtectionHook;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

public class RegionProtection {

    /**
     * Cached region protection
     */
    private static volatile RegionProtectionCache BLOCK_CACHE = new RegionProtectionCache(5000);
    /**
     * Registered region protections
     */
    private static Map<String, RegionProtectionHook> PROTECTIONS = new LinkedHashMap<>(); //LinkedHashMap keeps the hashmap order of insertion

    public RegionProtection() {
        // Platform-specific protection hooks are registered by the active platform module.
    }

    /**
     * Register a new type of region protection to respect with bending.
     *
     * @param plugin The plugin the region protection belongs to
     * @param hook   The region protection hook
     */
    public static void registerRegionProtection(@NotNull String pluginName, @NotNull RegionProtectionHook hook) {
        PROTECTIONS.put(pluginName, hook);
        clearCache();
    }

    public static void registerRegionProtection(@NotNull Object plugin, @NotNull RegionProtectionHook hook) {
        registerRegionProtection(Platform.plugins().pluginName(plugin), hook);
    }

    /**
     * Removes region protection for the unloaded plugin.
     * To be called by PK's Listener when a plugin unloads
     *
     * @param plugin The plugin
     */
    public static void unloadPlugin(Object plugin) {
        unloadPlugin(Platform.plugins().pluginName(plugin));
    }

    public static void unloadPlugin(String pluginName) {
        PROTECTIONS.remove(pluginName);
        clearCache();
    }

    /**
     * Get a list of currently active custom region protections
     *
     * @return Enabled region protections
     */
    public static Map<String, RegionProtectionHook> getActiveProtections() {
        return PROTECTIONS;
    }

    /**
     * Checks if a location is protected by region protection plugins. Abilities that damage terrain
     * will not damage the terrain (or progress) if this method returns true
     *
     * @param player   The player being checked
     * @param location The location to check
     * @param ability  The ability to check
     * @return True if the region is protected by other plugins
     */
    public static boolean isRegionProtected(@NotNull Player player, @Nullable Location location, @Nullable CoreAbility ability) {
        final Location origin = player.getLocation();
        // Synthetic or not-yet-attached locations have no stable world key.
        if (origin.getWorld() == null || (location != null && location.getWorld() == null)) {
            return isRegionProtectedCached(player, location, ability);
        }
        final RegionProtectionCache cache = BLOCK_CACHE;
        final RegionProtectionCache.Key key = RegionProtectionCache.Key.of(location, origin);
        final Boolean cached = cache.get(player.getUniqueId(), key, ability);
        if (cached != null) return cached;

        final boolean value = (location != null && checkAll(player, location, ability))
                || checkAll(player, origin, ability);
        cache.put(player.getUniqueId(), key, ability, value);
        return value;
    }

    /**
     * Checks if a location is protected by region protection plugins. Abilities that damage terrain
     * will not damage the terrain (or progress) if this method returns true
     *
     * @param player   The player being checked
     * @param location The location to check
     * @param ability  The ability to check
     * @return True if the region is protected by other plugins
     */
    public static boolean isRegionProtected(@NotNull Player player, @Nullable Location location, @Nullable String ability) {
        return isRegionProtected(player, location, CoreAbility.getAbility(ability));
    }

    /**
     * Checks if a location is protected by region protection plugins. Abilities that damage terrain
     * will not damage the terrain (or progress) if this method returns true
     *
     * @param player   The player being checked
     * @param location The location to check
     * @return True if the region is protected by other plugins
     */
    public static boolean isRegionProtected(@NotNull Player player, @Nullable Location location) {
        return isRegionProtected(player, location, (CoreAbility) null);
    }

    /**
     * Checks if a location is protected by region protection plugins. Abilities that damage terrain
     * will not damage the terrain (or progress) if this method returns true
     *
     * @param ability  The ability being checked
     * @param location The location to check
     * @return True if the region is protected by other plugins
     */
    public static boolean isRegionProtected(@NotNull CoreAbility ability, @Nullable Location location) {
        return isRegionProtected(ability.getPlayer(), location, ability);
    }


    /**
     * Checks if a location is protected by region protection plugins. Abilities that damage terrain
     * will not damage the terrain (or progress) if this method returns true
     *
     * @param player  The player being checked
     * @param ability The ability to check
     * @return True if the region is protected by other plugins
     */
    public static boolean isRegionProtected(@NotNull Player player, @Nullable CoreAbility ability) {
        return isRegionProtected(player, null, ability);
    }

    protected static boolean isRegionProtectedCached(Player player, Location location, CoreAbility ability) {
        if (location != null && checkAll(player, location, ability)) return true;

        return checkAll(player, player.getLocation(), ability);
    }

    /** Main-thread query used to build bounded prediction snapshots without polluting the gameplay cache. */
    public static boolean isRegionProtectedUncached(@NotNull Player player, @Nullable Location location,
                                                      @Nullable CoreAbility ability) {
        return isRegionProtectedCached(player, location, ability);
    }

    /** Invalidates cached decisions after an authoritative client snapshot changes. */
    public static void clearCache(@Nullable Player player) {
        if (player != null) BLOCK_CACHE.clear(player.getUniqueId());
    }

    /** Clears decisions that may reference pre-reload ability instances or hook configuration. */
    public static void clearCache() {
        BLOCK_CACHE.clear();
    }

    private static boolean checkAll(Player player, Location location, CoreAbility ability) {
        for (RegionProtectionHook protection : RegionProtection.getActiveProtections().values()) {
            try {
                if (protection.isRegionProtected(player, location, ability)) {
                    return true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    /**
     * Configures the cache lifetime in milliseconds. Kept for API compatibility;
     * expiration and bounded maintenance now happen on access without a full-map timer sweep.
     */
    public static void startCleanCacheTask(double period) {
        BLOCK_CACHE = new RegionProtectionCache(Double.isFinite(period) ? Math.max(0, (long) period) : 5000);
    }
}
