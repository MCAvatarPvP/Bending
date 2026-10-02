package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.PKScheduler;
import com.projectkorra.projectkorra.platform.PKTask;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Simulation scheduler for the existing platform API. Scheduling, cancellation,
 * task handles and callback state rewind together. All callbacks run on the owning
 * simulation thread, including legacy callbacks labelled async. They must access
 * simulation services; external I/O belongs to finalized effects.
 */
public final class RollbackScheduler implements PKScheduler, RollbackStateCell<RollbackScheduler.State> {
    private final Thread owner = Thread.currentThread();
    private final int maximumTasks;
    private final int maximumCallbacks;
    private final Map<Integer, Task> tasks = new LinkedHashMap<>();
    private final PriorityQueue<Task> queue = new PriorityQueue<>(Comparator
            .comparingLong((Task task) -> task.due).thenComparingLong(task -> task.order));
    private int nextId = 1;
    private int maximumNextId = Integer.MAX_VALUE;
    private long nextOrder = 1;
    private long tick;
    private boolean running;
    private boolean failed;
    private boolean imported;

    public RollbackScheduler(int maximumTasks, int maximumCallbacks) {
        if (maximumTasks < 1 || maximumCallbacks < 1) throw new IllegalArgumentException("Scheduler budgets");
        this.maximumTasks = maximumTasks;
        this.maximumCallbacks = maximumCallbacks;
    }

    public long tick() { checkThread(); return tick; }
    public int pendingTasks() { checkThread(); return tasks.size(); }

    /** Called once, before ability progress, for each sequential simulation tick. */
    public void advance(final long nextTick) {
        checkUsable();
        if (running) throw new IllegalStateException("Reentrant simulation scheduler");
        if (nextTick != Math.incrementExact(tick)) throw new IllegalArgumentException("Nonsequential scheduler tick");
        tick = nextTick;
        running = true;
        int callbacks = 0;
        try {
            while (!queue.isEmpty() && queue.peek().due <= tick) {
                if (++callbacks > maximumCallbacks) throw new IllegalStateException("Simulation callback budget exceeded");
                final Task task = queue.remove();
                AbilityExecutionContext.run(task.ability, () -> PredictionDeterminism.run(task.action, task.seed, task.callback));
                if (tasks.get(task.id) != task) continue; // Cancelled by this callback or a nested callback.
                if (task.period > 0) {
                    task.due = Math.addExact(tick, task.period);
                    queue.add(task);
                } else tasks.remove(task.id);
            }
        } catch (RuntimeException | Error exception) {
            failed = true;
            throw exception;
        } finally {
            running = false;
        }
    }

    @Override public PKTask runNow(Runnable task) { return runLater(task, 0); }
    @Override public PKTask runAsync(Runnable task) { return runLater(task, 0); }
    @Override public PKTask runLater(Runnable task, long delayTicks) { return schedule(task, delayTicks, -1); }
    @Override public PKTask runAsyncLater(Runnable task, long delayTicks) { return runLater(task, delayTicks); }
    @Override public PKTask runTimer(Runnable task, long delayTicks, long periodTicks) {
        // Bukkit treats period=0 as one tick and a negative period as non-repeating.
        return schedule(task, delayTicks, periodTicks == 0 ? 1 : periodTicks);
    }
    @Override public PKTask runTimerAsync(Runnable task, long delayTicks, long periodTicks) {
        return runTimer(task, delayTicks, periodTicks);
    }
    @Override public int scheduleRepeating(Runnable task, long delayTicks, long periodTicks) {
        return runTimer(task, delayTicks, periodTicks).legacyId();
    }

    private Task schedule(Runnable callback, long delay, long period) {
        checkUsable();
        Objects.requireNonNull(callback, "callback");
        if (tasks.size() >= maximumTasks) throw new IllegalStateException("Simulation task budget exceeded");
        if (nextId >= maximumNextId) throw new IllegalStateException("Simulation task ID reservation exhausted");
        final int id = nextId;
        final int followingId = Math.incrementExact(id);
        final long due = Math.addExact(tick, Math.max(1, delay));
        final long followingOrder = Math.incrementExact(nextOrder);
        final Task task = new Task(id, callback, due, period, nextOrder);
        tasks.put(id, task);
        queue.add(task);
        nextId = followingId;
        nextOrder = followingOrder;
        return task;
    }

    /** Bootstrap-only batch: retain legacy ids, equal-deadline order and captured execution context. */
    void importTasks(List<RollbackTaskBindings.Entry> entries, int minimumNextId, int maximumNextId) {
        checkUsable();
        if (imported || tick != 0 || nextId != 1 || !tasks.isEmpty() || running)
            throw new IllegalStateException("Task import requires a fresh private scheduler");
        if (entries.size() > maximumTasks) throw new IllegalArgumentException("Imported task budget exceeded");
        if (minimumNextId < 1 || maximumNextId < minimumNextId) throw new IllegalArgumentException("Invalid imported task id reservation");
        var imported = new LinkedHashMap<Integer, Task>();
        int followingId = minimumNextId;
        for (var entry : entries) {
            Objects.requireNonNull(entry, "task entry");
            var handle = Objects.requireNonNull(entry.handle(), "task handle");
            int id = handle.legacyId();
            if (id < 1 || id >= maximumNextId || !handle.unbound() || imported.containsKey(id))
                throw new IllegalArgumentException("Invalid or already bound imported task handle");
            var callback = Objects.requireNonNull(entry.callback(), "task callback");
            if (callback instanceof RollbackCallback portable) portable.validate();
            var task = new Task(id, callback, Math.max(1, entry.delay()),
                    entry.period() == 0 ? 1 : entry.period(), imported.size() + 1L, entry.ability(), entry.action(), entry.seed());
            imported.put(id, task); followingId = Math.max(followingId, Math.incrementExact(id));
        }
        this.maximumNextId = maximumNextId;
        tasks.putAll(imported); queue.addAll(imported.values()); nextId = followingId; nextOrder = entries.size() + 1L; this.imported = true;
        for (var entry : entries) entry.handle().bind(imported.get(entry.handle().legacyId()));
    }

    /**
     * Export remaining work at a settled simulation tick for ownership restoration.
     * Copy these bindings together with the outgoing gameplay graph while still in its
     * domain. The source scheduler stays owned until the destination is fully prepared.
     */
    public RollbackTaskBindings.Capture exportTasks() {
        checkUsable();
        if (running) throw new IllegalStateException("Cannot export a running callback");
        var pending = new ArrayList<RollbackTaskBindings.Pending>();
        for (var task : tasks.values()) {
            pending.add(new RollbackTaskBindings.Pending(task, task.callback,
                    Math.max(1, Math.subtractExact(task.due, tick)), task.period,
                    task.ability, task.action, task.seed));
        }
        return RollbackTaskBindings.captureSimulation(pending, nextId, maximumNextId, value -> {
            checkUsable();
            if (running) throw new IllegalStateException("Cannot copy running scheduler handles");
            if (!(value instanceof RollbackScheduler.Task task) || task.scheduler() != this) return null;
            return tasks.get(task.id) != task ? task.id : null;
        });
    }

    @Override public void cancelTask(int taskId) {
        checkThread();
        final Task task = tasks.remove(taskId);
        if (task != null) queue.remove(task);
    }
    @Override public void cancelAll() {
        checkThread();
        tasks.clear();
        queue.clear();
    }
    @Override public boolean isPrimaryThread() { return Thread.currentThread() == owner; }
    @Override public <T> Future<T> callSync(Callable<T> callback) {
        checkUsable();
        Objects.requireNonNull(callback, "callback");
        try { return new Immediate<>(callback.call(), null); }
        catch (Exception exception) { return new Immediate<>(null, exception); }
    }

    public static final class State {
        private final RollbackScheduler owner;
        private final long tick;
        private final int nextId;
        private final int maximumNextId;
        private final long nextOrder;
        private final boolean imported;
        private final List<SavedTask> tasks;
        private State(RollbackScheduler owner, long tick, int nextId, List<SavedTask> tasks) {
            this.owner = owner;
            this.imported = owner.imported;
            this.tick = tick;
            this.nextId = nextId;
            this.maximumNextId = owner.maximumNextId;
            this.nextOrder = owner.nextOrder;
            this.tasks = List.copyOf(tasks);
        }
    }
    private record SavedTask(Task task, long due) {}

    @Override public State captureRollbackState() {
        checkUsable();
        if (running) throw new IllegalStateException("Cannot checkpoint a running callback");
        final List<SavedTask> saved = new ArrayList<>(tasks.size());
        tasks.values().forEach(task -> saved.add(new SavedTask(task, task.due)));
        return new State(this, tick, nextId, saved);
    }
    @Override public void restoreRollbackState(State state) {
        checkThread();
        if (running) throw new IllegalStateException("Cannot restore a running callback");
        if (state.owner != this) throw new IllegalArgumentException("Snapshot belongs to another scheduler");
        tick = state.tick;
        nextId = state.nextId;
        maximumNextId = state.maximumNextId;
        nextOrder = state.nextOrder;
        imported = state.imported;
        tasks.clear();
        queue.clear();
        for (SavedTask saved : state.tasks) {
            saved.task.due = saved.due;
            tasks.put(saved.task.id, saved.task);
            queue.add(saved.task);
        }
        failed = false;
    }
    @Override public Collection<?> rollbackReferences() { checkThread(); return List.copyOf(tasks.values()); }

    private void checkThread() {
        if (!isPrimaryThread()) throw new IllegalStateException("Simulation scheduler crossed threads");
    }
    private void checkUsable() {
        checkThread();
        if (failed) throw new IllegalStateException("Simulation callback failed");
    }

    private final class Task implements PKTask {
        final int id;
        final long order;
        final Runnable callback;
        final long period;
        final CoreAbility ability;
        final long action;
        final long seed;
        long due;
        Task(int id, Runnable callback, long due, long period, long order) {
            this(id, callback, due, period, order, AbilityExecutionContext.current(), PredictionDeterminism.currentAction(), PredictionDeterminism.currentSeed());
        }
        Task(int id, Runnable callback, long due, long period, long order, CoreAbility ability, long action, long seed) {
            this.order = order;
            this.ability = ability; this.action = action; this.seed = seed;
            this.id = id;
            this.callback = callback;
            this.due = due;
            this.period = period;
        }
        @Override public void cancel() {
            checkThread();
            // IDs rewind; a handle from a discarded branch cannot cancel a new task with the same ID.
            if (tasks.get(id) == this) cancelTask(id);
        }
        @Override public boolean cancelled() { checkThread(); return tasks.get(id) != this; }
        @Override public int legacyId() { return id; }
        RollbackScheduler scheduler() { return RollbackScheduler.this; }
    }

    private record Immediate<T>(T value, Exception failure) implements Future<T> {
        @Override public boolean cancel(boolean interrupt) { return false; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return true; }
        @Override public T get() throws ExecutionException {
            if (failure != null) throw new ExecutionException(failure);
            return value;
        }
        @Override public T get(long timeout, TimeUnit unit) throws ExecutionException { return get(); }
    }
}
