package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.*;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.model.PKAdapter;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/** Platform installed for a private duel on either loader. There is no live-platform fallback. */
public final class RollbackPlatform implements ProjectKorraPlatform, RollbackStateCell<Void> {
    /** Captured server identity, shared by both replicas rather than inferred from their loader. */
    public record Identity(String id, Path dataFolder, Logger logger) {
        public Identity { Objects.requireNonNull(id); Objects.requireNonNull(dataFolder); Objects.requireNonNull(logger); }
    }

    /**
     * Complete private bindings, constructed by the native importer. Registry queries must
     * use agreed definitions, mutations must use owned state, and outputs must be buffered.
     * Referenced mutable state is included in checkpoints; never pass the live platform's services.
     */
    public record Services(Object plugin, PKPlugins plugins, PKTags tags, PKMaterials materials, PKPermissions permissions,
                           PKServer server, PKScoreboards scoreboards, PKBossBars bossBars, PKAdapter adapter) {
        public Services {
            Objects.requireNonNull(plugin); Objects.requireNonNull(plugins); Objects.requireNonNull(tags);
            Objects.requireNonNull(materials); Objects.requireNonNull(permissions); Objects.requireNonNull(server);
            Objects.requireNonNull(scoreboards); Objects.requireNonNull(bossBars); Objects.requireNonNull(adapter);
        }
        private List<?> references() { return List.of(plugin, plugins, tags, materials, permissions, server, scoreboards, bossBars, adapter); }
    }

    private final Thread thread = Thread.currentThread();
    private final Identity identity;
    private final Services services;
    private final RollbackScheduler scheduler;
    private final RollbackEventBus events;
    private final RollbackWorld world;
    private final SortedMap<UUID, RollbackPlayer> roster;
    private final RollbackRosterViews importedViews;
    private final PKPlayers players = new Players();
    private final PKWorlds worlds = new Worlds();
    private final PKChunks chunks = this::chunk;

    public RollbackPlatform(Identity identity, Services services, RollbackScheduler scheduler, RollbackEventBus events,
                            RollbackWorld world, Collection<RollbackPlayer> players) {
        this(identity, services, scheduler, events, world, players, null);
    }
    /** Retain the imported view policies as checkpoint roots along with the native roster. */
    public RollbackPlatform(Identity identity, Services services, RollbackScheduler scheduler, RollbackEventBus events,
                            RollbackRosterViews views) {
        this(identity, services, scheduler, events, views.world(), views.players().values(), views);
    }
    // UUID equality is insufficient: another replica can contain the same UUID in a different owned body.
    @SuppressWarnings("WrapperReferenceEquality")
    private RollbackPlatform(Identity identity, Services services, RollbackScheduler scheduler, RollbackEventBus events,
                             RollbackWorld world, Collection<RollbackPlayer> players, RollbackRosterViews importedViews) {
        this.identity = Objects.requireNonNull(identity); this.services = Objects.requireNonNull(services);
        this.scheduler = Objects.requireNonNull(scheduler); this.events = Objects.requireNonNull(events);
        this.world = Objects.requireNonNull(world);
        var roster = new TreeMap<UUID, RollbackPlayer>();
        var names = new HashSet<String>();
        for (RollbackPlayer player : players) {
            if (player.getWorld() != world || world.entities().get(player.getUniqueId()) != player || !player.isOnline()
                    || roster.putIfAbsent(player.getUniqueId(), player) != null || !names.add(player.getName().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Platform requires the complete private online roster");
            }
        }
        if (roster.size() < 2 || roster.size() > 128 || world.getPlayers().size() != roster.size()) {
            throw new IllegalArgumentException("Private platform roster size/membership");
        }
        this.roster = Collections.unmodifiableSortedMap(roster);
        this.importedViews = importedViews;
    }

    @Override public String id() { checkThread(); return identity.id; }
    @Override public Object pluginHandle() { checkThread(); return services.plugin; }
    @Override public Path dataFolder() { checkThread(); return identity.dataFolder; }
    @Override public Logger logger() { checkThread(); return identity.logger; }
    @Override public RollbackScheduler scheduler() { checkThread(); return scheduler; }
    @Override public RollbackEventBus events() { checkThread(); return events; }
    @Override public PKPlayers players() { checkThread(); return players; }
    @Override public PKWorlds worlds() { checkThread(); return worlds; }
    @Override public PKPlugins plugins() { checkThread(); return services.plugins; }
    @Override public PKTags tags() { checkThread(); return services.tags; }
    @Override public PKMaterials materials() { checkThread(); return services.materials; }
    @Override public PKPermissions permissions() { checkThread(); return services.permissions; }
    @Override public PKServer server() { checkThread(); return services.server; }
    @Override public PKScoreboards scoreboards() { checkThread(); return services.scoreboards; }
    @Override public PKBossBars bossBars() { checkThread(); return services.bossBars; }
    @Override public PKChunks chunks() { checkThread(); return chunks; }
    @Override public PKAdapter adapter() { checkThread(); return services.adapter; }

    private final class Players implements PKPlayers {
        @SuppressWarnings("unchecked") @Override public <P> Collection<P> onlinePlayers() {
            checkThread(); return (Collection<P>) roster.values().stream().filter(RollbackPlayer::isOnline).toList();
        }
        @SuppressWarnings("unchecked") @Override public <P> P getPlayer(UUID id) {
            checkThread(); RollbackPlayer player = roster.get(Objects.requireNonNull(id));
            return player != null && player.isOnline() ? (P) player : null;
        }
        @SuppressWarnings("unchecked") @Override public <P> P getPlayer(String name) {
            checkThread(); Objects.requireNonNull(name);
            return (P) roster.values().stream().filter(player -> player.isOnline() && player.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
        }
        @SuppressWarnings("unchecked") @Override public <P> P getOfflinePlayer(UUID id) { checkThread(); return (P) roster.get(Objects.requireNonNull(id)); }
        @SuppressWarnings("unchecked") @Override public <P> P getOfflinePlayer(String name) {
            checkThread(); Objects.requireNonNull(name);
            return (P) roster.values().stream().filter(player -> player.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
        }
    }
    private final class Worlds implements PKWorlds {
        @SuppressWarnings("unchecked") @Override public <W> Collection<W> worlds() { checkThread(); return (Collection<W>) List.of(world); }
    }
    private CompletableFuture<?> chunk(Object value) {
        checkThread();
        if (!(value instanceof Location location) || location.getWorld() != world) throw new IllegalArgumentException("Foreign rollback chunk location");
        int x = Math.floorDiv(location.getBlockX(), 16), z = Math.floorDiv(location.getBlockZ(), 16);
        if (!world.isChunkLoaded(x, z)) throw new IllegalStateException("Chunk is outside the captured arena");
        // Reversion callbacks run synchronously over an already captured chunk. Never load a live chunk.
        return CompletableFuture.completedFuture(new RollbackWorld.Chunk(x, z));
    }
    @Override public Void captureRollbackState() { checkThread(); return null; }
    @Override public void restoreRollbackState(Void state) { checkThread(); }
    @Override public Collection<?> rollbackReferences() {
        checkThread();
        var references = new ArrayList<Object>(services.references());
        references.add(scheduler); references.add(events); references.add(world); references.addAll(roster.values());
        if (importedViews != null) references.add(importedViews);
        return List.copyOf(references);
    }
    private void checkThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Rollback platform crossed threads");
    }
}
