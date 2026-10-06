package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.PKTask;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RollbackTaskBindingsTest {
    static final class Callback implements Runnable {
        PKTask handle;
        int calls;
        List<Long> observed = new ArrayList<>();
        @Override public void run() {
            calls++; observed.add(PredictionDeterminism.currentAction()); observed.add(PredictionDeterminism.currentSeed());
            if (calls == 2) handle.cancel();
        }
    }

    @Test void transferredCallbacksKeepHandlesContextRelativeDeadlinesAndRewindState() {
        var callback = new Callback();
        callback.handle = new PKTask() {
            @Override public int legacyId() { return 42; }
            @Override public boolean cancelled() { return false; }
            @Override public void cancel() { fail("Private callback reached a live task"); }
        };
        var capture = RollbackTaskBindings.capture(List.of(new RollbackTaskBindings.Pending(callback.handle, callback, 2, 2, null, 71, 93)));
        var source = capture.bindings();
        var codec = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(
                List.of(RollbackTaskBindings.class, RollbackTaskBindings.Entry.class, RollbackTaskBindings.Handle.class, Callback.class),
                List.of(), List.of()), new RollbackGraphCodec.Limits(100, 1000, 100_000, 10_000));
        var imported = (RollbackTaskBindings) codec.decode(codec.encode(List.of(source), capture::replacement)).getFirst();
        var copy = (Callback) imported.entries().getFirst().callback();
        assertNotSame(callback, copy); assertSame(imported.entries().getFirst().handle(), copy.handle);
        var scheduler = new RollbackScheduler(8, 8); imported.install(scheduler);
        assertEquals(43, scheduler.runLater(() -> {}, 100).legacyId());
        scheduler.advance(1); assertEquals(0, copy.calls);
        scheduler.advance(2); assertEquals(List.of(71L, 93L), copy.observed);
        assertEquals(0, callback.calls); assertFalse(callback.handle.cancelled());
        var checkpoint = new RollbackStateGraph(value -> value instanceof Thread, field -> true, 200)
                .capture(List.of(scheduler, imported), List.of());
        scheduler.advance(3); scheduler.advance(4);
        assertTrue(copy.handle.cancelled()); assertEquals(2, copy.calls);
        checkpoint.restore(); assertFalse(copy.handle.cancelled()); assertEquals(1, copy.calls);
        assertEquals(List.of(71L, 93L), copy.observed);
        scheduler.advance(3); scheduler.advance(4); assertTrue(copy.handle.cancelled());
        assertEquals(List.of(71L, 93L, 71L, 93L), copy.observed);
        assertThrows(IllegalStateException.class, () -> imported.install(new RollbackScheduler(8, 8)));
    }

    @Test void exportingSettledReplayTransfersLatestCallbackStateAndRemainingDeadlines() {
        var source = new RollbackScheduler(8, 8); var callback = new Callback();
        PredictionDeterminism.run(71, 93, () -> callback.handle = source.runTimer(callback, 2, 5));
        var completed = source.runNow(() -> {});
        source.advance(1); source.advance(2); source.advance(3);
        assertEquals(1, callback.calls);
        var export = source.exportTasks();
        assertEquals(4, export.bindings().entries().getFirst().delay());
        var codec = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(
                List.of(RollbackTaskBindings.class, RollbackTaskBindings.Entry.class, RollbackTaskBindings.Handle.class, Callback.class),
                List.of(), List.of()), new RollbackGraphCodec.Limits(100, 1000, 100_000, 10_000));
        var roots = codec.decode(codec.encode(List.of(export.bindings(), completed), export::replacement));
        var bindings = (RollbackTaskBindings) roots.getFirst();
        var copy = (Callback) bindings.entries().getFirst().callback();
        var destination = new RollbackScheduler(8, 8); bindings.install(destination);
        assertEquals(3, destination.runLater(() -> {}, 100).legacyId());
        assertTrue(((PKTask) roots.get(1)).cancelled());
        for (int tick = 1; tick <= 3; tick++) destination.advance(tick);
        assertEquals(1, copy.calls); destination.advance(4);
        assertEquals(2, copy.calls); assertTrue(copy.handle.cancelled());
        assertEquals(List.of(71L, 93L, 71L, 93L), copy.observed);
        assertEquals(1, callback.calls); assertFalse(callback.handle.cancelled());
        // An export doesn't retire the source until the external owner finishes handoff.
        for (int tick = 4; tick <= 7; tick++) source.advance(tick);
        assertEquals(2, callback.calls); assertTrue(callback.handle.cancelled());
    }

    @Test void exportRejectsMidCallbackAndPreservesImportedHandleAliasesAcrossAnotherTransfer() {
        var source = new RollbackScheduler(8, 8);
        source.runNow(() -> assertThrows(IllegalStateException.class, source::exportTasks));
        source.advance(1);
        var callback = new Callback();
        var entry = new RollbackTaskBindings.Entry(8, callback, 1, 2, null, 71, 93);
        callback.handle = entry.handle();
        var privateScheduler = new RollbackScheduler(8, 8);
        var original = new RollbackTaskBindings(List.of(entry)); original.install(privateScheduler);
        privateScheduler.advance(1);
        var exported = privateScheduler.exportTasks();
        var codec = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(
                List.of(RollbackTaskBindings.class, RollbackTaskBindings.Entry.class, RollbackTaskBindings.Handle.class, Callback.class),
                List.of(), List.of()), new RollbackGraphCodec.Limits(100, 1000, 100_000, 10_000));
        var copied = (RollbackTaskBindings) codec.decode(codec.encode(List.of(exported.bindings()), exported::replacement)).getFirst();
        var next = new RollbackScheduler(8, 8); copied.install(next);
        next.advance(1); next.advance(2);
        var result = (Callback) copied.entries().getFirst().callback();
        assertEquals(2, result.calls); assertTrue(result.handle.cancelled());
        assertTrue(copied.entries().getFirst().handle().cancelled());
        assertEquals(1, callback.calls); assertFalse(callback.handle.cancelled());
    }

    @Test void privateServiceTimerStaysInSourceWhileGameplayAndInertServiceHandlesTransfer() {
        var source = new RollbackScheduler(8, 8);
        var serviceCalls = new java.util.concurrent.atomic.AtomicInteger();
        var service = source.runServiceTimer(serviceCalls::incrementAndGet, 1, 1);
        var callback = new Callback(); callback.handle = source.runTimer(callback, 1, 1);
        source.advance(1);
        var exported = source.exportTasks();
        assertEquals(1, exported.bindings().entries().size());
        assertEquals(2, source.pendingTasks());
        var codec = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(
                List.of(RollbackTaskBindings.class, RollbackTaskBindings.Entry.class, RollbackTaskBindings.Handle.class, Callback.class),
                List.of(), List.of()), new RollbackGraphCodec.Limits(100, 1000, 100_000, 10_000));
        var copied = codec.decode(codec.encode(List.of(exported.bindings(), service), exported::replacement));
        var bindings = (RollbackTaskBindings) copied.getFirst();
        var destination = new RollbackScheduler(8, 8); bindings.install(destination);
        var retiredService = (PKTask) copied.get(1);
        assertTrue(retiredService.cancelled()); retiredService.cancel();
        assertFalse(service.cancelled());
        destination.advance(1);
        assertEquals(2, ((Callback) bindings.entries().getFirst().callback()).calls);
        assertEquals(1, serviceCalls.get()); assertEquals(1, callback.calls);
        source.advance(2); assertEquals(2, serviceCalls.get());
        assertEquals(3, destination.runNow(() -> {}).legacyId());
    }

    @Test void equalDeadlinesRetainSourceOrderingInsteadOfSortingLegacyIds() {
        List<Integer> calls = new ArrayList<>();
        var first = new RollbackTaskBindings.Entry(30, () -> calls.add(30), 1, 1, null, 0, 0);
        var second = new RollbackTaskBindings.Entry(4, () -> calls.add(4), 1, 1, null, 0, 0);
        var scheduler = new RollbackScheduler(4, 4);
        new RollbackTaskBindings(List.of(first, second)).install(scheduler);
        scheduler.runTimer(() -> calls.add(31), 1, 1);
        scheduler.advance(1); scheduler.advance(2);
        assertEquals(List.of(30, 4, 31, 30, 4, 31), calls);
    }

    @Test void failedBatchDoesNotBindHandlesOrConsumeIds() {
        var first = new RollbackTaskBindings.Entry(4, () -> {}, 0, -1, null, 0, 0);
        var duplicate = new RollbackTaskBindings.Entry(4, () -> {}, 1, 1, null, 0, 0);
        var scheduler = new RollbackScheduler(4, 4);
        assertThrows(IllegalArgumentException.class, () -> new RollbackTaskBindings(List.of(first, duplicate)).install(scheduler));
        assertEquals(0, scheduler.pendingTasks());
        assertThrows(IllegalStateException.class, first.handle()::cancel);
        new RollbackTaskBindings(List.of(first)).install(scheduler);
        assertFalse(first.handle().cancelled()); scheduler.advance(1); assertTrue(first.handle().cancelled());
        assertEquals(5, scheduler.runNow(() -> {}).legacyId());
    }

    @Test void emptyImportIsStillOneBootstrapAndBudgetFailureIsAtomic() {
        var scheduler = new RollbackScheduler(1, 2);
        new RollbackTaskBindings(List.of()).install(scheduler);
        assertThrows(IllegalStateException.class, () -> new RollbackTaskBindings(List.of()).install(scheduler));
        var a = new RollbackTaskBindings.Entry(7, () -> {}, 1, -1, null, 0, 0);
        var b = new RollbackTaskBindings.Entry(8, () -> {}, 1, -1, null, 0, 0);
        var limited = new RollbackScheduler(1, 2);
        assertThrows(IllegalArgumentException.class, () -> new RollbackTaskBindings(List.of(a, b)).install(limited));
        assertEquals(0, limited.pendingTasks()); assertThrows(IllegalStateException.class, a.handle()::cancel);
        new RollbackTaskBindings(List.of(a)).install(limited); assertEquals(1, limited.pendingTasks());
    }
}
