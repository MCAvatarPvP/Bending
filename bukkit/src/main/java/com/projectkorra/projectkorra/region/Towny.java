package com.projectkorra.projectkorra.region;

import com.palmergames.bukkit.towny.object.TownyPermission;
import com.palmergames.bukkit.towny.utils.PlayerCacheUtil;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

class Towny extends RegionProtectionBase {

    protected Towny() {
        super("Towny");
    }

    @Override
    public boolean isRegionProtectedReal(Player player, Location location, CoreAbility ability, boolean igniteAbility, boolean explosiveAbility) {
        if (!PlayerCacheUtil.getCachePermission((org.bukkit.entity.Player) player.handle(), BukkitMC.locationHandle(location), org.bukkit.Material.DIRT, TownyPermission.ActionType.BUILD)) {
            return true;
        }

        return false;
    }
}
