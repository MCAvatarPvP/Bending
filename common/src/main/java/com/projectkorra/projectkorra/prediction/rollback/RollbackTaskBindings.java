package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.PKTask;
import java.util.List;
import java.util.Objects;

/** Pending callbacks copied with the bending graph, retaining callback/ability/handle aliases.
 * Capture delays relative to the frozen source tick. Source task handles must be projected
 * to the corresponding entry handle by the source graph adapter; never copy native tasks.
 */
public final class RollbackTaskBindings {
    private final List<Entry> entries;
    private final int minimumNextId;
    private boolean installed;

    public RollbackTaskBindings(List<Entry> entries) { this(entries, 1); }
    public RollbackTaskBindings(List<Entry> entries, int minimumNextId) {
        if (minimumNextId < 1) throw new IllegalArgumentException("Task id reservation");
        this.entries = List.copyOf(entries); this.minimumNextId = minimumNextId;
    }
    public List<Entry> entries() { return entries; }

    /** Live adapter input at a frozen tick boundary; never itself placed in the portable graph. */
    public record Pending(PKTask source, Runnable callback, long delay, long period, CoreAbility ability, long action, long seed) {
        public Pending { Objects.requireNonNull(source); Objects.requireNonNull(callback); }
    }

    /** Keeps live-to-portable handle projections outside the transferable root. */
    public static final class Capture {
        private final RollbackTaskBindings bindings;
        private final java.util.IdentityHashMap<Object, RollbackStateTransfer.Replacement> projections = new java.util.IdentityHashMap<>();
        private final java.util.function.Function<Object, Integer> inactiveHandles;
        private Capture(List<Pending> pending, int minimumNextId, java.util.function.Function<Object, Integer> inactiveHandles) {
            this.inactiveHandles = Objects.requireNonNull(inactiveHandles);
            var entries = new java.util.ArrayList<Entry>();
            var ids = new java.util.HashSet<Integer>();
            for (var task : pending) {
                if (task.source().cancelled()) throw new IllegalArgumentException("Captured task is cancelled");
                int id = task.source().legacyId();
                if (!ids.add(id) || projections.containsKey(task.source())) throw new IllegalArgumentException("Duplicate source task");
                var entry = new Entry(id, task.callback(), task.delay(), task.period(), task.ability(), task.action(), task.seed());
                entries.add(entry);
                projections.put(task.source(), RollbackStateTransfer.Replacement.fromProjection(entry.handle()));
            }
            bindings = new RollbackTaskBindings(entries, minimumNextId);
        }
        public RollbackTaskBindings bindings() { return bindings; }
        public RollbackStateTransfer.Replacement replacement(Object value) {
            var found = projections.get(value);
            if (found != null) return found;
            Integer id = inactiveHandles.apply(value);
            if (id == null) return null;
            var replacement = RollbackStateTransfer.Replacement.fromProjection(new Handle(id, true));
            projections.put(value, replacement); return replacement;
        }
        void projectSources(java.util.function.BiConsumer<Object, RollbackStateTransfer.Replacement> target) { projections.forEach(target); }
    }
    /** Supply entries in original scheduling order, including equal-deadline repeating tasks. */
    public static Capture capture(List<Pending> pending) {
        if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Capture source tasks before replay");
        return new Capture(List.copyOf(pending), 1, ignored -> null);
    }

    /** Source adapters reserve past IDs and recognize their own completed/cancelled handles on graph traversal. */
    public static Capture capture(List<Pending> pending, int minimumNextId, java.util.function.Function<Object, Integer> inactiveHandles) {
        if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Capture source tasks before replay");
        return new Capture(List.copyOf(pending), minimumNextId, inactiveHandles);
    }

    /** The private scheduler validates the whole batch before changing membership or handles. */
    public void install(RollbackScheduler scheduler) {
        if (installed) throw new IllegalStateException("Task bindings already installed");
        Objects.requireNonNull(scheduler).importTasks(entries, minimumNextId);
        installed = true;
    }

    /** Ordinary fields intentionally preserve shared identities through the portable graph codec. */
    public static final class Entry {
        private final Handle handle;
        private final Runnable callback;
        private final long delay, period, action, seed;
        private final CoreAbility ability;
        public Entry(int id, Runnable callback, long delay, long period, CoreAbility ability, long action, long seed) {
            this.handle = new Handle(id); this.callback = Objects.requireNonNull(callback);
            this.delay = delay; this.period = period; this.ability = ability; this.action = action; this.seed = seed;
        }
        public Handle handle() { return handle; }
        public Runnable callback() { return callback; }
        public long delay() { return delay; }
        public long period() { return period; }
        public CoreAbility ability() { return ability; }
        public long action() { return action; }
        public long seed() { return seed; }
    }

    /** Project source handle references here before graph encoding; bind only after decoding. */
    public static final class Handle implements PKTask {
        private final int id;
        private final boolean inactive;
        private PKTask delegate;
        private Handle(int id) { this(id, false); }
        private Handle(int id, boolean inactive) {
            if (id < 1 || id == Integer.MAX_VALUE) throw new IllegalArgumentException("Imported task id");
            this.id = id; this.inactive = inactive;
        }
        boolean unbound() { return delegate == null && !inactive; }
        void bind(PKTask task) { delegate = task; }
        private PKTask task() {
            if (delegate == null) throw new IllegalStateException("Imported task is not installed");
            return delegate;
        }
        @Override public void cancel() { if (!inactive) task().cancel(); }
        @Override public boolean cancelled() { return inactive || task().cancelled(); }
        @Override public int legacyId() { return id; }
    }
}
