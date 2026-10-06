package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.Platform;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live roster reservation, outside rewindable simulation state. Loaders acquire it
 * before capture and retain it through native and common restoration. This gates
 * common updates; native movement, input, tasks and world access need loader gates too.
 */
public final class RollbackLiveOwnership {
    private static final Map<UUID, Lease> OWNERS = new ConcurrentHashMap<>();
    private RollbackLiveOwnership() { }

    /** Private domains execute their own roster normally; only outside progression is suspended. */
    public static boolean blocks(UUID player) {
        return player != null && !RollbackDomain.active() && OWNERS.containsKey(player);
    }

    /** Return a cleanup owner before acquiring any reservation or native resources. */
    public static Lease prepare(Set<UUID> participants) {
        requireLive();
        var roster = Set.copyOf(participants);
        if (roster.isEmpty() || roster.size() > 128) throw new IllegalArgumentException("Live rollback roster");
        return new Lease(roster);
    }

    public static final class Lease {
        private final Thread owner = Thread.currentThread();
        private final Set<UUID> roster;
        private boolean acquired, released, restoring;
        private Lease(Set<UUID> roster) { this.roster = roster; }
        public Set<UUID> participants() { return roster; }
        public void acquire() {
            boundary();
            if (released || restoring) throw new IllegalStateException("Live rollback reservation is closed or restoring");
            if (acquired) { requireCurrent(); return; }
            synchronized (OWNERS) {
                if (roster.stream().anyMatch(OWNERS::containsKey)) throw new IllegalStateException("Rollback roster overlaps an existing owner");
                roster.forEach(id -> OWNERS.put(id, this));
                acquired = true;
            }
        }
        public void requireCurrent() {
            boundary();
            if (!acquired || released || roster.stream().anyMatch(id -> OWNERS.get(id) != this))
                throw new IllegalStateException("Live rollback reservation changed");
        }
        /**
         * Restore the whole roster before release. On failure keep every participant
         * reserved for retry. The callback must be idempotent and restore all loader
         * state as well as the common graph; no partial roster release is permitted.
         */
        public void restoreAndRelease(Runnable restore) {
            boundary(); Objects.requireNonNull(restore);
            if (restoring) throw new IllegalStateException("Recursive rollback restoration");
            if (released) return;
            if (!acquired) { released = true; return; }
            requireCurrent(); restoring = true;
            try {
                restore.run();
                requireCurrent();
                synchronized (OWNERS) { roster.forEach(id -> OWNERS.remove(id, this)); }
                released = true;
            } finally { restoring = false; }
        }
        private void boundary() {
            requireLive();
            if (Thread.currentThread() != owner) throw new IllegalStateException("Live rollback owner crossed threads");
        }
    }
    private static void requireLive() {
        if (RollbackDomain.active() || RollbackClock.active() || !Platform.scheduler().isPrimaryThread())
            throw new IllegalStateException("Change rollback ownership on the live main thread");
    }
}
