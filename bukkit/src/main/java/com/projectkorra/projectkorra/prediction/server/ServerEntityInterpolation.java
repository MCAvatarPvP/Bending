package com.projectkorra.projectkorra.prediction.server;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;

/**
 * Replays the exact remote-player movement packets sent to each viewer through
 * vanilla's client interpolation rules. This is collision view state only; it
 * does not use ping, history, rewind, or sampled Bukkit movement.
 */
public final class ServerEntityInterpolation implements Runnable {
    private static volatile ServerEntityInterpolation active;

    private final JavaPlugin plugin;
    private final Map<UUID, ViewerState> viewers = new HashMap<>();
    private final Map<UUID, Set<Integer>> packetPlayerIds = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<PacketUpdate> pending = new ConcurrentLinkedQueue<>();
    private BukkitTask task;

    private ServerEntityInterpolation(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public static ServerEntityInterpolation start(final JavaPlugin plugin) {
        final ServerEntityInterpolation interpolation = new ServerEntityInterpolation(plugin);
        active = interpolation;
        interpolation.scheduleTicker();
        return interpolation;
    }

    public void stop() {
        if (this.task != null) this.task.cancel();
        this.pending.clear();
        this.viewers.clear();
        this.packetPlayerIds.clear();
        if (active == this) active = null;
    }

    /** Restores this ticker after a ProjectKorra reload cancels plugin tasks. */
    public static void schedulerReset() {
        final ServerEntityInterpolation interpolation = active;
        if (interpolation != null) interpolation.scheduleTicker();
    }

    private void scheduleTicker() {
        if (this.task != null && !this.task.isCancelled()) return;
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this, 1L, 1L);
    }

    void spawn(final UUID viewer, final int entityId, final UUID target,
               final double x, final double y, final double z) {
        this.packetPlayerIds.computeIfAbsent(viewer, ignored -> ConcurrentHashMap.newKeySet())
                .add(entityId);
        this.pending.add(new Spawn(viewer, entityId, target, x, y, z));
    }

    boolean tracksPlayerPacket(final UUID viewer, final int entityId) {
        final Set<Integer> entityIds = this.packetPlayerIds.get(viewer);
        return entityIds != null && entityIds.contains(entityId);
    }

    void relativeMove(final UUID viewer, final int entityId,
                      final double x, final double y, final double z) {
        this.pending.add(new RelativeMove(viewer, entityId, x, y, z));
    }

    void positionSync(final UUID viewer, final int entityId,
                      final double x, final double y, final double z) {
        this.pending.add(new PositionSync(viewer, entityId, x, y, z));
    }

    void teleport(final UUID viewer, final int entityId,
                  final double x, final double y, final double z,
                  final boolean relativeX, final boolean relativeY, final boolean relativeZ) {
        this.pending.add(new Teleport(viewer, entityId, x, y, z,
                relativeX, relativeY, relativeZ));
    }

    void destroy(final UUID viewer, final int[] entityIds) {
        final Set<Integer> tracked = this.packetPlayerIds.get(viewer);
        if (tracked == null || tracked.isEmpty()) return;
        final int[] players = new int[entityIds.length];
        int index = 0;
        for (int entityId : entityIds) if (tracked.remove(entityId)) players[index++] = entityId;
        if (index == 0) return;
        if (index != players.length) {
            final int[] compact = new int[index];
            System.arraycopy(players, 0, compact, 0, index);
            this.pending.add(new Destroy(viewer, compact));
        } else {
            this.pending.add(new Destroy(viewer, players));
        }
    }

    @Override
    public void run() {
        prepareViewers();

        PacketUpdate update;
        while ((update = this.pending.poll()) != null) apply(update);

        this.viewers.values().forEach(state ->
                state.byTarget.values().forEach(tracked -> tracked.position.tick()));
    }

    private void prepareViewers() {
        final Iterator<Map.Entry<UUID, ViewerState>> iterator = this.viewers.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, ViewerState> entry = iterator.next();
            final Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null) {
                iterator.remove();
                this.packetPlayerIds.remove(entry.getKey());
                continue;
            }
            final UUID world = viewer.getWorld().getUID();
            final ViewerState state = entry.getValue();
            if (state.world != null && !state.world.equals(world)) {
                state.clear();
                final Set<Integer> packetIds = this.packetPlayerIds.get(entry.getKey());
                if (packetIds != null) packetIds.clear();
            }
            state.world = world;
        }
    }

    private void apply(final PacketUpdate update) {
        final ViewerState state = this.viewers.computeIfAbsent(update.viewer(), ignored -> new ViewerState());
        if (update instanceof Spawn spawn) {
            this.packetPlayerIds.computeIfAbsent(spawn.viewer, ignored -> ConcurrentHashMap.newKeySet())
                    .add(spawn.entityId);
            state.spawn(spawn.entityId, spawn.target,
                    new VanillaRemotePlayerPosition(spawn.x, spawn.y, spawn.z));
        } else if (update instanceof RelativeMove move) {
            final TrackedPlayer tracked = state.byEntityId.get(move.entityId);
            if (tracked != null) tracked.position.relativeMove(move.x, move.y, move.z);
        } else if (update instanceof PositionSync sync) {
            final TrackedPlayer tracked = state.byEntityId.get(sync.entityId);
            if (tracked != null) tracked.position.positionSync(sync.x, sync.y, sync.z);
        } else if (update instanceof Teleport teleport) {
            final TrackedPlayer tracked = state.byEntityId.get(teleport.entityId);
            if (tracked != null) tracked.position.teleport(teleport.x, teleport.y, teleport.z,
                    teleport.relativeX, teleport.relativeY, teleport.relativeZ);
        } else if (update instanceof Destroy destroy) {
            for (int entityId : destroy.entityIds) state.removeEntity(entityId);
        }
    }

    /** Returns the active caster's current client-rendered remote position. */
    public static Location location(final Player target, final Location actual) {
        final ServerEntityInterpolation service = active;
        if (service == null || target == null || actual == null) return actual;
        final TrackedPlayer tracked = service.trackedPlayer(target, AbilityExecutionContext.current());
        if (tracked == null) return actual;

        final Location serverPosition = target.getLocation();
        return actual.clone().add(tracked.position.x() - serverPosition.getX(),
                tracked.position.y() - serverPosition.getY(),
                tracked.position.z() - serverPosition.getZ());
    }

    /** Returns the remote player's current vanilla-interpolated bounding box. */
    public static org.bukkit.util.BoundingBox boundingBox(
            final Player target, final org.bukkit.util.BoundingBox actual) {
        final ServerEntityInterpolation service = active;
        if (service == null || target == null || actual == null) return actual;
        return service.boundingBox(target, actual, AbilityExecutionContext.current());
    }

    /** Replaces Bukkit's instantaneous player candidates with viewer packet state. */
    public static void reconcileNearbyPlayers(
            final World world, final org.bukkit.util.BoundingBox query,
            final CoreAbility ability,
            final Predicate<com.projectkorra.projectkorra.platform.mc.entity.Entity> filter,
            final Map<UUID, com.projectkorra.projectkorra.platform.mc.entity.Entity> result) {
        final ServerEntityInterpolation service = active;
        if (service == null || world == null || query == null || result == null) return;
        final UUID observer = observer(ability);
        final ViewerState viewer = observer == null ? null : service.viewers.get(observer);
        if (viewer == null) return;

        for (TrackedPlayer tracked : viewer.byTarget.values()) {
            final Player target = Bukkit.getPlayer(tracked.target);
            if (target == null || target.getWorld() != world
                    || !service.sweptIntersects(target, query, tracked)) {
                result.remove(tracked.target);
                continue;
            }

            final com.projectkorra.projectkorra.platform.mc.entity.Entity wrapped = BukkitMC.entity(target);
            if (wrapped != null && (filter == null || filter.test(wrapped))) {
                result.put(target.getUniqueId(), wrapped);
            } else {
                result.remove(target.getUniqueId());
            }
        }
    }

    private TrackedPlayer trackedPlayer(final Player target, final CoreAbility ability) {
        final UUID observer = observer(ability);
        if (observer == null || observer.equals(target.getUniqueId())) return null;
        final ViewerState viewer = this.viewers.get(observer);
        return viewer == null ? null : viewer.byTarget.get(target.getUniqueId());
    }

    private org.bukkit.util.BoundingBox boundingBox(
            final Player target, final org.bukkit.util.BoundingBox actual,
            final CoreAbility ability) {
        final TrackedPlayer tracked = trackedPlayer(target, ability);
        if (tracked == null) return actual;

        final Location actualLocation = target.getLocation();
        final VanillaRemotePlayerPosition position = tracked.position;
        final double dx = position.x() - actualLocation.getX();
        final double dy = position.y() - actualLocation.getY();
        final double dz = position.z() - actualLocation.getZ();
        return new org.bukkit.util.BoundingBox(
                actual.getMinX() + dx, actual.getMinY() + dy, actual.getMinZ() + dz,
                actual.getMaxX() + dx, actual.getMaxY() + dy, actual.getMaxZ() + dz);
    }

    private boolean sweptIntersects(final Player target,
                                    final org.bukkit.util.BoundingBox query,
                                    final TrackedPlayer tracked) {
        final Location actualLocation = target.getLocation();
        final org.bukkit.util.BoundingBox actual = target.getBoundingBox();
        return tracked.position.sweptIntersects(
                query.getMinX(), query.getMinY(), query.getMinZ(),
                query.getMaxX(), query.getMaxY(), query.getMaxZ(),
                actual.getMinX() - actualLocation.getX(),
                actual.getMinY() - actualLocation.getY(),
                actual.getMinZ() - actualLocation.getZ(),
                actual.getMaxX() - actualLocation.getX(),
                actual.getMaxY() - actualLocation.getY(),
                actual.getMaxZ() - actualLocation.getZ());
    }

    private static UUID observer(final CoreAbility ability) {
        return ability == null || ability.getPlayer() == null
                ? null : ability.getPlayer().getUniqueId();
    }

    private static final class ViewerState {
        private final Map<Integer, TrackedPlayer> byEntityId = new HashMap<>();
        private final Map<UUID, TrackedPlayer> byTarget = new HashMap<>();
        private UUID world;

        private void spawn(final int entityId, final UUID target,
                           final VanillaRemotePlayerPosition position) {
            removeEntity(entityId);
            final TrackedPlayer previous = this.byTarget.remove(target);
            if (previous != null) this.byEntityId.remove(previous.entityId, previous);
            final TrackedPlayer tracked = new TrackedPlayer(entityId, target, position);
            this.byEntityId.put(entityId, tracked);
            this.byTarget.put(target, tracked);
        }

        private void removeEntity(final int entityId) {
            final TrackedPlayer removed = this.byEntityId.remove(entityId);
            if (removed != null) this.byTarget.remove(removed.target, removed);
        }

        private void clear() {
            this.byEntityId.clear();
            this.byTarget.clear();
        }
    }

    private record TrackedPlayer(int entityId, UUID target,
                                 VanillaRemotePlayerPosition position) {
    }

    private sealed interface PacketUpdate permits Spawn, RelativeMove, PositionSync, Teleport, Destroy {
        UUID viewer();
    }

    private record Spawn(UUID viewer, int entityId, UUID target,
                         double x, double y, double z) implements PacketUpdate {
    }

    private record RelativeMove(UUID viewer, int entityId,
                                double x, double y, double z) implements PacketUpdate {
    }

    private record PositionSync(UUID viewer, int entityId,
                                double x, double y, double z) implements PacketUpdate {
    }

    private record Teleport(UUID viewer, int entityId,
                            double x, double y, double z,
                            boolean relativeX, boolean relativeY,
                            boolean relativeZ) implements PacketUpdate {
    }

    private record Destroy(UUID viewer, int[] entityIds) implements PacketUpdate {
    }
}
