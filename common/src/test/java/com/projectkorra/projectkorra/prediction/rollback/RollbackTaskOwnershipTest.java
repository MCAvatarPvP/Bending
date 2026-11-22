package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackTaskOwnershipTest {
    private static final UUID A = new UUID(0, 81), B = new UUID(0, 82);
    private static Player player(UUID id) { return new Player() { @Override public UUID getUniqueId() { return id; } }; }
    private static final class Callback implements Runnable {
        Object captured;
        int calls;
        Callback(Object captured) { this.captured = captured; }
        @Override public void run() { calls++; }
    }
    private static RollbackLiveScheduler.Work work(Runnable callback) { return new RollbackLiveScheduler.Work(callback, null, false, 0, 0); }
    private static RollbackTaskOwnership ownership() { return new RollbackTaskOwnership(Set.of(A), List.of(), ignored -> false, 100); }

    @Test void untaggedCallbacksFollowCyclicCapturedStateAndLeaveOutsidersRunning() {
        var backend = new RollbackLiveSchedulerTest.Backend();
        var scheduler = new RollbackLiveScheduler(backend, () -> backend.tick, callback -> callback);
        var values = new ArrayList<Object>(); values.add(values); values.add(Optional.of(player(A)));
        var mine = new Callback(values); var other = new Callback(player(B));
        scheduler.runNow(mine); scheduler.runNow(other);
        var lease = scheduler.prepare(ownership()); var captured = lease.freeze(8);
        assertEquals(1, captured.bindings().entries().size());
        assertSame(mine, captured.bindings().entries().getFirst().callback());
        backend.advance(); assertEquals(0, mine.calls); assertEquals(1, other.calls);
        lease.restore(); backend.advance(); assertEquals(1, mine.calls); assertEquals(1, other.calls);
    }
    @Test void enrollingWithRandomCallbacksFreezesOnlyTheDuelistAndPreservesLiveCadence() {
        var backend = new RollbackLiveSchedulerTest.Backend();
        var scheduler = new RollbackLiveScheduler(backend, () -> backend.tick, callback -> callback);
        var plain = new Random(17);
        var ownedRandom = new RollbackRandom(43);
        ownedRandom.nextGaussian();
        var state = ownedRandom.captureRollbackState();
        var ambient = new Callback(plain);
        var mine = new Callback(List.of(ownedRandom, player(A)));
        var outside = new Callback(List.of(new Random(23), player(B)));
        scheduler.runTimer(ambient, 1, 2);
        scheduler.runLater(mine, 2);
        scheduler.runTimer(outside, 2, 2);
        var lease = scheduler.prepare(ownership());
        try {
            var captured = lease.freeze(8);
            assertEquals(List.of(mine), captured.bindings().entries().stream().map(RollbackTaskBindings.Entry::callback).toList());
            var late = new Callback(new Random(31));
            scheduler.runNow(late);
            lease.requireCurrent();
            for (int i = 0; i < 3; i++) backend.advance();
            assertEquals(2, ambient.calls);
            assertEquals(1, outside.calls);
            assertEquals(1, late.calls);
            assertEquals(0, mine.calls);
        } finally { lease.restore(); }
        backend.advance();
        assertEquals(2, outside.calls);
        assertEquals(0, mine.calls);
        backend.advance();
        assertEquals(3, ambient.calls);
        assertEquals(1, mine.calls);
        assertEquals(new Random(17).nextLong(), plain.nextLong());
        assertEquals(state, ownedRandom.captureRollbackState());
    }

    private static final class PlayerRandom extends Random {
        private final Player owner;
        PlayerRandom(Player owner) { this.owner = owner; }
    }

    @Test void playerReferencesInAnAddonRandomStillGateCallbacksAndRejectMixedOwnership() {
        var selector = ownership();
        var mine = new Callback(new PlayerRandom(player(A)));
        var outside = new Callback(new PlayerRandom(player(B)));
        var mixed = new Callback(List.of(new PlayerRandom(player(A)), player(B)));
        assertTrue(selector.test(work(mine)));
        assertFalse(selector.test(work(outside)));
        assertThrows(IllegalStateException.class, () -> selector.test(work(mixed)));
        assertTrue(selector.referencesParticipant(mine));
        assertTrue(selector.referencesParticipant(mixed));
        assertFalse(selector.referencesParticipant(outside));
        assertFalse(selector.referencesParticipant(new Callback(new Random(17))));
    }

    @Test void statisticsPersistenceContinuesOnItsLiveCadenceWhenTheManagerContainsDuelistRows() throws Exception {
        var constructor = com.projectkorra.projectkorra.util.StatisticsManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var statistics = constructor.newInstance();
        statistics.store(A);
        statistics.store(B);
        var backend = new RollbackLiveSchedulerTest.Backend();
        var scheduler = new RollbackLiveScheduler(backend, () -> backend.tick, callback -> callback);
        var handle = scheduler.runTimer(statistics, 57, 100);
        var mine = new Callback(List.of(statistics, A));
        scheduler.runLater(mine, 57);
        var checked = new ArrayList<UUID>();
        var players = (com.projectkorra.projectkorra.platform.PKPlayers) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.projectkorra.projectkorra.platform.PKPlayers.class}, (self, method, args) -> {
                    if (!method.getName().equals("getPlayer")) throw new AssertionError(method);
                    checked.add((UUID) args[0]);
                    return player((UUID) args[0]);
                });
        var platform = (com.projectkorra.projectkorra.platform.ProjectKorraPlatform) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.projectkorra.projectkorra.platform.ProjectKorraPlatform.class},
                (self, method, args) -> switch (method.getName()) {
                    case "scheduler" -> scheduler;
                    case "players" -> players;
                    default -> throw new AssertionError(method);
                });
        try (var ignored = com.projectkorra.projectkorra.platform.Platform.using(platform)) {
            var flightConstructor = com.projectkorra.projectkorra.util.FlightHandler.class.getDeclaredConstructor();
            flightConstructor.setAccessible(true);
            var flights = flightConstructor.newInstance();
            var duelist = player(A);
            var outsider = player(B);
            flights.createInstance(duelist, 0, "duelist");
            flights.createInstance(outsider, 0, "outside");
            flights.startCleanup();
            var roster = RollbackLiveOwnership.prepare(Set.of(A));
            var lease = scheduler.prepare(ownership());
            roster.acquire();
            try {
                var capture = lease.freeze(8);
                assertEquals(List.of(mine), capture.bindings().entries().stream().map(RollbackTaskBindings.Entry::callback).toList());
                assertFalse(handle.cancelled());
                assertEquals(57, scheduler.managerDelay(statistics.getClass(), 100));
                for (int tick = 0; tick < 57; tick++) backend.advance();
                assertEquals(List.of(B), checked);
                assertNotNull(flights.getInstance(duelist));
                assertNull(flights.getInstance(outsider));
                assertEquals(0, mine.calls);
            } finally {
                lease.restore();
                roster.restoreAndRelease(() -> {});
            }
            for (int tick = 0; tick < 100; tick++) backend.advance();
            assertEquals(List.of(B, A), checked);
            assertNull(flights.getInstance(duelist));
            assertEquals(1, mine.calls);
        }
    }

    @Test void sharedCallbackCannotPartiallyFreezeAnotherPlayersWork() {
        var backend = new RollbackLiveSchedulerTest.Backend();
        var scheduler = new RollbackLiveScheduler(backend, () -> backend.tick, callback -> callback);
        var shared = new Callback(List.of(player(A), player(B)));
        scheduler.runNow(shared);
        var lease = scheduler.prepare(ownership());
        assertThrows(IllegalStateException.class, () -> lease.freeze(8));
        lease.restore(); backend.advance(); assertEquals(1, shared.calls);
    }
    @Test void lambdaAndUuidCaptureAreResolvedEachTimeRatherThanCachedByClass() {
        Player mine = player(A), other = player(B);
        Runnable first = () -> mine.getUniqueId(); Runnable second = () -> other.getUniqueId();
        var selector = ownership();
        assertTrue(selector.test(work(first))); assertFalse(selector.test(work(second)));
        var callback = new Callback(B); assertFalse(selector.test(work(callback)));
        callback.captured = A; assertTrue(selector.test(work(callback)));
    }
    @Test void participantOnlyServiceRootsUseIdentityAndOpaqueInfrastructureRequiresBoundary() {
        var privateService = new Object(); var sharedService = Thread.currentThread();
        var selector = new RollbackTaskOwnership(Set.of(A), List.of(privateService), value -> value == sharedService, 100);
        assertTrue(selector.test(work(new Callback(List.of(privateService, sharedService)))));
        assertFalse(selector.test(work(new Callback(new Object()))));
        assertThrows(IllegalArgumentException.class, () -> ownership().test(work(new Callback(sharedService))));
        assertThrows(IllegalStateException.class, () -> new RollbackTaskOwnership(Set.of(A), List.of(), ignored -> false, 2)
                .test(work(new Callback(List.of(new Object(), new Object(), player(A))))));
    }
    @Test void newlySubmittedOwnedCallbackInvalidatesFrozenLease() {
        var backend = new RollbackLiveSchedulerTest.Backend();
        var scheduler = new RollbackLiveScheduler(backend, () -> backend.tick, callback -> callback);
        var lease = scheduler.prepare(ownership()); lease.freeze(8);
        var callback = new Callback(player(A));
        assertThrows(IllegalStateException.class, () -> scheduler.runNow(callback));
        assertThrows(IllegalStateException.class, lease::requireCurrent);
        lease.restore(); backend.advance(); assertEquals(0, callback.calls);
    }
}
