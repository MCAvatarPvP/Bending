package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Real Paper plugin-channel transfer, pinned to each participant's original connection and world. */
public final class PaperRollbackBootstraps {
    private final Map<Object, UUID> worlds = new IdentityHashMap<>();
    private final RollbackBootstrapServerEndpoint endpoint;
    private final LongSupplier clock;
    public PaperRollbackBootstraps(JavaPlugin plugin, LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
        endpoint = new RollbackBootstrapServerEndpoint(new RollbackBootstrapServerEndpoint.Transport() {
            @Override public boolean current(RollbackBootstrapServerEndpoint.Peer peer) {
                Player player = Bukkit.getPlayer(peer.player());
                return player != null && player.isOnline() && PaperRollbackIngress.connection(player) == peer.connection()
                        && player.getWorld().getUID().equals(worlds.get(peer.connection()));
            }
            @Override public void send(RollbackBootstrapServerEndpoint.Peer peer, RollbackBootstrapPacket.Message message) {
                if (!current(peer)) throw new IllegalStateException("Bootstrap connection changed before send");
                Bukkit.getPlayer(peer.player()).sendPluginMessage(plugin, RollbackBootstrapPacket.SERVER_CHANNEL,
                        RollbackBootstrapPacket.encode(message, RollbackStartPacket.Direction.SERVER_TO_CLIENT));
            }
        });
    }
    /** Channel support is a preflight only; successful native import still requires an authenticated receipt. */
    public boolean supports(Set<UUID> players) {
        requireThread();
        if (players.size() < 2 || players.size() > 128) return false;
        for (UUID id : players) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline() || !PaperPredictionServer.isExactClient(id)
                    || !player.getListeningPluginChannels().contains(RollbackBootstrapPacket.SERVER_CHANNEL)) return false;
        }
        return true;
    }
    public Transfer begin(RollbackBootstrapData data, RollbackTerrainCodec.Limits terrainLimits,
                          RollbackBootstrapServerEndpoint.Limits limits, BooleanSupplier ready) {
        requireThread();
        if (!supports(data.sides().keySet())) throw new IllegalStateException("Bootstrap roster does not support the transport");
        var peers = new ArrayList<RollbackBootstrapServerEndpoint.Peer>(); var captured = new IdentityHashMap<Object, UUID>();
        for (UUID id : data.sides().keySet()) {
            Player player = Bukkit.getPlayer(id); Object connection = PaperRollbackIngress.connection(player);
            if (!data.world().world().equals(player.getWorld().getUID()) || worlds.containsKey(connection)) throw new IllegalStateException("Bootstrap world changed or connection reserved");
            peers.add(new RollbackBootstrapServerEndpoint.Peer(id, connection)); captured.put(connection, data.world().world());
        }
        byte[] encoded = data.encode(new PaperRollbackTerrainTransfer(), terrainLimits);
        var offer = new RollbackBootstrapPacket.Offer(data.session(), data.challenge(), RollbackStartNegotiation.VERSION,
                data.definitions(), RollbackBootstrapData.fingerprint(encoded), encoded.length);
        worlds.putAll(captured);
        try { return new Transfer(endpoint.begin(offer, encoded, peers, clock.getAsLong(), limits, ready), captured); }
        catch (RuntimeException | Error failure) { captured.forEach((connection, world) -> worlds.remove(connection, world)); throw failure; }
    }
    public final class Transfer {
        private final RollbackBootstrapServerEndpoint.Registration registration;
        private final Map<Object, UUID> captured;
        private Transfer(RollbackBootstrapServerEndpoint.Registration registration, Map<Object, UUID> captured) { this.registration = registration; this.captured = captured; }
        public RollbackBootstrapPacket.Offer offer() { return registration.offer(); }
        public boolean poll() { requireThread(); return registration.poll(clock.getAsLong()); }
        public List<RollbackStartNegotiation.PreparedPeer> handoff() { requireThread(); return registration.handoff(clock.getAsLong()); }
        /** Caller stops native runtime/preparation first; this releases only transfer ownership. */
        public void close(RollbackStartPacket.AbortReason reason) {
            requireThread();
            try { registration.close(reason); }
            finally { captured.forEach((connection, world) -> worlds.remove(connection, world)); }
        }
    }
    public void receive(Player player, byte[] message, long tick) { requireThread(); endpoint.receive(PaperRollbackIngress.connection(player), message, tick); }
    public void stopPlayer(UUID player, RollbackStartPacket.AbortReason reason) {
        if (RollbackDomain.active()) throw new IllegalStateException("Bootstrap cancellation cannot run during replay");
        endpoint.stopPlayer(player, reason); // The endpoint enforces its construction thread; this path accesses no Bukkit state.
    }
    public void shutdown() { requireThread(); try { endpoint.shutdown(); } finally { worlds.clear(); } }
    private static void requireThread() { if (!Bukkit.isPrimaryThread() || RollbackDomain.active()) throw new IllegalStateException("Bootstrap transport requires the live main thread"); }
}
