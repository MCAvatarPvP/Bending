package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.util.MultiAbilityManager;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.PassiveAbility;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

    private static final class InstalledPermissions {
        static final Set<String> NODES = load();

        private static Set<String> load() {
            var nodes = new TreeSet<String>();
            try {
                var resources = PaperRollbackPlayerAccess.class.getClassLoader().getResources(
                        "META-INF/projectkorra/rollback-gameplay.permissions");
                if (!resources.hasMoreElements()) throw new IllegalStateException("Missing rollback gameplay permission inventory");
                while (resources.hasMoreElements()) {
                    try (var reader = new BufferedReader(new InputStreamReader(resources.nextElement().openStream(), StandardCharsets.UTF_8))) {
                        for (String node; (node = reader.readLine()) != null;) add(nodes, node);
                    }
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot read rollback gameplay permission inventory", failure);
            }
            return Collections.unmodifiableSet(nodes);
        }
    }

    /** Includes shared listener checks and runtime permission names from the installed gameplay registries. */
    public static Set<String> gameplayNodes() {
        var result = new TreeSet<>(InstalledPermissions.NODES);
        for (var element : Element.getAllElements()) add(result, "bending.element." + element.getName());
        for (var sub : Element.getAllSubElements()) {
            var parents = sub instanceof Element.MultiSubElement multi ? multi.getParentElements() : new Element[]{sub.getParentElement()};
            for (var parent : parents) add(result, "bending." + parent.getName() + "." + sub.getName() + sub.getType().getBending());
        }
        for (var multi : MultiAbilityManager.multiAbilityList) {
            for (var mode : multi.getAbilities()) add(result, "bending.ability." + multi.getName() + "." + mode.getName());
        }
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
