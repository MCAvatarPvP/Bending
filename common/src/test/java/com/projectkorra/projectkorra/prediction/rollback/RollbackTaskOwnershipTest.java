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
