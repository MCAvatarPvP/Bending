package com.projectkorra.projectkorra.region;

import com.massivecraft.factions.*;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

class FactionsUUID extends RegionProtectionBase {

    protected FactionsUUID() {
        super("Factions");
    }

    @Override
    public boolean isRegionProtectedReal(Player player, Location location, CoreAbility ability, boolean igniteAbility, boolean explosiveAbility) {
        final FPlayer fPlayer = FPlayers.getInstance().getByPlayer((org.bukkit.entity.Player) player.handle());
        FLocation fLoc = new FLocation(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        final Faction faction = Board.getInstance().getFactionAt(fLoc);

        final Object relation;
        try {
            relation = faction.getClass().getMethod("getRelationTo", Faction.class).invoke(faction, fPlayer.getFaction());
        } catch (ReflectiveOperationException exception) {
            return true;
        }

        if (!(faction.isWilderness() || fPlayer.getFaction().equals(faction) || "ALLY".equals(String.valueOf(relation)))) {
            return true;
        }
        return false;
    }
}
