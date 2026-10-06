package com.projectkorra.projectkorra.prediction.rollback;

import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Live events that must restore the entire enrolled duel before native mutation. */
public final class PaperRollbackLifecycle implements Listener, AutoCloseable {
    private final Map<UUID, Lease> owners = new HashMap<>();
    private final BooleanSupplier primaryThread;
    private boolean closing, closed, installed;
    public PaperRollbackLifecycle() { this(Bukkit::isPrimaryThread); }
    PaperRollbackLifecycle(BooleanSupplier primaryThread) { this.primaryThread = Objects.requireNonNull(primaryThread); }
    public void install(JavaPlugin plugin) {
        boundary();
        if (installed || closed || closing) throw new IllegalStateException("Lifecycle listener already installed/closed");
        Bukkit.getPluginManager().registerEvents(this, plugin); installed = true;
    }
    /** Reserve before acquiring native state; retain this handle until whole-duel restoration succeeds. */
    public Lease reserve(Set<UUID> players, Runnable stop) {
        boundary();
        var roster = Set.copyOf(players); Objects.requireNonNull(stop);
        if (closing || closed || roster.isEmpty() || roster.size() > 128 || roster.stream().anyMatch(owners::containsKey))
            throw new IllegalStateException("Lifecycle roster unavailable");
        var lease = new Lease(roster, stop); roster.forEach(player -> owners.put(player, lease)); return lease;
    }
    public final class Lease {
        private final Set<UUID> roster;
        private final Runnable stop;
        private boolean stopping, released;
        private Lease(Set<UUID> roster, Runnable stop) { this.roster = roster; this.stop = stop; }
        /** Call only after native/common/terrain restoration and release have succeeded. */
        public void release() {
            boundary();
            if (released) return;
            if (roster.stream().anyMatch(player -> owners.get(player) != this)) throw new IllegalStateException("Lifecycle ownership changed");
            roster.forEach(player -> owners.remove(player, this)); released = true;
        }
        private void stop() {
            if (released) return;
            if (stopping) throw new IllegalStateException("Recursive lifecycle mutation during restoration");
            stopping = true;
            try {
                stop.run();
                if (!released) throw new IllegalStateException("Lifecycle teardown did not release the whole roster");
            } finally { stopping = false; }
        }
    }
    private void before(Cancellable event, UUID... players) {
        if (RollbackDomain.active() || RollbackClock.active()) return;
        try { stopPlayers(players); }
        catch (RuntimeException | Error failure) { event.setCancelled(true); throw failure; }
    }
    private void stopPlayers(UUID... players) {
        if (RollbackDomain.active() || RollbackClock.active()) return;
        boundary();
        var selected = new LinkedHashSet<Lease>();
        for (UUID player : players) { var lease = owners.get(player); if (lease != null) selected.add(lease); }
        for (var lease : selected) lease.stop();
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) { before(event, event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { before(event, event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void mount(EntityMountEvent event) { before(event, event.getEntity().getUniqueId(), event.getMount().getUniqueId()); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void dismount(EntityDismountEvent event) { before(event, event.getEntity().getUniqueId(), event.getDismounted().getUniqueId()); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void kick(PlayerKickEvent event) { before(event, event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void quit(PlayerQuitEvent event) { stopPlayers(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void changedWorld(PlayerChangedWorldEvent event) { stopPlayers(event.getPlayer().getUniqueId()); }
    @Override public void close() {
        boundary();
        if (closed) return;
        closing = true;
        Throwable failure = null;
        for (var lease : new LinkedHashSet<>(owners.values())) {
            try { lease.stop(); }
            catch (RuntimeException | Error problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (installed) HandlerList.unregisterAll(this);
        closed = true;
    }
    private void boundary() {
        if (!primaryThread.getAsBoolean() || RollbackDomain.active() || RollbackClock.active())
            throw new IllegalStateException("Lifecycle ownership requires the live server thread");
    }
}
