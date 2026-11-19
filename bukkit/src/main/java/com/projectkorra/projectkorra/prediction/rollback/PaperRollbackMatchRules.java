package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import com.projectkorra.projectkorra.BendingPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;
import java.util.Map;

/** Reads the currently enrolled Neptune round once, on the live server thread. */
final class PaperRollbackMatchRules {
    private PaperRollbackMatchRules() {}

    // Neptune 2.3's public bridge supplies the match UUID but its API jar omits getMatch and
    // getPlacedBlocks. Keep that compatibility lookup entirely outside private simulation.
    record Rules(RollbackDuelInteractions interactions, RollbackArenaFloor floor) {}

    static Rules capture(PaperRollbackMatchBootstrap.Request request, Player owner) {
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
            var arena = call(match, "getArena");
            var checkpoints = new java.util.TreeMap<UUID, RollbackArenaFloor.Respawn>();
            if (enabled.contains("parkour")) {
                var participantById = match.getClass().getMethod("getParticipant", UUID.class);
                var matchType = api.getClassLoader().loadClass("dev.lrxh.neptune.game.match.Match");
                for (var id : request.sides().keySet()) {
                    var participant = participantById.invoke(match, id);
                    var spawn = (Location) participant.getClass().getMethod("getSpawn", matchType).invoke(participant, match);
                    if (spawn == null || spawn.getWorld() == null || !request.world().equals(spawn.getWorld().getUID()))
                        throw new IllegalStateException("Parkour checkpoint is outside the captured arena world");
                    checkpoints.put(id, new RollbackArenaFloor.Respawn(spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getYaw(), spawn.getPitch()));
                }
            }
            var interactions = new RollbackDuelInteractions(build, enabled.contains("bedwars"), allowed, placed);
            return new Rules(interactions, new RollbackArenaFloor((int) call(arena, "getDeathY"), checkpoints));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot capture the current Neptune kit", failure);
        }
    }

    static RollbackBendingHooks bendingHooks(Collection<? extends Player> players) {
        var projection = new NeptuneSpectators(players);
        return BendingPlayer.captureRollbackHooks(
                hook -> projection.fromNeptune(hook) ? projection.policy() : hook,
                hook -> projection.fromNeptune(hook) ? projection.policy() : hook);
    }

    // Neptune 2.3 registers both methods under ProjectKorra's plugin identity. Resolve their declaring
    // plugin class instead. Other shared addon callbacks are copied by the normal gameplay graph codec.
    private static final class NeptuneSpectators {
        private final Collection<? extends Player> players;
        private RollbackBendingHooks.Spectators captured;
        NeptuneSpectators(Collection<? extends Player> players) { this.players = players; }

        boolean fromNeptune(Object hook) {
            var host = hook.getClass().getNestHost();
            if (!host.getName().equals("dev.lrxh.neptune.hooks.ProjectKorraHook")) return false;
            var plugin = Bukkit.getPluginManager().getPlugin("Neptune");
            return plugin != null && host.getClassLoader() == plugin.getClass().getClassLoader();
        }

        RollbackBendingHooks.Spectators policy() {
            if (captured != null) return captured;
            var plugin = Bukkit.getPluginManager().getPlugin("Neptune");
            try {
                var api = Class.forName("dev.lrxh.neptune.API", false, plugin.getClass().getClassLoader());
                var getProfile = api.getMethod("getProfile", Player.class);
                var spectators = new HashSet<UUID>();
                for (var player : players) {
                    var profile = getProfile.invoke(null, player);
                    if (profile != null && ((Enum<?>)call(profile, "getState")).name().equals("IN_SPECTATOR"))
                        spectators.add(player.getUniqueId());
                }
                captured = new RollbackBendingHooks.Spectators(spectators);
                return captured;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot capture Neptune spectator bending rules", failure);
            }
        }
    }

    private static Object call(Object owner, String name) throws ReflectiveOperationException {
        return owner.getClass().getMethod(name).invoke(owner);
    }
}
