package com.projectkorra.projectkorra.region;

import com.massivecraft.factions.*;
import com.massivecraft.factions.struct.Relation;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

class SaberFactions extends RegionProtectionBase {

    protected SaberFactions() {
        super("Factions");
    }

    @Override
    public boolean isRegionProtectedReal(Player player, Location location, CoreAbility ability, boolean igniteAbility, boolean explosiveAbility) {
        final FPlayer fPlayer = FPlayers.getInstance().getByPlayer((org.bukkit.entity.Player) player.handle());
        FLocation fLoc = new FLocation(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        final Faction faction = Board.getInstance().getFactionAt(fLoc);
        final Relation relation = fPlayer.getRelationTo(faction);

        if (!(faction.isWilderness() || fPlayer.getFaction().equals(faction) || relation == Relation.ALLY)) {
            return true;
        }
        return false;
    }
}
