package com.projectkorra.projectkorra.region;

import com.griefcraft.model.Protection;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import org.bukkit.block.Block;

class LWC extends RegionProtectionBase {

    protected LWC() {
        super("LWC");
    }

    @Override
    public boolean isRegionProtectedReal(Player player, Location location, CoreAbility ability, boolean igniteAbility, boolean explosiveAbility) {
        final com.griefcraft.lwc.LWC lwc2 = com.griefcraft.lwc.LWC.getInstance();
        final Protection protection = lwc2.getProtectionCache().getProtection((Block) location.getBlock().handle());
        if (protection != null) {
            if (!lwc2.canAccessProtection((org.bukkit.entity.Player) player.handle(), protection)) {
                return true;
            }
        }
        return false;
    }
}
