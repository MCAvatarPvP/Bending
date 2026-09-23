package com.projectkorra.projectkorra.prediction.rollback;

import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackPlayerAccessNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void resolvesProviderDecisionsIncludingUnregisteredChildrenAndEffectiveNodesWithoutTruncation() throws Exception {
        onTickThread(() -> {
            var first = new UUID(0, 1); var second = new UUID(0, 2);
            var denied = new HashSet<>(Set.of("bending.feature.denied", "other.effective"));
            var a = player(first, "ADVENTURE", "LEFT", Set.of(second), denied);
            var b = player(second, "SURVIVAL", "RIGHT", Set.of(), Set.of());
            var parent = new Permission("bending.parent"); parent.getChildren().put("bending.feature.denied", true);
            var candidates = new ArrayList<String>();
            for (int i = 0; i < 5_000; i++) candidates.add("addon.feature." + i);
            candidates.add("bending.*");
            var snapshot = PaperRollbackPlayerAccess.capture(List.of(b, a), List.of(parent), candidates);
            assertEquals(5_004, snapshot.players().get(first).permissions().size());
            assertFalse(snapshot.players().get(first).hasPermission("BENDING.FEATURE.DENIED"));
            assertTrue(snapshot.players().get(second).hasPermission("bending.feature.denied"));
            assertFalse(snapshot.players().get(first).hasPermission("other.effective"));
            assertTrue(snapshot.players().get(first).profile().operator());
            assertEquals(Set.of(second), snapshot.players().get(first).hidden());
            denied.clear(); assertFalse(snapshot.players().get(first).hasPermission("bending.feature.denied"));
            assertThrows(IllegalStateException.class, () -> snapshot.players().get(first).hasPermission("bending.unknown"));
            assertArrayEquals(snapshot.encode(), RollbackPlayerAccess.decode(snapshot.encode()).encode());
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackPlayerAccess.capture(List.of(a, a), List.of(), List.of()));
            return null;
        });
    }

    @Test void cannotCaptureFromAnAsyncThread() {
        assertThrows(IllegalStateException.class, () -> PaperRollbackPlayerAccess.capture(List.of(), List.of(), List.of()));
    }

    /** Explicit Bukkit policy fixture, accompanying the separately captured native player fixture. */
    static RollbackPlayerAccess fixture(RollbackRosterData roster) {
        var ids = List.copyOf(roster.players().keySet()); var players = new ArrayList<Player>();
        for (var entry : roster.players().entrySet()) {
            var identity = entry.getValue().identity();
            players.add(player(entry.getKey(), identity.mode().name(), identity.client().mainHand().name(),
                    entry.getKey().equals(ids.getFirst()) ? Set.of(ids.getLast()) : Set.of(), Set.of("bending.feature.denied", "other.effective")));
        }
        var parent = new Permission("bending.parent"); parent.getChildren().put("bending.feature.denied", true);
        return PaperRollbackPlayerAccess.capture(players, List.of(parent), List.of("bending.feature.allowed"));
    }

    private static Player player(UUID id, String mode, String hand, Set<UUID> hidden, Set<String> denied) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getDisplayName" -> "\u00a7bplayer" + id.getLeastSignificantBits();
            case "getGameMode" -> org.bukkit.GameMode.valueOf(mode);
            case "getMainHand" -> org.bukkit.inventory.MainHand.valueOf(hand);
            case "isOnline", "isOp" -> true;
            case "hasPlayedBefore" -> false;
            case "getPing" -> 142;
            case "canSee" -> !hidden.contains(((Player) args[0]).getUniqueId());
            case "getEffectivePermissions" -> Set.of(new PermissionAttachmentInfo((Player) proxy, "other.effective", null, false));
            case "hasPermission" -> !denied.contains((String) args[0]);
            default -> throw new AssertionError("Player policy capture accessed unrelated service: " + method);
        });
    }
}
