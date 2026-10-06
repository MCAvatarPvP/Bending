package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Connection/world checks for an already negotiated Paper rollback session. */
public final class PaperRollbackIngress {
    private final RollbackIngress ingress = new RollbackIngress(256);
    private final Map<UUID, UUID> worlds = new HashMap<>();

    public RollbackIngress.Registration enroll(RollbackSession<?, ?> session, Consumer<RollbackIngress.Registration> stopped) {
        if (!Bukkit.isPrimaryThread() || RollbackDomain.active()) throw new IllegalStateException("Rollback enrollment requires the live main thread");
        Objects.requireNonNull(stopped, "stop callback");
        var captured = new HashMap<UUID, UUID>();
        for (var peer : session.peers()) {
            Player player = Bukkit.getPlayer(peer.player());
            if (player == null || !player.isOnline() || connection(player) != peer.connection()
                    || !PaperPredictionServer.isExactClient(peer.player())) throw new IllegalStateException("Prediction connection changed before enrollment");
            captured.put(peer.player(), player.getWorld().getUID());
        }
        var registration = ingress.enroll(session, value -> {
            captured.forEach((player, world) -> worlds.remove(player, world));
            stopped.accept(value);
        });
        worlds.putAll(captured);
        return registration;
    }

    public boolean blocksLegacy(UUID player) { return ingress.blocksLegacy(player); }

    /** Detached whole-roster authority update; direct control transport bypasses the legacy prediction gate. */
    public void publish(RollbackSession<?, ?> session, JavaPlugin plugin) {
        if (!Bukkit.isPrimaryThread() || RollbackDomain.active() || !ingress.owns(session)) {
            throw new IllegalStateException("Authority publication requires this active live-thread session");
        }
        try {
            var recipients = new java.util.ArrayList<Player>();
            for (var peer : session.peers()) {
                var player = Bukkit.getPlayer(peer.player());
                if (player == null || !player.isOnline() || connection(player) != peer.connection()
                        || !player.getWorld().getUID().equals(worlds.get(peer.player()))) {
                    throw new IllegalStateException("Authority recipient connection/world changed");
                }
                recipients.add(player);
            }
            var chunks = RollbackAuthorityChunk.split(session.publish()).stream().map(RollbackAuthorityChunk::encode).toList();
            for (var player : recipients) for (byte[] chunk : chunks) player.sendPluginMessage(plugin, RollbackAuthorityChunk.CHANNEL, chunk);
        } catch (RuntimeException | Error failure) {
            try { ingress.stopPlayer(session.peers().getFirst().player(), RollbackIngress.StopReason.FAILURE); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public void stopPlayer(UUID player, RollbackIngress.StopReason reason) { ingress.stopPlayer(player, reason); }

    public RollbackIngress.Result receive(Player player, byte[] packet) {
        if (!blocksLegacy(player.getUniqueId())) return new RollbackIngress.Result(RollbackIngress.Dispatch.UNENROLLED, null);
        Player current = Bukkit.getPlayer(player.getUniqueId());
        if (current == null || !current.isOnline() || connection(current) != connection(player)) {
            ingress.stopPlayer(player.getUniqueId(), RollbackIngress.StopReason.CONNECTION_CHANGED);
        }
        UUID world = worlds.get(player.getUniqueId());
        if (world != null && !world.equals(player.getWorld().getUID())) ingress.stopPlayer(player.getUniqueId(), RollbackIngress.StopReason.WORLD_CHANGED);
        return ingress.receive(connection(player), packet);
    }

    public void tick() {
        ingress.beginTick();
        for (var entry : Map.copyOf(worlds).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && !entry.getValue().equals(player.getWorld().getUID())) ingress.stopPlayer(entry.getKey(), RollbackIngress.StopReason.WORLD_CHANGED);
        }
        ingress.validateConnections(peer -> {
            Player player = Bukkit.getPlayer(peer.player());
            return player != null && player.isOnline() && connection(player) == peer.connection();
        });
    }

    public void shutdown() { try { ingress.shutdown(); } finally { worlds.clear(); } }

    /** Use this exact native listener identity when constructing RollbackSession.Peer. */
    public static Object connection(Player player) {
        return player instanceof CraftPlayer craft ? craft.getHandle().connection : null;
    }
}
