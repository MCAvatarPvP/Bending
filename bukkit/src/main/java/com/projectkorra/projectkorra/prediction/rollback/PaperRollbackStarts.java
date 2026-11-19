package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Live control-message adapter; only a completed bootstrap may call begin. */
public final class PaperRollbackStarts {
    private final Map<Object, UUID> worlds = new IdentityHashMap<>();
    private final RollbackStartServerEndpoint endpoint;
    private final JavaPlugin plugin;

    public PaperRollbackStarts(JavaPlugin plugin) {
        this.plugin = plugin;
        endpoint = new RollbackStartServerEndpoint(new RollbackStartServerEndpoint.Transport() {
            @Override public boolean current(RollbackStartNegotiation.PreparedPeer peer) {
                Player player = Bukkit.getPlayer(peer.player());
                return player != null && player.isOnline() && PaperRollbackIngress.connection(player) == peer.connection()
                        && player.getWorld().getUID().equals(worlds.get(peer.connection()));
            }
            @Override public void send(RollbackStartNegotiation.PreparedPeer peer, RollbackStartPacket.Message message) {
                if (!current(peer)) throw new IllegalStateException("Rollback connection changed before control send");
                Bukkit.getPlayer(peer.player()).sendPluginMessage(plugin, RollbackStartPacket.SERVER_CHANNEL,
                        RollbackStartPacket.encode(message, RollbackStartPacket.Direction.SERVER_TO_CLIENT));
            }
        });
    }

    /** Ready must remain valid during the running duel (lease, preferences and match state). */
    public RollbackStartServerEndpoint.Registration begin(UUID session, UUID challenge, String contentHash,
            Collection<RollbackStartNegotiation.PreparedPeer> peers, long tick, RollbackStartNegotiation.Limits limits,
            BooleanSupplier ready, RollbackStartServerEndpoint.Runtime runtime) {
        if (!Bukkit.isPrimaryThread() || RollbackDomain.active()) throw new IllegalStateException("Start negotiation requires the live main thread");
        Objects.requireNonNull(runtime);
        var captured = new IdentityHashMap<Object, UUID>();
        for (var peer : peers) {
            Player player = Bukkit.getPlayer(peer.player());
            if (endpoint.owns(peer.player()) || worlds.containsKey(peer.connection()) || player == null || !player.isOnline()
                    || PaperRollbackIngress.connection(player) != peer.connection() || !PaperPredictionServer.isExactClient(peer.player())) {
                throw new IllegalStateException("Rollback peer is unavailable or already reserved");
            }
            captured.put(peer.connection(), player.getWorld().getUID());
        }
        worlds.putAll(captured);
        try {
            return endpoint.begin(session, challenge, contentHash, peers, tick, limits, ready, new RollbackStartServerEndpoint.Runtime() {
                @Override public void start(RollbackStartNegotiation negotiation, long serverTick) { runtime.start(negotiation, serverTick); }
                @Override public void tick(long serverTick) { runtime.tick(serverTick); }
                @Override public void stop(RollbackStartServerEndpoint.Failure failure) {
                    if (plugin != null && failure.cause() instanceof com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.OutsideCapture)
                        plugin.getLogger().info("Rollback session " + session + " reached the captured arena boundary: " + failure.detail());
                    else if (failure.cause() != null && plugin != null) plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Rollback session " + session + " stopped: " + failure.detail(), failure.cause());
                    captured.forEach((connection, world) -> worlds.remove(connection, world));
                    runtime.stop(failure);
                }
            });
        } catch (RuntimeException | Error failure) { captured.forEach((connection, world) -> worlds.remove(connection, world)); throw failure; }
    }

    public void receive(Player player, byte[] message, long tick) { endpoint.receive(PaperRollbackIngress.connection(player), message, tick); }
    public void tick(long tick) { endpoint.tick(tick); }
    public boolean stopSession(UUID session, RollbackStartPacket.AbortReason reason) { return endpoint.stopSession(session, reason); }
    public void stopPlayer(UUID player, RollbackStartPacket.AbortReason reason) { endpoint.stopPlayer(player, reason); }
    public void shutdown() { try { endpoint.shutdown(); } finally { worlds.clear(); } }
}
