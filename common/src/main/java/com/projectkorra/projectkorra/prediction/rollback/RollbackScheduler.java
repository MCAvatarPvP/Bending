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
            .comparingLong((Task task) -> task.due).thenComparingInt(task -> task.id));
    private int nextId = 1;
    private long tick;
    private boolean running;
    private boolean failed;

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
        final int id = nextId;
        final int followingId = Math.incrementExact(id);
        final long due = Math.addExact(tick, Math.max(1, delay));
        final Task task = new Task(id, callback, due, period);
        tasks.put(id, task);
        queue.add(task);
        nextId = followingId;
        return task;
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
        private final List<SavedTask> tasks;
        private State(RollbackScheduler owner, long tick, int nextId, List<SavedTask> tasks) {
            this.owner = owner;
            this.tick = tick;
            this.nextId = nextId;
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
        final Runnable callback;
        final long period;
        final CoreAbility ability = AbilityExecutionContext.current();
        final long action = PredictionDeterminism.currentAction();
        final long seed = PredictionDeterminism.currentSeed();
        long due;
        Task(int id, Runnable callback, long due, long period) {
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
