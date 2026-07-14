package com.projectkorra.projectkorra.region;

import com.projectkorra.projectkorra.platform.Platform;

/**
 * Registers Bukkit-only protection integrations without leaking them into common.
 */
public final class BukkitRegionProtectionBootstrap {
    private BukkitRegionProtectionBootstrap() {
    }

    public static void registerBuiltIns() {
        if (enabled("WorldGuard")) new WorldGuard();
        if (enabled("Factions")) {
            Object raw = Platform.plugins().getPlugin("Factions");
            String website = null;
            try {
                Object description = raw.getClass().getMethod("getDescription").invoke(raw);
                website = String.valueOf(description.getClass().getMethod("getWebsite").invoke(description));
            } catch (Throwable ignored) {
            }
            if (website != null && website.toLowerCase().contains("factionsuuid")) {
                new FactionsUUID();
            } else {
                new SaberFactions();
            }
        }
        if (enabled("LWC")) new LWC();
        if (enabled("Towny")) new Towny();
        if (enabled("RedProtect")) new RedProtect();
        if (enabled("GriefDefender")) new GriefDefender();
        if (enabled("GriefPrevention")) new GriefPrevention();
        if (enabled("Residence")) new Residence();
        if (enabled("Lands")) new Lands();
    }

    private static boolean enabled(final String plugin) {
        return Platform.plugins().isPluginEnabled(plugin);
    }
}
