package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.*;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Tracks platform tasks so startup can freeze/copy them and restore an aborted handoff.
 * The backend must queue callbacks, never execute them synchronously inside schedule.
 * Leases cover tasks selected by the complete gameplay capture; no ability-name filter.
 */
public final class RollbackLiveScheduler implements PKScheduler {
    public record Work(Runnable callback, CoreAbility ability, boolean asynchronous, long action, long seed) { }
    private final PKScheduler backend;
    private final LongSupplier clock;
    private final Function<Runnable, Runnable> context;
    private final Map<Integer, Task> tasks = new LinkedHashMap<>();
    private final List<Lease> leases = new ArrayList<>();
    private boolean selecting;
    private int highestId;

    public RollbackLiveScheduler(PKScheduler backend, LongSupplier clock, Function<Runnable, Runnable> context) {
        this.backend = Objects.requireNonNull(backend); this.clock = Objects.requireNonNull(clock); this.context = Objects.requireNonNull(context);
    }
    @Override public PKTask runNow(Runnable task) { return schedule(task, 0, -1, false); }
    @Override public PKTask runAsync(Runnable task) { return schedule(task, 0, -1, true); }
    @Override public PKTask runLater(Runnable task, long delay) { return schedule(task, delay, -1, false); }
    @Override public PKTask runAsyncLater(Runnable task, long delay) { return schedule(task, delay, -1, true); }
    @Override public PKTask runTimer(Runnable task, long delay, long period) { return schedule(task, delay, period == 0 ? 1 : period, false); }
    @Override public PKTask runTimerAsync(Runnable task, long delay, long period) { return schedule(task, delay, period == 0 ? 1 : period, true); }
    @Override public int scheduleRepeating(Runnable task, long delay, long period) { return runTimer(task, delay, period).legacyId(); }

    private synchronized PKTask schedule(Runnable callback, long delay, long period, boolean asynchronous) {
        mutation(); Objects.requireNonNull(callback);
        var work = new Work(callback, AbilityExecutionContext.current(), asynchronous, PredictionDeterminism.currentAction(), PredictionDeterminism.currentSeed());
        for (var lease : leases) if (matches(lease.selector, work)) {
            lease.invalidated = true;
            throw new IllegalStateException("Live task creation crossed frozen rollback ownership");
        }
        var task = new Task(work, Objects.requireNonNull(context.apply(callback)), period);
        task.due = Math.addExact(clock.getAsLong(), Math.max(0, delay));
        task.id = Math.incrementExact(highestId);
        if (task.id == Integer.MAX_VALUE) throw new IllegalStateException("Live task ID space exhausted");
        // Logical handles belong to this scheduler, not to the backend queue.
        // Retire IDs even when submission fails: the backend may have queued work.
        highestId = task.id;
        task.generation = new Object();
        try { task.nativeTask = submit(task, task.generation, delay); }
        catch (RuntimeException | Error failure) { task.cancelled = true; throw failure; }
        tasks.put(task.id, task);
        return task;
    }
    private PKTask submit(Task task, Object generation, long delay) {
        Runnable dispatch = () -> execute(task, generation);
        if (task.period > 0) return task.work.asynchronous()
                ? backend.runTimerAsync(dispatch, delay, task.period) : backend.runTimer(dispatch, delay, task.period);
        return task.work.asynchronous() ? backend.runAsyncLater(dispatch, delay) : backend.runLater(dispatch, delay);
    }
    private void execute(Task task, Object generation) {
        synchronized (this) {
            if (task.generation != generation || task.cancelled || task.complete || task.frozen != null) return;
            if (task.period > 0) task.due = Math.addExact(clock.getAsLong(), task.period);
            task.running++;
        }
        try { task.contextual.run(); }
        finally {
            synchronized (this) {
                task.running--;
                if (task.period <= 0) { task.complete = true; tasks.remove(task.id, task); }
            }
        }
    }
    @Override public synchronized void cancelTask(int id) {
        mutation(); var task = tasks.get(id);
        if (task != null) task.cancel();
    }
    @Override public synchronized void cancelAll() {
        mutation();
        for (var task : tasks.values()) task.cancelled = true;
        tasks.clear();
        for (var lease : leases) lease.invalidated = true;
        backend.cancelAll();
    }
    @Override public <T> Future<T> callSync(Callable<T> task) { live(); return backend.callSync(task); }
    @Override public boolean isPrimaryThread() { return backend.isPrimaryThread(); }

    /** Returns the cleanup owner before any native cancellation. Call freeze at the capture boundary. */
    public synchronized Lease prepare(Predicate<Work> selector) {
        primary(); mutation(); return new Lease(Objects.requireNonNull(selector));
    }
    public final class Lease {
        private final Predicate<Work> selector;
        private List<Suspended> selected = List.of();
        private final List<PKTask> cleanup = new ArrayList<>();
        private RollbackTaskBindings.Capture capture;
        private int reservedCapacity;
        private boolean frozen, acquired, closed, invalidated;
        private Lease(Predicate<Work> selector) { this.selector = selector; }

        /** Repeated calls can finish cancellation after a backend failure; copied state is not exposed early. */
        public RollbackTaskBindings.Capture freeze() { return freezeInternal(0); }
        /** Reserve a bounded range for replay-created tasks, disjoint from subsequent live work. */
        public RollbackTaskBindings.Capture freeze(int newTaskCapacity) {
            if (newTaskCapacity < 1) throw new IllegalArgumentException("Task ID reservation capacity");
            return freezeInternal(newTaskCapacity);
        }
        private RollbackTaskBindings.Capture freezeInternal(int newTaskCapacity) {
            synchronized (RollbackLiveScheduler.this) {
                primary(); mutation();
                if (closed || invalidated) throw new IllegalStateException("Task ownership is no longer current");
                if (frozen && reservedCapacity != newTaskCapacity) throw new IllegalStateException("Task reservation changed during freeze retry");
                if (!frozen) {
                    long now = clock.getAsLong();
                    var chosen = new ArrayList<Suspended>(); var pending = new ArrayList<RollbackTaskBindings.Pending>();
                    for (var task : tasks.values()) {
                        if (task.cancelled || task.complete || (task.frozen == null && task.nativeTask.cancelled())) continue;
                        if (!matches(selector, task.work)) continue;
                        if (task.running != 0 || task.frozen != null) throw new IllegalStateException("Selected task is running or already owned");
                        long delay = Math.max(1, Math.subtractExact(task.due, now));
                        chosen.add(new Suspended(task, delay));
                        pending.add(new RollbackTaskBindings.Pending(task, task.work.callback(), delay, task.period,
                                task.work.ability(), task.work.action(), task.work.seed()));
                    }
                    int first = Math.incrementExact(highestId);
                    int limit = newTaskCapacity == 0 ? Integer.MAX_VALUE : Math.addExact(first, newTaskCapacity);
                    capture = RollbackTaskBindings.captureReserved(pending, first, limit, RollbackLiveScheduler.this::inactiveHandle);
                    if (newTaskCapacity != 0) highestId = limit - 1;
                    reservedCapacity = newTaskCapacity;
                    selected = List.copyOf(chosen); frozen = true; leases.add(this);
                    for (var item : selected) item.task.frozen = this;
                }
                for (var item : selected) item.task.nativeTask.cancel();
                acquired = true;
                return capture;
            }
        }
        public void requireCurrent() {
            synchronized (RollbackLiveScheduler.this) {
                primary();
                if (!acquired || closed || invalidated) throw new IllegalStateException("Frozen task ownership changed");
            }
        }
        /** Abort/pre-start cleanup: resume the original callbacks with paused relative deadlines. Retry on failure. */
        public void restore() {
            synchronized (RollbackLiveScheduler.this) {
                primary(); mutation(); if (closed) return;
                if (!frozen) { closed = true; return; }
                invalidated = true;
                cleanStaged();
                for (var item : selected) item.task.nativeTask.cancel();
                var staged = new ArrayList<Resumed>();
                long now = clock.getAsLong();
                try {
                    for (var item : selected) if (!item.task.cancelled) {
                        long due = Math.addExact(now, item.delay);
                        Object generation = new Object();
                        PKTask nativeTask = submit(item.task, generation, item.delay);
                        staged.add(new Resumed(item.task, nativeTask, generation, due));
                    }
                } catch (RuntimeException | Error failure) {
                    for (var item : staged) cleanup.add(item.nativeTask);
                    try { cleanStaged(); } catch (RuntimeException | Error cancellation) { failure.addSuppressed(cancellation); }
                    throw failure;
                }
                for (var item : staged) {
                    item.task.nativeTask = item.nativeTask; item.task.generation = item.generation; item.task.due = item.due;
                }
                for (var item : selected) item.task.frozen = null;
                finish();
            }
        }
        /** Running-session handoff: discard original callbacks only after replacement state owns their work. */
        public void discard() {
            synchronized (RollbackLiveScheduler.this) {
                primary(); mutation(); if (closed) return;
                invalidated = true;
                cleanStaged();
                for (var item : selected) {
                    item.task.nativeTask.cancel();
                    item.task.cancelled = true; tasks.remove(item.task.id, item.task);
                }
                for (var item : selected) item.task.frozen = null;
                finish();
            }
        }
        private void cleanStaged() {
            var iterator = cleanup.iterator();
            while (iterator.hasNext()) { iterator.next().cancel(); iterator.remove(); }
        }
        private void finish() { leases.remove(this); closed = true; }
    }
    private record Suspended(Task task, long delay) { }
    private record Resumed(Task task, PKTask nativeTask, Object generation, long due) { }
    private final class Task implements PKTask {
        final Work work; final Runnable contextual; final long period;
        PKTask nativeTask; Object generation; Lease frozen;
        int id, running; long due; boolean cancelled, complete;
        Task(Work work, Runnable contextual, long period) { this.work = work; this.contextual = contextual; this.period = period; }
        @Override public void cancel() {
            synchronized (RollbackLiveScheduler.this) {
                mutation();
                if (frozen != null) { frozen.invalidated = true; throw new IllegalStateException("Live cancellation crossed frozen rollback ownership"); }
                cancelled = true; nativeTask.cancel(); tasks.remove(id, this);
            }
        }
        @Override public boolean cancelled() {
            synchronized (RollbackLiveScheduler.this) { live(); return cancelled || (frozen == null && nativeTask.cancelled()); }
        }
        @Override public int legacyId() { return id; }
        RollbackLiveScheduler scheduler() { return RollbackLiveScheduler.this; }
    }
    private synchronized Integer inactiveHandle(Object value) {
        primary();
        if (!(value instanceof RollbackLiveScheduler.Task task) || task.scheduler() != this) return null;
        return task.cancelled || task.complete || (task.frozen == null && task.nativeTask.cancelled()) ? task.id : null;
    }
    private boolean matches(Predicate<Work> selector, Work work) {
        selecting = true;
        try { return selector.test(work); } finally { selecting = false; }
    }
    private void mutation() { live(); if (selecting) throw new IllegalStateException("Task selection must not mutate the scheduler"); }
    private void primary() { live(); if (!backend.isPrimaryThread()) throw new IllegalStateException("Task ownership requires the live main thread"); }
    private static void live() {
        if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Live scheduler cannot be used during replay");
    }
}
