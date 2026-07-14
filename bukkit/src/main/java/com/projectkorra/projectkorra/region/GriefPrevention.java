package com.projectkorra.projectkorra.region;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

class GriefPrevention extends RegionProtectionBase {

    protected GriefPrevention() {
        super("GriefPrevention");
    }

    @Override
    public boolean isRegionProtectedReal(Player player, Location location, CoreAbility ability, boolean igniteAbility, boolean explosiveAbility) {
        final String reason = me.ryanhamshire.GriefPrevention.GriefPrevention.instance.allowBuild((org.bukkit.entity.Player) player.handle(), BukkitMC.locationHandle(location));

        //final Claim claim = me.ryanhamshire.GriefPrevention.GriefPrevention.instance.dataStore.getClaimAt(location, true, null);

        if (reason != null) {
            return true;
        }

        return false;
    }
}
