package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;

import java.util.*;
import java.util.function.Consumer;

/**
 * Plugin-owned gate for world ticks only; the connection's doTick path needs a separate owner.
 * Acquire/release at a server tick boundary, before the world's entity iteration begins.
 * The loader must intercept riding/world changes and stop the whole session before they occur.
 */
public final class PaperRollbackEntityTickGate {
    private static final java.lang.reflect.Field TICKS;
    static {
        try {
            TICKS = ServerLevel.class.getDeclaredField("entityTickList");
            if (TICKS.getType() != EntityTickList.class || java.lang.reflect.Modifier.isStatic(TICKS.getModifiers()))
                throw new IllegalStateException("World tick-list field changed");
            TICKS.setAccessible(true);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static EntityTickList ticks(ServerLevel world) {
        try { return (EntityTickList) TICKS.get(world); }
        catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot read world tick list", failure); }
    }
    private static void ticks(ServerLevel world, EntityTickList value) {
        try { TICKS.set(world, value); }
        catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot replace world tick list", failure); }
    }
    private PaperRollbackEntityTickGate() { }

    /** Preparation does not change the world's list. The caller must retain this owner through cleanup. */
    public static Lease prepare(Collection<ServerPlayer> players) {
        boundary();
        if (players.isEmpty() || players.size() > 128) throw new IllegalArgumentException("Native tick roster");
        var roster = new LinkedHashMap<UUID, ServerPlayer>();
        ServerLevel world = null;
        for (var player : players) {
            if (roster.putIfAbsent(player.getUUID(), player) != null) throw new IllegalArgumentException("Duplicate native tick player");
            if (world == null) world = player.level();
            else if (world != player.level()) throw new IllegalArgumentException("Native tick roster crosses worlds");
        }
        return new Lease(world, List.copyOf(roster.values()));
    }

    public static final class Lease {
        private final Thread owner = Thread.currentThread();
        private final ServerLevel world;
        private final List<ServerPlayer> roster;
        private Gate gate;
        private boolean released, restoring;
        private Lease(ServerLevel world, List<ServerPlayer> roster) { this.world = world; this.roster = roster; }
        public void acquire() {
            checkThread();
            if (released || restoring) throw new IllegalStateException("Native tick owner is closed/restoring");
            if (gate != null) { requireCurrent(); return; }
            var current = Objects.requireNonNull(ticks(world), "World tick list");
            var candidate = current instanceof Gate existing ? existing : new Gate(current);
            for (var player : roster) {
                if (player.level() != world || player.isPassenger() || !player.getPassengers().isEmpty()
                        || !candidate.contains(player) || candidate.owners.containsKey(player))
                    throw new IllegalStateException("Native tick roster changed or overlaps an owner");
            }
            if (current != candidate) ticks(world, candidate);
            gate = candidate;
            roster.forEach(player -> gate.owners.put(player, this));
        }
        public void requireCurrent() {
            checkThread();
            if (released || gate == null || ticks(world) != gate
                    || roster.stream().anyMatch(player -> gate.owners.get(player) != this))
                throw new IllegalStateException("Native world tick ownership changed");
        }
        /** Failure retains the complete world-tick reservation; cleanup must be idempotent. */
        public void restoreAndRelease(Runnable restore) {
            checkThread(); Objects.requireNonNull(restore);
            if (restoring) throw new IllegalStateException("Recursive native tick cleanup");
            if (released) return;
            if (gate == null) { released = true; return; }
            requireCurrent(); restoring = true;
            try {
                restore.run();
                requireCurrent();
                if (gate.owners.size() == roster.size()) ticks(world, gate.delegate);
                roster.forEach(gate.owners::remove);
                released = true;
            } finally { restoring = false; }
        }
        private void checkThread() {
            boundary();
            if (Thread.currentThread() != owner) throw new IllegalStateException("Native tick ownership crossed threads");
        }
    }

    private static final class Gate extends EntityTickList {
        private final EntityTickList delegate;
        private final IdentityHashMap<Entity, Lease> owners = new IdentityHashMap<>();
        private Gate(EntityTickList delegate) { this.delegate = delegate; }
        @Override public void add(Entity entity) { delegate.add(entity); }
        @Override public void remove(Entity entity) { delegate.remove(entity); }
        @Override public boolean contains(Entity entity) { return delegate.contains(entity); }
        @Override public void forEach(Consumer<Entity> action) {
            delegate.forEach(entity -> { if (!owners.containsKey(entity)) action.accept(entity); });
        }
    }

    private static void boundary() {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Change native tick ownership on the live server tick thread");
    }
}
