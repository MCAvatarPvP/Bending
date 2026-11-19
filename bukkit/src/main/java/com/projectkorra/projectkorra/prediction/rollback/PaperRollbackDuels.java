package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.listener.CommonAbilityLifecycleListener;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.bukkit.BukkitProjectKorraPlatform;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;
import com.projectkorra.projectkorra.prediction.state.PredictionConfigSync;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import java.util.*;
import java.util.function.Consumer;

/** Owns the complete live side of a Neptune round from freeze through confirmed writeback. */
public final class PaperRollbackDuels implements PaperRollbackMatchBootstrap {
    public record Simulation(PaperRollbackNativeScene scene, RollbackGameplayGraph.Imported graph,
            RollbackCombatRuntime<RollbackPlayerInput, Object> combat,
            Consumer<RollbackEngine.Update<RollbackDomain.Checkpoint, RollbackPlayerInput, Object>> presentation,
            PaperRollbackCorrections.Settings corrections) {
        public Simulation {
            Objects.requireNonNull(scene); Objects.requireNonNull(graph); Objects.requireNonNull(combat);
            Objects.requireNonNull(presentation); Objects.requireNonNull(corrections);
        }
    }
    @FunctionalInterface
    public interface SimulationFactory {
        Simulation open(RollbackDuelRuntime setup, PaperRollbackDuelSeed captured, ServerLevel source,
                        Map<UUID, PaperRollbackRosterSeed.Services> services);
    }

    private static final int RESERVED_IDS = 65_536;
    private final PaperPredictionServer server;
    private final BukkitProjectKorraPlatform platform;
    private final CommonAbilityLifecycleListener lifecycle;
    private final SimulationFactory factory;
    private RollbackDuelRuntime definitions;

    public PaperRollbackDuels(PaperPredictionServer server, BukkitProjectKorraPlatform platform,
            CommonAbilityLifecycleListener lifecycle, SimulationFactory factory) {
        this.server = Objects.requireNonNull(server); this.platform = Objects.requireNonNull(platform);
        this.lifecycle = Objects.requireNonNull(lifecycle); this.factory = Objects.requireNonNull(factory);
    }

    @Override public boolean supports(Set<UUID> players) { return server.supportsRollbackBootstrap(players)
            && com.projectkorra.projectkorra.region.BukkitRegionProtectionBootstrap.supportsRollback(); }

    @Override public Preparation prepare(Request request) {
        if (!supports(request.sides().keySet())) throw new IllegalStateException("Rollback participants changed before capture");
        if (definitions == null) definitions = new RollbackDuelRuntime(getClass().getClassLoader());
        return new Enrollment(request);
    }

    private final class Enrollment implements Preparation, RollbackStartServerEndpoint.Runtime {
        private final Request request;
        private final UUID challenge = UUID.randomUUID();
        private final Map<UUID, ServerPlayer> players = new TreeMap<>();
        private final Map<UUID, BendingPlayer> bending = new TreeMap<>();
        private final ServerLevel level;
        private final PaperRollbackLiveOwnership ownership;
        private PaperRollbackMatchRestoration.LiveRoster live;
        private PaperRollbackDuelSeed captured;
        private PaperRollbackBootstraps.Transfer transfer;
        private Simulation simulation;
        private PaperRollbackMatchRuntime<Object> runtime;
        private PaperRollbackMatchRestoration restoration;
        private boolean acquired, running, cleanupPending, released, stopped;
        private Prepared prepared;

        private Enrollment(Request request) {
            this.request = request;
            for (var id : request.sides().keySet()) {
                if (!(Bukkit.getPlayer(id) instanceof CraftPlayer player) || !player.isOnline()
                        || !request.world().equals(player.getWorld().getUID()))
                    throw new IllegalStateException("Duel participant left the arena");
                players.put(id, player.getHandle());
                bending.put(id, Objects.requireNonNull(BendingPlayer.getBendingPlayer(BukkitMC.player(player)),
                        "Missing bending profile for " + id));
            }
            level = players.values().iterator().next().level();
            var references = new ArrayList<Object>(bending.values());
            for (var player : players.values()) { references.add(player); references.add(player.getBukkitEntity()); }
            var tasks = new RollbackTaskOwnership(request.sides().keySet(), references,
                    value -> value instanceof org.bukkit.plugin.Plugin || value instanceof org.bukkit.Server
                            || value instanceof org.bukkit.World || value instanceof net.minecraft.server.MinecraftServer
                            || value instanceof ServerLevel || value instanceof PaperPredictionServer,
                    RollbackDuelRuntime.GRAPH.maximumObjects());
            ownership = server.prepareRollbackOwnership(players.values(), platform.rollbackScheduler(), tasks,
                    RESERVED_IDS, this::stopBeforeMutation);
        }

        @Override public Prepared poll() {
            if (stopped || released) throw new IllegalStateException("Duel preparation expired");
            if (prepared != null) return prepared;
            if (!acquired) { acquired = true; ownership.acquire(); }
            if (captured == null) {
                if (!ownership.pollReady(level.getServer().packetProcessor())) return null;
                capture();
            }
            if (!transfer.poll()) return null;
            prepared = new Prepared(challenge, transfer.offer().fingerprint(), transfer.handoff(), RollbackDuelRuntime.CLOCK, this);
            return prepared;
        }

        private void capture() {
            long seed = RollbackDuelRuntime.seed(request.session());
            var ids = request.sides().keySet();
            var reservation = CoreAbility.reserveRollbackIds(ids, RESERVED_IDS);
            var original = CoreAbility.captureRollbackRegistry(ids, reservation);
            var services = PaperRollbackRosterSeed.captureServices(players.values(), seed, RollbackDuelRuntime.GRAPH.maximumObjects());
            var graph = new PaperRollbackDuelSeed.GraphSetup(definitions.installed(), RollbackDuelRuntime.GRAPH,
                    lifecycle, pluginBindings());
            var bounds = RollbackDuelRuntime.TERRAIN;
            var captureLimits = new PaperRollbackTerrainCapture.Limits(bounds.maximumCells(), 65536,
                    bounds.maximumStorageBytes(), bounds.maximumBlockEntityBytes());
            var views = bending.values().stream().map(BendingPlayer::getPlayer).toList();
            var regions = com.projectkorra.projectkorra.region.BukkitRegionProtectionBootstrap.captureRollback(views, request.terrain());
            captured = PaperRollbackDuelSeed.captureOwned(ownership, request, challenge, definitions.definitions(),
                    players.values(), services, bending.values(), ProjectKorra.collisionManager, List.of(reservation, regions),
                    graph, List.of(), seed, Long.rotateLeft(seed, 29), captureLimits);
            var config = RollbackConfiguration.prepare(captured.portable().configuration(), PredictionConfigSync.sources());
            var codec = RollbackGameplayGraph.create(graph.installed(), graph.limits(), RollbackGameplayGraph.Side.LIVE,
                    new RollbackRosterBindings(views.getFirst().getWorld(), views), config, lifecycle, graph.services(),
                    new PaperRollbackGraphViews());
            live = new PaperRollbackMatchRestoration.LiveRoster(players, bending, reservation, original, codec);
            simulation = Objects.requireNonNull(factory.open(definitions, captured, level, services));
            runtime = new PaperRollbackMatchRuntime<>(request, seed, simulation.combat(), server, new RollbackServerRuntime.Output<>() {
                @Override public void update(RollbackEngine.Update<RollbackDomain.Checkpoint, RollbackPlayerInput, Object> update) {
                    simulation.presentation().accept(update);
                }
                @Override public void stop(RollbackStartServerEndpoint.Failure failure) { release(); }
            }, simulation.corrections());
            transfer = server.beginRollbackBootstrap(captured.portable(), bounds, RollbackDuelRuntime.TRANSFER, () -> {
                if (stopped || released) return false;
                ownership.requireReady(); return true;
            });
        }

        @Override public void start(RollbackStartNegotiation negotiation, long tick) {
            if (prepared == null || stopped || released || running) throw new IllegalStateException("Duel is not prepared");
            ownership.requireReady();
            runtime.start(negotiation, tick);
            ownership.beginSimulation();
            running = true;
        }
        @Override public void tick(long tick) {
            if (stopped || released || !running) throw new IllegalStateException("Duel authority is not running");
            ownership.requireReady(); runtime.tick(tick);
        }
        private void stopBeforeMutation() {
            if (!server.stopNegotiatedRollback(request.session()))
                stop(new RollbackStartServerEndpoint.Failure(RollbackStartPacket.AbortReason.STATE_CHANGED,
                        "Live participant changed during duel preparation", null));
            if (!released) throw new IllegalStateException("Duel ownership is still restoring");
        }
        @Override public void stop(RollbackStartServerEndpoint.Failure failure) {
            stopped = true;
            if (transfer != null) { transfer.close(failure.reason()); transfer = null; }
            if (runtime != null) runtime.stop(failure);
            release();
        }
        private void release() {
            if (released) return;
            if (acquired) {
                if (cleanupPending && !ownership.pollCleanup(level.getServer().packetProcessor()))
                    throw new IllegalStateException("Waiting for the live packet queue before restoring the duel");
                if (running && restoration == null)
                    restoration = PaperRollbackMatchRestoration.prepare(simulation.combat(), captured.portable(),
                            simulation.scene(), simulation.graph(), level, live, System.nanoTime());
                try {
                    if (running) restoration.release(ownership);
                    else ownership.abort(() -> { });
                } catch (RuntimeException | Error failure) { cleanupPending = true; throw failure; }
            }
            released = true;
        }
    }

    private List<RollbackGraphCodec.Binding> pluginBindings() {
        return Arrays.stream(Bukkit.getPluginManager().getPlugins()).map(plugin ->
                new RollbackGraphCodec.Binding("plugin/" + plugin.getName(), Object.class, plugin)).toList();
    }
}
