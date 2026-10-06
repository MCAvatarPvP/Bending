package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.prediction.movement.VelocitySync;
import com.projectkorra.projectkorra.prediction.authority.PredictionServices;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.BendingManager;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.OfflineBendingPlayer;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.FlightHandler;
import org.apache.commons.lang3.tuple.Pair;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RollbackLiveOwnershipTest {
    private static final UUID A = new UUID(0, 41), B = new UUID(0, 42), C = new UUID(0, 43);
    @Test void overlapCannotPartiallyReserveAndFailedRestorationKeepsWholeRoster() {
        try (var scope = Platform.using(platform())) {
            var first = RollbackLiveOwnership.prepare(Set.of(A, B));
            var overlap = RollbackLiveOwnership.prepare(Set.of(B, C));
            try {
                assertFalse(RollbackLiveOwnership.blocks(A));
                first.acquire(); first.acquire();
                assertThrows(IllegalStateException.class, overlap::acquire);
                assertTrue(RollbackLiveOwnership.blocks(A)); assertTrue(RollbackLiveOwnership.blocks(B));
                assertFalse(RollbackLiveOwnership.blocks(C));
                assertThrows(IllegalStateException.class, () -> first.restoreAndRelease(() -> { throw new IllegalStateException("Native restore failed"); }));
                first.requireCurrent();
                assertTrue(RollbackLiveOwnership.blocks(A)); assertTrue(RollbackLiveOwnership.blocks(B));
                var calls = new AtomicInteger();
                Runnable restore = () -> {
                    calls.incrementAndGet();
                    assertTrue(RollbackLiveOwnership.blocks(A)); assertTrue(RollbackLiveOwnership.blocks(B));
                    assertThrows(IllegalStateException.class, () -> first.restoreAndRelease(() -> fail("Recursive restore")));
                };
                first.restoreAndRelease(restore); first.restoreAndRelease(restore);
                assertEquals(1, calls.get());
                assertFalse(RollbackLiveOwnership.blocks(A)); assertFalse(RollbackLiveOwnership.blocks(B));
                assertThrows(IllegalStateException.class, first::acquire);
                overlap.acquire();
                assertTrue(RollbackLiveOwnership.blocks(B)); assertTrue(RollbackLiveOwnership.blocks(C));
            } finally {
                first.restoreAndRelease(() -> { }); overlap.restoreAndRelease(() -> { });
            }
        }
    }
    @Test void privateSimulationRunsWhileLiveRosterIsReservedAndCannotReleaseOwnership() {
        var platform = platform();
        try (var scope = Platform.using(platform)) {
            var lease = RollbackLiveOwnership.prepare(Set.of(A, B)); lease.acquire();
            try {
                var domain = RollbackDomain.create(new RollbackStateGraph(value -> false, field -> true, 100),
                        List.of(), List.of(), platform, () -> { });
                domain.call(() -> {
                    assertFalse(RollbackLiveOwnership.blocks(A)); assertFalse(RollbackLiveOwnership.blocks(B));
                    assertThrows(IllegalStateException.class, () -> lease.restoreAndRelease(() -> fail("Live restore in replay")));
                    assertThrows(IllegalStateException.class, () -> RollbackLiveOwnership.prepare(Set.of(C)));
                    return null;
                });
                lease.requireCurrent(); assertTrue(RollbackLiveOwnership.blocks(A));
            } finally { lease.restoreAndRelease(() -> { }); }
        }
    }
    @Test @SuppressWarnings("unchecked") void liveExpiryQueuesSkipOwnedPlayersWithoutBlockingOthers() throws Exception {
        var scheduler = new RollbackLiveSchedulerTest.Backend();
        try (var scope = Platform.using(platform(scheduler))) {
            var lease = RollbackLiveOwnership.prepare(Set.of(A));
            var originalPlayers = new HashMap<>(BendingPlayer.getPlayers());
            var field = OfflineBendingPlayer.class.getDeclaredField("TEMP_ELEMENTS"); field.setAccessible(true);
            var expiries = (PriorityQueue<Pair<Player, Long>>) field.get(null);
            var saved = new ArrayList<>(expiries);
            var a = player(A); var c = player(C);
            var bendingA = new ExpiringPlayer(a); var bendingC = new ExpiringPlayer(c);
            var constructor = FlightHandler.class.getDeclaredConstructor(); constructor.setAccessible(true);
            var flights = constructor.newInstance();
            try {
                BendingPlayer.getPlayers().put(A, bendingA); BendingPlayer.getPlayers().put(C, bendingC);
                expiries.clear(); expiries.add(Pair.of(a, 1L)); expiries.add(Pair.of(c, 2L));
                try (var clock = RollbackClock.at(0, 0, 50_000_000)) {
                    flights.createInstance(a, 1, "owned"); flights.createInstance(c, 2, "unrelated");
                }
                flights.startCleanup(); lease.acquire();
                scheduler.advance(); new BendingManager.TempElementsRunnable().run();
                assertNotNull(flights.getInstance(a)); assertNull(flights.getInstance(c));
                assertEquals(0, bendingA.expired); assertEquals(1, bendingC.expired);
                assertEquals(List.of(Pair.of(a, 1L)), new ArrayList<>(expiries));
                scheduler.advance(); new BendingManager.TempElementsRunnable().run();
                assertNotNull(flights.getInstance(a)); assertEquals(0, bendingA.expired);
                lease.restoreAndRelease(() -> { });
                scheduler.advance(); new BendingManager.TempElementsRunnable().run();
                assertNull(flights.getInstance(a)); assertEquals(1, bendingA.expired); assertTrue(expiries.isEmpty());
            } finally {
                lease.restoreAndRelease(() -> { }); scheduler.cancelAll();
                expiries.clear(); expiries.addAll(saved);
                BendingPlayer.getPlayers().clear(); BendingPlayer.getPlayers().putAll(originalPlayers);
            }
        }
    }
    @Test void velocityOwnershipPrecedesCommitScopesAndDoesNotPublishLiveReceipts() {
        var writes = new AtomicInteger(); var receipts = new AtomicInteger(); var privateReceipts = new AtomicInteger();
        VelocitySync.Listener listener = (ability, target, velocity) -> receipts.incrementAndGet();
        var platform = platform(); var owned = player(A); var outsider = player(C);
        var ability = (Ability) Proxy.newProxyInstance(
                Ability.class.getClassLoader(), new Class<?>[]{Ability.class},
                (proxy, method, args) -> { if (method.getName().equals("getPlayer")) return owned; throw new AssertionError(method); });
        var velocity = new Vector(1, 2, 3);
        VelocitySync.install(listener);
        try (var scope = Platform.using(platform)) {
            var lease = RollbackLiveOwnership.prepare(Set.of(A)); lease.acquire();
            try {
                VelocitySync.applyDirect(null, owned, velocity, writes::incrementAndGet);
                VelocitySync.commit(() ->
                        VelocitySync.applyDirect(ability, owned, velocity, writes::incrementAndGet));
                VelocitySync.publish(ability, owned, velocity);
                assertEquals(0, writes.get()); assertEquals(0, receipts.get());
                VelocitySync.applyDirect(null, outsider, velocity, writes::incrementAndGet);
                VelocitySync.publish(ability, outsider, velocity);
                assertEquals(1, writes.get()); assertEquals(1, receipts.get());
                var domain = RollbackDomain.create(new RollbackStateGraph(value -> false, field -> true, 100), List.of(), List.of(), platform, () -> { });
                domain.call(() -> {
                    var bindings = PredictionServices.builder()
                            .bind(VelocitySync.Listener.class,
                                    (source, target, vector) -> privateReceipts.incrementAndGet()).build();
                    try (var services = PredictionServices.using(bindings)) {
                        VelocitySync.applyDirect(ability, owned, velocity, writes::incrementAndGet);
                    }
                    return null;
                });
                assertEquals(2, writes.get()); assertEquals(1, receipts.get()); assertEquals(1, privateReceipts.get());
                VelocitySync.commitPredictedRemote(owned, () ->
                        VelocitySync.applyDirect(ability, owned, velocity, writes::incrementAndGet));
                assertEquals(2, writes.get()); assertEquals(1, receipts.get());
                lease.restoreAndRelease(() -> { });
                VelocitySync.applyDirect(ability, owned, velocity, writes::incrementAndGet);
                assertEquals(3, writes.get()); assertEquals(2, receipts.get());
            } finally { lease.restoreAndRelease(() -> { }); }
        } finally { VelocitySync.clear(listener); }
    }

    private static Player player(UUID id) { return new Player() { @Override public UUID getUniqueId() { return id; } }; }
    private static final class ExpiringPlayer extends BendingPlayer {
        int expired;
        ExpiringPlayer(Player player) { super(player); }
        @Override public void recalculateTempElements(boolean offline) { expired++; }
    }
    private static ProjectKorraPlatform platform() { return platform(new RollbackLiveSchedulerTest.Backend()); }
    private static ProjectKorraPlatform platform(RollbackLiveSchedulerTest.Backend scheduler) {
        return (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> {
                    if (method.getName().equals("scheduler")) return scheduler;
                    throw new AssertionError(method);
                });
    }
}
