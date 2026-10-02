package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.*;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RollbackLiveSchedulerTest {
    static final class Backend implements PKScheduler {
        final Thread owner = Thread.currentThread();
        final Map<Integer, NativeTask> tasks = new LinkedHashMap<>();
        long tick; int next = 1, submissions, failAt = -1, cancelFailures;
        boolean failCleanup;
        final class NativeTask implements PKTask {
            final int id = next++; final Runnable callback; final long period; long due;
            boolean cancelled; int cancellations;
            NativeTask(Runnable callback, long delay, long period) { this.callback = callback; this.period = period; due = tick + Math.max(1, delay); }
            @Override public void cancel() { cancellations++; if (cancelFailures > 0) { cancelFailures--; throw new IllegalStateException("cancel failed"); } cancelled = true; tasks.remove(id); }
            @Override public boolean cancelled() { return cancelled; }
            @Override public int legacyId() { return id; }
        }
        PKTask add(Runnable callback, long delay, long period) {
            if (++submissions == failAt) { if (failCleanup) cancelFailures++; throw new IllegalStateException("schedule failed"); }
            var task = new NativeTask(callback, delay, period); tasks.put(task.id, task); return task;
        }
        void advance() {
            tick++;
            for (var task : List.copyOf(tasks.values())) if (!task.cancelled && task.due <= tick) {
                if (task.period > 0) task.due = tick + task.period; else tasks.remove(task.id);
                task.callback.run();
            }
        }
        @Override public PKTask runNow(Runnable task) { return runLater(task, 0); }
        @Override public PKTask runAsync(Runnable task) { return runLater(task, 0); }
        @Override public PKTask runLater(Runnable task, long delay) { return add(task, delay, -1); }
        @Override public PKTask runAsyncLater(Runnable task, long delay) { return runLater(task, delay); }
        @Override public PKTask runTimer(Runnable task, long delay, long period) { return add(task, delay, period); }
        @Override public PKTask runTimerAsync(Runnable task, long delay, long period) { return runTimer(task, delay, period); }
        @Override public int scheduleRepeating(Runnable task, long delay, long period) { return runTimer(task, delay, period).legacyId(); }
        @Override public void cancelTask(int id) { var task = tasks.get(id); if (task != null) task.cancel(); }
        @Override public void cancelAll() { for (var task : List.copyOf(tasks.values())) task.cancel(); }
        @Override public boolean isPrimaryThread() { return Thread.currentThread() == owner; }
        @Override public <T> Future<T> callSync(Callable<T> task) { var result = new CompletableFuture<T>(); try { result.complete(task.call()); } catch (Exception failure) { result.completeExceptionally(failure); } return result; }
    }
    static RollbackLiveScheduler scheduler(Backend backend) { return new RollbackLiveScheduler(backend, () -> backend.tick, callback -> callback); }

    @Test void freezeCapturesRelativeTimingAndContextThenRestoresStableHandles() {
        var backend = new Backend(); AtomicInteger context = new AtomicInteger(8); List<Integer> seen = new ArrayList<>();
        var scheduler = new RollbackLiveScheduler(backend, () -> backend.tick, callback -> { int captured = context.get(); return () -> { seen.add(captured); callback.run(); }; });
        AtomicInteger calls = new AtomicInteger(); Runnable callback = calls::incrementAndGet;
        PKTask[] handle = new PKTask[1];
        PredictionDeterminism.run(71, 93, () -> handle[0] = scheduler.runTimer(callback, 4, 3));
        var old = backend.tasks.get(handle[0].legacyId()); backend.advance();
        var lease = scheduler.prepare(work -> work.callback() == callback);
        var capture = lease.freeze(); var entry = capture.bindings().entries().getFirst();
        assertEquals(3, entry.delay()); assertEquals(3, entry.period()); assertEquals(71, entry.action()); assertEquals(93, entry.seed());
        assertSame(callback, entry.callback()); assertFalse(handle[0].cancelled());
        backend.advance(); backend.advance(); old.callback.run(); assertEquals(0, calls.get());
        context.set(99); lease.restore(); lease.restore();
        assertEquals(1, handle[0].legacyId()); assertEquals(1, backend.tasks.size());
        old.callback.run(); assertEquals(0, calls.get());
        backend.advance(); backend.advance(); assertEquals(0, calls.get()); backend.advance();
        assertEquals(1, calls.get()); assertEquals(List.of(8), seen);
        scheduler.cancelTask(handle[0].legacyId()); assertTrue(handle[0].cancelled()); assertTrue(backend.tasks.isEmpty());
    }

    @Test void unrelatedTasksContinueAndFrozenMutationInvalidatesTheLease() {
        var backend = new Backend(); var scheduler = scheduler(backend); AtomicInteger selected = new AtomicInteger(), unrelated = new AtomicInteger();
        Runnable callback = selected::incrementAndGet;
        PKTask handle = scheduler.runTimer(callback, 1, 1); scheduler.runTimer(unrelated::incrementAndGet, 1, 1);
        var lease = scheduler.prepare(work -> work.callback() == callback); lease.freeze();
        backend.advance(); assertEquals(0, selected.get()); assertEquals(1, unrelated.get());
        assertThrows(IllegalStateException.class, () -> scheduler.runNow(callback));
        assertThrows(IllegalStateException.class, lease::requireCurrent);
        assertThrows(IllegalStateException.class, handle::cancel);
        lease.restore(); backend.advance(); assertEquals(1, selected.get()); assertEquals(2, unrelated.get());
    }

    @Test void cancellationFailureKeepsCallbacksFrozenAndCleanupCanRetry() {
        var backend = new Backend(); var scheduler = scheduler(backend); AtomicInteger calls = new AtomicInteger();
        scheduler.runTimer(calls::incrementAndGet, 1, 1); var old = backend.tasks.get(1);
        var lease = scheduler.prepare(work -> true); backend.cancelFailures = 1;
        assertThrows(IllegalStateException.class, lease::freeze);
        old.callback.run(); assertEquals(0, calls.get());
        assertThrows(IllegalStateException.class, lease::requireCurrent);
        lease.restore(); backend.advance(); assertEquals(1, calls.get());
        old.callback.run(); assertEquals(1, calls.get());
    }

    @Test void partialRestoreFailureDoesNotReleaseAnyTaskOrDuplicateWorkOnRetry() {
        var backend = new Backend(); var scheduler = scheduler(backend); List<Integer> calls = new ArrayList<>();
        scheduler.runNow(() -> calls.add(1)); scheduler.runNow(() -> calls.add(2));
        var lease = scheduler.prepare(work -> true); lease.freeze(); backend.failAt = backend.submissions + 2;
        assertThrows(IllegalStateException.class, lease::restore);
        assertThrows(IllegalStateException.class, lease::requireCurrent); assertTrue(backend.tasks.isEmpty());
        backend.advance(); assertTrue(calls.isEmpty());
        lease.restore(); backend.advance(); assertEquals(List.of(1, 2), calls); lease.restore(); backend.advance(); assertEquals(List.of(1, 2), calls);
    }

    @Test void failedStagedCancellationRemainsInertUntilCleanupRetry() {
        var backend = new Backend(); var scheduler = scheduler(backend); List<Integer> calls = new ArrayList<>();
        scheduler.runNow(() -> calls.add(1)); scheduler.runNow(() -> calls.add(2));
        var lease = scheduler.prepare(work -> true); lease.freeze();
        backend.failAt = backend.submissions + 2; backend.failCleanup = true;
        var failure = assertThrows(IllegalStateException.class, lease::restore);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(1, backend.tasks.size());
        var abandoned = backend.tasks.values().iterator().next();
        abandoned.callback.run(); assertTrue(calls.isEmpty());
        lease.restore(); abandoned.callback.run(); assertTrue(calls.isEmpty());
        backend.advance(); assertEquals(List.of(1, 2), calls);
        abandoned.callback.run(); assertEquals(List.of(1, 2), calls);
    }

    @Test void aRunningAsyncCallbackRejectsFreezeWithoutCancellingAnything() throws Exception {
        var backend = new Backend(); var scheduler = scheduler(backend);
        var started = new CountDownLatch(1); var finish = new CountDownLatch(1);
        scheduler.runTimerAsync(() -> { started.countDown(); try { assertTrue(finish.await(5, TimeUnit.SECONDS)); } catch (InterruptedException failure) { throw new AssertionError(failure); } }, 1, 1);
        var nativeTask = backend.tasks.get(1); var executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(nativeTask.callback); assertTrue(started.await(5, TimeUnit.SECONDS));
            var lease = scheduler.prepare(work -> true);
            assertThrows(IllegalStateException.class, lease::freeze); assertEquals(0, nativeTask.cancellations);
            lease.restore(); finish.countDown(); running.get(5, TimeUnit.SECONDS);
        } finally { finish.countDown(); executor.shutdownNow(); }
    }

    @Test void discardAndGlobalShutdownNeverResurrectOriginalTasks() {
        var backend = new Backend(); var scheduler = scheduler(backend); AtomicInteger calls = new AtomicInteger();
        var handle = scheduler.runNow(calls::incrementAndGet); var lease = scheduler.prepare(work -> true); lease.freeze(); lease.discard(); lease.restore();
        assertTrue(handle.cancelled()); backend.advance(); assertEquals(0, calls.get());
        scheduler.runNow(calls::incrementAndGet); var next = scheduler.prepare(work -> true); next.freeze(); scheduler.cancelAll(); next.restore();
        backend.advance(); assertEquals(0, calls.get()); assertTrue(backend.tasks.isEmpty());
    }

    @Test void frozenLiveWorkTransfersWithoutNativeHandlesAndRewindsIndependently() {
        var backend = new Backend(); var live = scheduler(backend);
        var callback = new RollbackTaskBindingsTest.Callback();
        PredictionDeterminism.run(71, 93, () -> callback.handle = live.runTimer(callback, 2, 2));
        var completed = live.runNow(() -> {});
        var cancelled = live.runLater(() -> {}, 20); cancelled.cancel();
        backend.advance();
        var lease = live.prepare(work -> work.callback() == callback);
        var capture = lease.freeze();
        var codec = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(
                List.of(RollbackTaskBindings.class, RollbackTaskBindings.Entry.class,
                        RollbackTaskBindings.Handle.class, RollbackTaskBindingsTest.Callback.class),
                List.of(), List.of()), new RollbackGraphCodec.Limits(100, 1000, 100_000, 10_000));
        var roots = codec.decode(codec.encode(List.of(capture.bindings(), completed, cancelled, completed), capture::replacement));
        var imported = (RollbackTaskBindings) roots.getFirst();
        var copy = (RollbackTaskBindingsTest.Callback) imported.entries().getFirst().callback();
        assertSame(roots.get(1), roots.get(3));
        var privateScheduler = new RollbackScheduler(8, 8); imported.install(privateScheduler);
        var newTask = privateScheduler.runLater(() -> {}, 100);
        assertEquals(4, newTask.legacyId(), "completed and cancelled IDs must remain reserved");
        for (int index : List.of(1, 2)) {
            var inactive = (PKTask) roots.get(index);
            assertTrue(inactive.cancelled()); inactive.cancel();
            privateScheduler.cancelTask(inactive.legacyId());
        }
        assertFalse(newTask.cancelled());
        privateScheduler.advance(1);
        var checkpoint = new RollbackStateGraph(value -> value instanceof Thread, field -> true, 200)
                .capture(List.of(privateScheduler, imported), List.of());
        privateScheduler.advance(2); privateScheduler.advance(3);
        assertTrue(copy.handle.cancelled()); assertEquals(2, copy.calls);
        checkpoint.restore(); assertFalse(copy.handle.cancelled()); assertEquals(1, copy.calls);
        privateScheduler.advance(2); privateScheduler.advance(3);
        assertTrue(copy.handle.cancelled()); assertEquals(List.of(71L, 93L, 71L, 93L), copy.observed);
        assertEquals(0, callback.calls); assertFalse(callback.handle.cancelled());
        lease.requireCurrent(); lease.restore(); backend.advance();
        assertEquals(1, callback.calls); assertEquals(2, copy.calls);
    }

    @Test void replayReservationSurvivesWireTransferRewindAndExportWithoutOverlappingLiveWork() {
        var backend = new Backend(); var live = scheduler(backend);
        live.runNow(() -> {});
        var lease = live.prepare(work -> false);
        var captured = lease.freeze(2);
        assertSame(captured, lease.freeze(2));
        assertThrows(IllegalStateException.class, () -> lease.freeze(3));
        assertEquals(4, live.runNow(() -> {}).legacyId());
        var codec = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(
                List.of(RollbackTaskBindings.class, RollbackTaskBindings.Entry.class, RollbackTaskBindings.Handle.class),
                List.of(), List.of()), new RollbackGraphCodec.Limits(100, 1000, 100_000, 10_000));
        var bindings = (RollbackTaskBindings) codec.decode(codec.encode(List.of(captured.bindings()), captured::replacement)).getFirst();
        var replay = new RollbackScheduler(8, 8); bindings.install(replay);
        assertEquals(2, replay.runNow(() -> {}).legacyId()); replay.advance(1);
        var checkpoint = replay.captureRollbackState();
        assertEquals(3, replay.runNow(() -> {}).legacyId());
        assertThrows(IllegalStateException.class, () -> replay.runNow(() -> {}));
        assertEquals(1, replay.pendingTasks());
        replay.restoreRollbackState(checkpoint);
        var exported = replay.exportTasks();
        var nextBindings = (RollbackTaskBindings) codec.decode(codec.encode(List.of(exported.bindings()), exported::replacement)).getFirst();
        var next = new RollbackScheduler(8, 8); nextBindings.install(next);
        assertEquals(3, next.runNow(() -> {}).legacyId());
        assertThrows(IllegalStateException.class, () -> next.runNow(() -> {}));
        lease.restore();
        assertEquals(5, live.runNow(() -> {}).legacyId(), "Aborted reservations are never recycled");
    }

    @Test void logicalIdsNeverCancelUnrelatedNativeTasksAfterRestoration() {
        var backend = new Backend(); backend.next = 100;
        var scheduler = scheduler(backend); AtomicInteger calls = new AtomicInteger();
        var first = scheduler.runNow(calls::incrementAndGet);
        assertEquals(1, first.legacyId());
        var lease = scheduler.prepare(work -> true); lease.freeze(); lease.restore();
        var resumed = backend.tasks.values().iterator().next();
        assertEquals(101, resumed.legacyId());
        scheduler.cancelTask(101); // Not a handle issued by this scheduler.
        assertFalse(resumed.cancelled());
        backend.advance(); assertEquals(1, calls.get());
        var second = scheduler.runNow(calls::incrementAndGet);
        assertEquals(2, second.legacyId());
        scheduler.cancelTask(first.legacyId());
        backend.advance(); assertEquals(2, calls.get());
        backend.failAt = backend.submissions + 1;
        assertThrows(IllegalStateException.class, () -> scheduler.runNow(() -> {}));
        assertEquals(4, scheduler.runNow(() -> {}).legacyId(), "Failed submission must not recycle a logical ID");
    }

    @Test void selectorMutationIsRejectedBeforeOwnershipChanges() {
        var backend = new Backend(); var scheduler = scheduler(backend); AtomicInteger calls = new AtomicInteger(); scheduler.runNow(calls::incrementAndGet);
        var lease = scheduler.prepare(work -> { scheduler.runNow(() -> {}); return true; });
        assertThrows(IllegalStateException.class, lease::freeze); lease.restore(); backend.advance(); assertEquals(1, calls.get());
    }
}
