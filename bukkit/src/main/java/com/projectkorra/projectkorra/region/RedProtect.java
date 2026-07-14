package com.projectkorra.projectkorra.region;

import br.net.fabiozumbi12.RedProtect.Bukkit.API.RedProtectAPI;
import br.net.fabiozumbi12.RedProtect.Bukkit.Region;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

class RedProtect extends RegionProtectionBase {

    protected RedProtect() {
        super("RedProtect");
    }

    @Override
    public boolean isRegionProtectedReal(Player player, Location location, CoreAbility ability, boolean igniteAbility, boolean explosiveAbility) {
        final RedProtectAPI api = br.net.fabiozumbi12.RedProtect.Bukkit.RedProtect.get().getAPI();
        final Region region = api.getRegion(BukkitMC.locationHandle(location));
        if (region != null) {
            if (!region.canBuild((org.bukkit.entity.Player) player.handle())) return true;
            return !region.canFire() && (igniteAbility || explosiveAbility);
        }

        return false;
    }
}
