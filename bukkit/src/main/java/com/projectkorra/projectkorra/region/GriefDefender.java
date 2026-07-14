package com.projectkorra.projectkorra.region;

import com.griefdefender.api.User;
import com.griefdefender.api.claim.Claim;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

class GriefDefender extends RegionProtectionBase {

    protected GriefDefender() {
        super("GriefDefender");
    }

    @Override
    public boolean isRegionProtectedReal(Player player, Location location, CoreAbility ability, boolean igniteAbility, boolean explosiveAbility) {
        final Claim claim = com.griefdefender.api.GriefDefender.getCore().getClaimAt(location);
        if (claim != null) {
            final User user = com.griefdefender.api.GriefDefender.getCore().getUser(player.getUniqueId());

            return !claim.canBreak(player, location, user);
        }

        return false;
    }
}
