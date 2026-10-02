package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.*;
import com.projectkorra.projectkorra.platform.*;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackPlayerRestorationTest {
    private static Player player(long id) {
        return new Player() {
            final Object body = new Object();
            @Override public UUID getUniqueId() { return new UUID(0, id); }
            @Override public Object handle() { return body; }
        };
    }
    @Test @SuppressWarnings("unchecked") void rosterCommitPreservesOtherPlayersAndRejectsStaleOwnershipBeforeWriting() throws Exception {
        var field = OfflineBendingPlayer.class.getDeclaredField("TEMP_ELEMENTS"); field.setAccessible(true);
        var queue = (PriorityQueue<Pair<Player, Long>>) field.get(null);
        var savedQueue = new PriorityQueue<>(queue);
        var online = new HashMap<>(BendingPlayer.getPlayers());
        var offline = new HashMap<>(BendingPlayer.getOfflinePlayers());
        var backend = new RollbackLiveSchedulerTest.Backend();
        var platform = (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> {
                    if (method.getName().equals("scheduler")) return backend;
                    throw new AssertionError(method);
                });
        try (var scope = Platform.using(platform)) {
            var a = player(1); var b = player(2); var other = player(3);
            var beforeA = new BendingPlayer(a); var beforeB = new BendingPlayer(b); var outside = new BendingPlayer(other);
            var afterA = new BendingPlayer(a); var afterB = new BendingPlayer(b);
            afterA.getAbilities().put(1, "WaterManipulation");
            BendingPlayer.getPlayers().putAll(Map.of(a.getUniqueId(), beforeA, b.getUniqueId(), beforeB, other.getUniqueId(), outside));
            BendingPlayer.getOfflinePlayers().putAll(BendingPlayer.getPlayers());
            queue.clear(); queue.add(Pair.of(a, 900L));
            var exported = OfflineBendingPlayer.captureRollbackTemporaryElements(Set.of(a.getUniqueId()));
            queue.clear(); queue.add(Pair.of(a, 100L)); queue.add(Pair.of(b, 200L)); queue.add(Pair.of(other, 300L));
            var expected = Map.of(a.getUniqueId(), beforeA, b.getUniqueId(), beforeB);
            var restored = Map.of(a.getUniqueId(), afterA, b.getUniqueId(), afterB);
            var owner = OfflineBendingPlayer.prepareRollbackPlayerRestoration(expected, restored, exported);
            BendingPlayer.getPlayers().put(b.getUniqueId(), new BendingPlayer(b));
            assertThrows(IllegalStateException.class, owner::commit);
            assertSame(beforeA, BendingPlayer.getPlayers().get(a.getUniqueId()));
            assertEquals(3, queue.size());
            BendingPlayer.getPlayers().put(b.getUniqueId(), beforeB);
            owner.commit(); owner.commit(); owner.requireCurrent();
            assertSame(afterA, BendingPlayer.getPlayers().get(a.getUniqueId()));
            assertSame(afterB, BendingPlayer.getOfflinePlayers().get(b.getUniqueId()));
            assertSame(outside, BendingPlayer.getPlayers().get(other.getUniqueId()));
            assertSame(outside, BendingPlayer.getOfflinePlayers().get(other.getUniqueId()));
            assertEquals("WaterManipulation", BendingPlayer.getPlayers().get(a.getUniqueId()).getAbilities().get(1));
            assertEquals(2, queue.size());
            assertEquals(Pair.of(other, 300L), queue.peek());
            assertTrue(queue.contains(Pair.of(a, 900L)));
            var foreign = new BendingPlayer(player(1));
            assertThrows(IllegalArgumentException.class, () -> OfflineBendingPlayer.prepareRollbackPlayerRestoration(
                    restored, Map.of(a.getUniqueId(), foreign, b.getUniqueId(), afterB), exported));
            assertSame(afterA, BendingPlayer.getPlayers().get(a.getUniqueId()));
            try (var clock = RollbackClock.at(0, 0, 0, 50_000_000L)) {
                assertThrows(IllegalStateException.class, owner::commit);
            }
        } finally {
            BendingPlayer.getPlayers().clear(); BendingPlayer.getPlayers().putAll(online);
            BendingPlayer.getOfflinePlayers().clear(); BendingPlayer.getOfflinePlayers().putAll(offline);
            queue.clear(); queue.addAll(savedQueue);
        }
    }
}
