package com.projectkorra.projectkorra.region;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.hooks.RegionProtectionHook;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class RegionProtectionBase implements RegionProtectionHook {

    private String plugin;
    private String path;
    private String cachedPlugin;

    public RegionProtectionBase(String plugin) {
        this(plugin, "Respect" + plugin);
    }

    public RegionProtectionBase(String plugin, String path) {
        this.plugin = plugin;
        this.path = path;

        if (Platform.plugins().isPluginEnabled(plugin) && ConfigManager.defaultConfig.get().getBoolean("Properties.RegionProtection." + path)) {
            this.cachedPlugin = plugin;
            RegionProtection.registerRegionProtection(plugin, this);
        }
    }

    @Override
    public final boolean isRegionProtected(@NotNull Player player, @NotNull Location location, @Nullable CoreAbility ability) {
        if (ConfigManager.defaultConfig.get().getBoolean("Properties.RegionProtection." + path)) {

            final boolean allowHarmless = ConfigManager.defaultConfig.get().getBoolean("Properties.RegionProtection.AllowHarmlessAbilities");

            boolean isIgnite = false;
            boolean isExplosive = false;
            boolean isHarmless = false;

            if (ability != null) {
                isIgnite = ability.isIgniteAbility();
                isExplosive = ability.isExplosiveAbility();
                isHarmless = ability.isHarmlessAbility();
            }

            if ((ability == null || isHarmless) && allowHarmless) {
                return false;
            }
            return isRegionProtectedReal(player, location, ability, isIgnite, isExplosive);
        }
        return false;
    }

    public abstract boolean isRegionProtectedReal(Player player, Location location, CoreAbility ability, boolean igniteAbility, boolean explosiveAbility);
}
