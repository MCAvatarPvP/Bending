package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.PKTask;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RollbackSchedulerTest {
    @Test void rewindsTaskMembershipCancellationAndMutableCallbackStateThroughExistingApi() {
        var scheduler = new RollbackScheduler(20, 20);
        var platform = platform(scheduler);
        List<Long> callbacks = new ArrayList<>();
        try (var scope = Platform.using(platform)) {
            class Callback extends BukkitRunnable {
                int count;
                @Override public void run() {
                    callbacks.add(scheduler.tick());
                    if (++count == 2) cancel();
                }
            }
            Callback callback = new Callback();
            callback.runTaskTimer(null, 0, 2);
            scheduler.advance(1);
            assertEquals(List.of(1L), callbacks);
            var graph = new RollbackStateGraph(ignored -> false, ignored -> true, 100);
            var checkpoint = graph.capture(List.of(scheduler), List.of());
            scheduler.advance(2);
            scheduler.advance(3);
            assertTrue(callback.isCancelled());
            assertEquals(0, scheduler.pendingTasks());
            assertEquals(List.of(1L, 3L), callbacks);
            checkpoint.restore();
            assertEquals(1, scheduler.tick());
            assertEquals(1, callback.count);
            assertFalse(callback.isCancelled());
            assertEquals(1, scheduler.pendingTasks());
            assertEquals(List.of(1L), callbacks);
            scheduler.advance(2);
            scheduler.advance(3);
            assertTrue(callback.isCancelled());
            assertEquals(List.of(1L, 3L), callbacks);
        }
    }

    @Test void zeroDelayNestedTasksWaitUntilNextTickAndStableOrderReplays() {
        var scheduler = new RollbackScheduler(20, 20);
        List<String> callbacks = new ArrayList<>();
        scheduler.runNow(() -> {
            callbacks.add("parent");
            scheduler.runAsync(() -> callbacks.add("child"));
        });
        scheduler.runLater(() -> callbacks.add("peer"), 0);
        var checkpoint = new RollbackStateGraph(ignored -> false, ignored -> true, 100)
                .capture(List.of(scheduler), List.of());
        scheduler.advance(1);
        assertEquals(List.of("parent", "peer"), callbacks);
        scheduler.advance(2);
        assertEquals(List.of("parent", "peer", "child"), callbacks);
        checkpoint.restore();
        assertTrue(callbacks.isEmpty());
        scheduler.advance(1);
        scheduler.advance(2);
        assertEquals(List.of("parent", "peer", "child"), callbacks);
    }

    @Test void aDiscardedBranchHandleCannotCancelAnotherTaskAfterIdsRewind() {
        var scheduler = new RollbackScheduler(20, 20);
        var saved = scheduler.captureRollbackState();
        PKTask discarded = scheduler.runNow(() -> {});
        scheduler.restoreRollbackState(saved);
        AtomicInteger calls = new AtomicInteger();
        PKTask replacement = scheduler.runNow(calls::incrementAndGet);
        assertEquals(discarded.legacyId(), replacement.legacyId());
        assertTrue(discarded.cancelled());
        discarded.cancel();
        assertFalse(replacement.cancelled());
        scheduler.advance(1);
        assertEquals(1, calls.get());
    }

    @Test void capturedActionAndSeedFollowTheCallbackAndClearAfterwards() {
        var scheduler = new RollbackScheduler(20, 20);
        List<Long> observed = new ArrayList<>();
        PredictionDeterminism.run(72, 991, () -> scheduler.runNow(() -> {
            observed.add(PredictionDeterminism.currentAction());
            observed.add(PredictionDeterminism.currentSeed());
        }));
        scheduler.advance(1);
        assertEquals(List.of(72L, 991L), observed);
        assertEquals(0, PredictionDeterminism.currentAction());
        assertEquals(0, PredictionDeterminism.currentSeed());
    }

    @Test void budgetsAndCallbackFailureStopReplayInsteadOfSkippingWork() {
        var scheduler = new RollbackScheduler(2, 1);
        scheduler.runNow(() -> {});
        scheduler.runNow(() -> {});
        assertThrows(IllegalStateException.class, () -> scheduler.runNow(() -> {}));
        assertThrows(IllegalStateException.class, () -> scheduler.advance(1));
        assertThrows(IllegalStateException.class, () -> scheduler.advance(2));
        var failing = new RollbackScheduler(2, 2);
        failing.runNow(() -> { throw new IllegalArgumentException("callback"); });
        assertThrows(IllegalArgumentException.class, () -> failing.advance(1));
        assertThrows(IllegalStateException.class, () -> failing.runNow(() -> {}));
    }

    @Test void nestedPlatformScopesRestoreEvenWhenSimulationThrows() {
        boolean installed = Platform.isInstalled();
        var original = installed ? Platform.current() : null;
        var first = platform(new RollbackScheduler(2, 2));
        var second = platform(new RollbackScheduler(2, 2));
        try (var outer = Platform.using(first)) {
            assertSame(first, Platform.current());
            assertThrows(IllegalArgumentException.class, () -> {
                try (var inner = Platform.using(second)) {
                    assertSame(second, Platform.current());
                    assertThrows(IllegalStateException.class, outer::close);
                    throw new IllegalArgumentException("simulation");
                }
            });
            assertSame(first, Platform.current());
        }
        assertEquals(installed, Platform.isInstalled());
        if (installed) assertSame(original, Platform.current());
    }

    private static ProjectKorraPlatform platform(RollbackScheduler scheduler) {
        return (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("scheduler")) return scheduler;
                    throw new AssertionError(method);
                });
    }
}
