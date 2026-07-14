package com.projectkorra.projectkorra.region;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.flags.Flags;
import me.angeschossen.lands.api.land.Area;
import org.bukkit.plugin.Plugin;

class Lands extends RegionProtectionBase {

    protected LandsIntegration landsIntegration;

    protected Lands() {
        super("Lands");

        this.landsIntegration = LandsIntegration.of(Platform.pluginHandle(Plugin.class));
    }

    @Override
    public boolean isRegionProtectedReal(Player player, Location location, CoreAbility ability, boolean igniteAbility, boolean explosiveAbility) {
        final Area area = this.landsIntegration.getArea(BukkitMC.locationHandle(location));
        final boolean isClaimed = area != null;

        if (isClaimed) {
            if (igniteAbility && !area.hasFlag(player.getUniqueId(), Flags.BLOCK_IGNITE)) return true;
            if (explosiveAbility && !area.hasNaturalFlag(Flags.TNT_GRIEFING)) return true;
            return !area.hasFlag(player.getUniqueId(), Flags.BLOCK_BREAK);
        }

        return false;
    }
}
