package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * Stages intrinsic native roster components before writes. The loader must retain
 * native ownership and arrange spatial tracking/network resynchronization around
 * this commit; it does not acquire ownership or restore world/service state.
 */
public final class PaperRollbackRosterComponents {
    private PaperRollbackRosterComponents() { }

    public static Prepared prepare(Map<UUID, ServerPlayer> targets, RollbackRosterData outgoing, long initialNanos) {
        boundary();
        var roster = Collections.unmodifiableMap(new TreeMap<>(targets));
        if (!roster.keySet().equals(outgoing.players().keySet()))
            throw new IllegalArgumentException("Native component roster changed");
        var guards = new ArrayList<Runnable>();
        var writes = new ArrayList<Runnable>();
        var world = roster.values().iterator().next().level();
        for (var entry : roster.entrySet()) {
            var player = Objects.requireNonNull(entry.getValue());
            var seed = outgoing.players().get(entry.getKey());
            var identity = seed.identity();
            if (!entry.getKey().equals(player.getUUID()) || identity.entityId() != player.getId() || player.level() != world)
                throw new IllegalArgumentException("Native component target identity/world changed");
            var connection = player.connection;
            var wrapper = player.getBukkitEntity();
            guards.add(() -> {
                if (!identity.id().equals(player.getUUID()) || identity.entityId() != player.getId()
                        || player.level() != world || player.connection != connection || player.getBukkitEntity() != wrapper)
                    throw new IllegalStateException("Native component target changed before commit");
            });
            writes.add(PaperRollbackPlayerSeed.prepareValues(player, seed.values()));
            writes.add(PaperRollbackPlayerVitals.prepare(player, seed.vitals()));
            writes.add(PaperRollbackPlayerItems.prepare(player, seed.items()));
            writes.add(PaperRollbackPlayerContextData.prepare(player, seed.context(), initialNanos));
            writes.add(new PaperRollbackCombatSeed(seed.combat()).prepare(player, roster));
        }
        return new Prepared(guards, writes);
    }

    public static final class Prepared {
        private final Thread owner = Thread.currentThread();
        private final List<Runnable> guards, writes;
        private int completed;
        private boolean committing;
        private Prepared(List<Runnable> guards, List<Runnable> writes) {
            this.guards = List.copyOf(guards); this.writes = List.copyOf(writes);
        }
        public void validate() {
            boundary();
            if (Thread.currentThread() != owner) throw new IllegalStateException("Native component commit crossed threads");
            guards.forEach(Runnable::run);
        }
        /** Retry after failure only while the loader still holds the entire native roster. */
        public void commit() {
            validate();
            if (committing) throw new IllegalStateException("Recursive native component commit");
            committing = true;
            try {
                while (completed < writes.size()) {
                    writes.get(completed).run();
                    completed++;
                }
            } finally { committing = false; }
        }
    }
    private static void boundary() {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Native component staging/commit requires the live tick boundary");
    }
}
