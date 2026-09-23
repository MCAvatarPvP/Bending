package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.PassiveAbility;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;

import java.util.*;

/** Resolves the server's permission provider once at capture, including explicit denials and child nodes. */
public final class PaperRollbackPlayerAccess {
    private PaperRollbackPlayerAccess() { }

    /** Additional dynamically generated addon nodes must be declared before the shared snapshot is captured. */
    public static RollbackPlayerAccess capture(Collection<? extends Player> roster, Collection<Permission> registered,
                                                Collection<String> gameplayNodes) {
        if (!TickThread.isTickThread() || RollbackDomain.active() || RollbackClock.active()) throw new IllegalStateException("Capture player access on the live tick thread before replay");
        var players = new TreeMap<UUID, Player>();
        for (var player : roster) if (players.putIfAbsent(player.getUniqueId(), player) != null) throw new IllegalArgumentException("Duplicate access player");
        if (players.size() < 2 || players.size() > RollbackRosterData.MAXIMUM_PLAYERS) throw new IllegalArgumentException("Access roster size");
        var nodes = new TreeSet<String>();
        for (var permission : registered) {
            add(nodes, permission.getName());
            // Children need not have their own registered Permission object. Evaluating their
            // real hasPermission decision also honors provider inheritance and explicit denies.
            for (var child : permission.getChildren().keySet()) add(nodes, child);
        }
        for (var node : gameplayNodes) add(nodes, node);
        for (var player : players.values()) for (var permission : player.getEffectivePermissions()) add(nodes, permission.getPermission());
        if ((long) nodes.size() * players.size() > RollbackPlayerAccess.MAXIMUM_DECISIONS) throw new IllegalArgumentException("Access decision budget");
        var captured = new TreeMap<UUID, RollbackPlayerAccess.Entry>();
        for (var player : players.values()) {
            var profile = new RollbackPlayerState.Profile(player.getDisplayName(), player.getGameMode().name(), player.getMainHand().name(),
                    player.isOnline(), player.isOp(), player.hasPlayedBefore(), player.getPing());
            var hidden = new TreeSet<UUID>(); players.forEach((id, target) -> { if (!player.canSee(target)) hidden.add(id); });
            var decisions = new TreeMap<String, Boolean>();
            for (var node : nodes) decisions.put(node, player.hasPermission(node));
            captured.put(player.getUniqueId(), new RollbackPlayerAccess.Entry(player.getUniqueId(), profile, hidden, decisions));
        }
        return new RollbackPlayerAccess(captured);
    }

    /** Mirrors the generic ability/passive permission construction, without a list of selected abilities. */
    public static Set<String> gameplayNodes() {
        var result = new TreeSet<String>();
        for (var ability : CoreAbility.getAbilities()) {
            if (ability == null || ability.getName() == null || ability.getName().isBlank()) throw new IllegalStateException("Registered ability has no permission identity");
            add(result, "bending.ability." + ability.getName());
            if (ability instanceof PassiveAbility && ability.getElement() != null) add(result, "bending." + ability.getElement().getName() + ".passive");
        }
        return Collections.unmodifiableSet(result);
    }
    private static void add(Set<String> nodes, String node) {
        nodes.add(RollbackPlayerAccess.permissionName(node));
        if (nodes.size() > RollbackPlayerAccess.MAXIMUM_PERMISSIONS) throw new IllegalArgumentException("Access permission budget");
    }
}
