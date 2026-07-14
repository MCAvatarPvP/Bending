package com.projectkorra.projectkorra.region;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.session.SessionManager;

class WorldGuard extends RegionProtectionBase {

    protected WorldGuard() {
        super("WorldGuard");
    }

    @Override
    public boolean isRegionProtectedReal(Player player, com.projectkorra.projectkorra.platform.mc.Location reallocation, CoreAbility ability, boolean igniteAbility, boolean explosiveAbility) {
        World world = reallocation.getWorld();

        final com.sk89q.worldguard.WorldGuard wg = com.sk89q.worldguard.WorldGuard.getInstance();
        final Location location = BukkitAdapter.adapt(BukkitMC.locationHandle(reallocation));
        final LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer((org.bukkit.entity.Player) player.handle());
        final SessionManager sessionManager = wg.getPlatform().getSessionManager();

        if (sessionManager.hasBypass(localPlayer, localPlayer.getWorld())) {
            return false;
        }

        if (igniteAbility) {
            if (!player.hasPermission("worldguard.override.lighter")) {
                if (wg.getPlatform().getGlobalStateManager().get(BukkitAdapter.adapt((org.bukkit.World) world.handle())).blockLighter) {
                    return true;
                }
            }
        }

        if (explosiveAbility) {
            if (wg.getPlatform().getGlobalStateManager().get(BukkitAdapter.adapt((org.bukkit.World) world.handle())).blockTNTExplosions) {
                return true;
            }
            final StateFlag.State tntflag = wg.getPlatform().getRegionContainer().createQuery().queryState(location, localPlayer, Flags.TNT);
            if (tntflag != null && tntflag.equals(StateFlag.State.DENY)) {
                return true;
            }
        }
        final Flag<?> flag = wg.getFlagRegistry().get("bending");
        if (flag instanceof StateFlag bendingflag) {
            final StateFlag.State bendingflagstate = wg.getPlatform().getRegionContainer().createQuery().queryState(location, localPlayer, bendingflag);
            if (bendingflagstate == null && !wg.getPlatform().getRegionContainer().createQuery().testState(location, localPlayer, Flags.BUILD)) {
                return true;
            }
            if (bendingflagstate != null && bendingflagstate.equals(StateFlag.State.DENY)) {
                return true;
            }
        } else {
            if (!wg.getPlatform().getRegionContainer().createQuery().testState(location, localPlayer, Flags.BUILD)) {
                return true;
            }
        }

        return false;
    }
}
