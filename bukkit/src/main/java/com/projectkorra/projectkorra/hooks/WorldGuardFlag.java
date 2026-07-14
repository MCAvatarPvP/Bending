package com.projectkorra.projectkorra.hooks;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

public final class WorldGuardFlag {
    private WorldGuardFlag() {
    }

    public static void registerBendingWorldGuardFlag(final Plugin plugin) {
        final FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();

        try {
            if (registry.get("bending") != null) {
                plugin.getLogger().warning("Unable to register the WorldGuard bending flag because that name is already in use");
                return;
            }
            registry.register(new StateFlag("bending", false));
        } catch (final Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Unable to register the WorldGuard bending flag", e);
        }
    }
}
