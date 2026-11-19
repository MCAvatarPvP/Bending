package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Map;

/** Reads the currently enrolled Neptune round once, on the live server thread. */
final class PaperRollbackMatchRules {
    private PaperRollbackMatchRules() {}

    // Neptune 2.3's public bridge supplies the match UUID but its API jar omits getMatch and
    // getPlacedBlocks. Keep that compatibility lookup entirely outside private simulation.
    static RollbackDuelInteractions capture(PaperRollbackMatchBootstrap.Request request, Player owner) {
        var plugin = Bukkit.getPluginManager().getPlugin("Neptune");
        if (plugin == null || !plugin.isEnabled()) throw new IllegalStateException("Neptune match is unavailable");
        try {
            var api = Class.forName("dev.lrxh.neptune.API", false, plugin.getClass().getClassLoader());
            var profile = api.getMethod("getProfile", Player.class).invoke(null, owner);
            if (profile == null) throw new IllegalStateException("Neptune participant profile is unavailable");
            var match = call(profile, "getMatch");
            if (match == null || !request.match().equals(call(match, "getUuid")))
                throw new IllegalStateException("Neptune round changed before kit capture");
            var kit = call(match, "getKit");
            var enabled = new HashSet<String>();
            for (var rule : ((Map<?,?>)call(kit, "getRule")).entrySet()) {
                if (Boolean.TRUE.equals(rule.getValue())) enabled.add((String)call(rule.getKey(), "getSaveName"));
            }
            var allowed = new HashSet<String>();
            if (enabled.contains("arenaBreak")) {
                for (var material : (Iterable<?>)call(call(match, "getArena"), "getWhitelistedBlocks"))
                    allowed.add(((Material)material).name());
            }
            boolean build = enabled.contains("build");
            var placed = new HashSet<Position>();
            if (build) for (var object : (Iterable<?>)call(match, "getPlacedBlocks")) {
                var location = (Location)object;
                if (location.getWorld() == null || !request.world().equals(location.getWorld().getUID())) continue;
                var pos = new Position(location.getBlockX(), location.getBlockY(), location.getBlockZ());
                if (request.terrain().contains(pos)) placed.add(pos);
            }
            return new RollbackDuelInteractions(build, enabled.contains("bedwars"), allowed, placed);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot capture the current Neptune kit", failure);
        }
    }

    private static Object call(Object owner, String name) throws ReflectiveOperationException {
        return owner.getClass().getMethod(name).invoke(owner);
    }
}
