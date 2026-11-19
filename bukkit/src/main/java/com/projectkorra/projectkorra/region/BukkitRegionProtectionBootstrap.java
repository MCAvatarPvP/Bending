package com.projectkorra.projectkorra.region;

import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority;

import java.util.List;

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
            String website = raw instanceof org.bukkit.plugin.Plugin plugin ? plugin.getDescription().getWebsite() : null;
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

    public static boolean supportsRollback() {
        var policies = RegionProtection.getActiveProtections();
        return policies.isEmpty() || policies.size() == 1 && policies.get("WorldGuard") instanceof WorldGuard;
    }

    public static com.projectkorra.projectkorra.prediction.rollback.RollbackRegionPolicy captureRollback(
            java.util.Collection<Player> players, com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds bounds) {
        var policies = RegionProtection.getActiveProtections();
        if (!supportsRollback()) throw new IllegalStateException("Duel region hooks changed before capture");
        var rows = new java.util.TreeMap<java.util.UUID, List<RegionProtectionAuthority.Box>>();
        for (var player : players) rows.put(player.getUniqueId(), policies.isEmpty() ? List.of()
                : WorldGuard.duelBoxes(player, bounds, (WorldGuard) policies.get("WorldGuard")));
        return new com.projectkorra.projectkorra.prediction.rollback.RollbackRegionPolicy(rows);
    }

    public static List<RegionProtectionAuthority.Box> predictionBoxes(
            final Player player, final List<String> abilities, final int radius) {
        if (!enabled("WorldGuard")) return List.of();
        try {
            return WorldGuard.predictionBoxes(player, abilities, radius);
        } catch (LinkageError | RuntimeException ignored) {
            return List.of();
        }
    }

    private static boolean enabled(final String plugin) {
        return Platform.plugins().isPluginEnabled(plugin);
    }
}
