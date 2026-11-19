package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.listener.CommonAbilityLifecycleListener;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.bukkit.BukkitProjectKorraPlatform;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import java.util.*;
import java.util.function.Consumer;

/** Owns the complete live side of a Neptune round from freeze through confirmed writeback. */
public final class PaperRollbackDuels implements PaperRollbackMatchBootstrap, org.bukkit.event.Listener {
    public record Simulation(PaperRollbackNativeScene scene, RollbackGameplayGraph.Imported graph,
            RollbackCombatRuntime<RollbackPlayerInput, Object> combat,
            Consumer<RollbackEngine.Update<RollbackDomain.Checkpoint, RollbackPlayerInput, Object>> presentation,
            PaperRollbackCorrections.Settings corrections, java.util.concurrent.CompletableFuture<Void> nativeMethods) {
        public Simulation {
            Objects.requireNonNull(scene); Objects.requireNonNull(graph); Objects.requireNonNull(combat);
            Objects.requireNonNull(presentation); Objects.requireNonNull(corrections); Objects.requireNonNull(nativeMethods);
        }
    }
    @FunctionalInterface
    public interface SimulationFactory {
        default boolean ready() { return true; }
        default java.util.function.Supplier<Simulation> prepare(RollbackDuelRuntime setup, PaperRollbackDuelSeed captured,
                ServerLevel source, Map<UUID, PaperRollbackRosterSeed.Services> services) {
            return () -> open(setup, captured, source, services);
        }
        Simulation open(RollbackDuelRuntime setup, PaperRollbackDuelSeed captured, ServerLevel source,
                        Map<UUID, PaperRollbackRosterSeed.Services> services);
    }

    private static final PaperRollbackTerrainCapture.Limits CAPTURE_LIMITS = new PaperRollbackTerrainCapture.Limits(
            RollbackDuelRuntime.TERRAIN.maximumCells(), 65536, RollbackDuelRuntime.TERRAIN.maximumStorageBytes(),
            RollbackDuelRuntime.TERRAIN.maximumBlockEntityBytes());
    private static final int RESERVED_IDS = 65_536;
    private final PaperPredictionServer server;
    private final BukkitProjectKorraPlatform platform;
    private final CommonAbilityLifecycleListener lifecycle;
    private final SimulationFactory factory;
    private final PaperRollbackTerrainChunks terrainChunks;
    private final RollbackDuelRuntime definitions;
    private final java.util.concurrent.CompletableFuture<Void> classSchemas;
    private final Set<Enrollment> pendingAcquisition = new LinkedHashSet<>();

    /** Packet movement received between ticks still belongs to the live player until Paper processes its contacts. */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onTickEnd(com.destroystokyo.paper.event.server.ServerTickEndEvent event) {
        if (pendingAcquisition.isEmpty()) return;
        var ready = List.copyOf(pendingAcquisition);
        pendingAcquisition.clear();
        for (var enrollment : ready) enrollment.acquireAtBoundary();
    }


    public PaperRollbackDuels(org.bukkit.plugin.Plugin plugin, PaperPredictionServer server, BukkitProjectKorraPlatform platform,
            CommonAbilityLifecycleListener lifecycle, SimulationFactory factory) {
        terrainChunks = new PaperRollbackTerrainChunks(plugin);
        this.server = Objects.requireNonNull(server); this.platform = Objects.requireNonNull(platform);
        this.lifecycle = Objects.requireNonNull(lifecycle); this.factory = Objects.requireNonNull(factory);
        definitions = new RollbackDuelRuntime(getClass().getClassLoader());
        classSchemas = java.util.concurrent.CompletableFuture.runAsync(
                () -> RollbackGameplayCatalog.create(definitions.installed(), List.of()),
                task -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task));
    }

    @Override public boolean supports(Set<UUID> players) { return server.supportsRollbackBootstrap(players)
            && com.projectkorra.projectkorra.region.BukkitRegionProtectionBootstrap.supportsRollback()
            && RollbackLiveRegistryEligibility.activeRegistry(players, block -> false).isEmpty(); }

    @Override public Preparation prepare(Request request) {
        if (!supports(request.sides().keySet())) throw new IllegalStateException("Rollback participants changed before capture");
        return new Enrollment(request);
    }

    private final class Enrollment implements Preparation, RollbackStartServerEndpoint.Runtime {
        private final Request request;
        private final UUID challenge = UUID.randomUUID();
        private final Map<UUID, ServerPlayer> players = new TreeMap<>();
        private final Map<UUID, BendingPlayer> bending = new TreeMap<>();
        private final ServerLevel level;
        private final PaperRollbackLiveOwnership ownership;
        private final PaperRollbackTerrainChunks.Lease chunks;
        private PaperRollbackMatchRestoration.LiveRoster live;
        private com.projectkorra.projectkorra.util.TempBlock.RollbackLayers.Lease layerLease;
        private PaperRollbackTerrainCapture.Prepared terrain;
        private PaperRollbackDuelSeed captured;
        private PaperRollbackBootstraps.Transfer transfer;
        private java.util.concurrent.CompletableFuture<byte[]> encodedBootstrap;
        private java.util.concurrent.CompletableFuture<Map<UUID, PaperRollbackRosterSeed.Services>> nativeServices;
        private java.util.function.Supplier<Simulation> assembly;
        private int lastAssemblyTick;
        private Simulation simulation;
        private PaperRollbackMatchRuntime<Object> runtime;
        private PaperRollbackMatchRestoration restoration;
        private boolean acquired, running, cleanupPending, released, stopped;
        private final List<RollbackDuelInteractions.LiveBreak> matchBreaks = new ArrayList<>();
        private Prepared prepared;
        private Throwable acquisitionFailure;

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
            chunks = terrainChunks.prepare(level.getWorld(), request.terrain());
            ownership = server.prepareRollbackOwnership(players.values(), platform.rollbackScheduler(), tasks,
                    RESERVED_IDS, this::stopBeforeMutation);
        }

        private void acquireAtBoundary() {
            if (stopped || released) return;
            acquired = true;
            long time = RollbackTiming.start();
            try { ownership.acquire(); }
            catch (RuntimeException | Error failure) { acquisitionFailure = failure; }
            finally { RollbackTiming.finish(time, "enroll.acquire"); }
        }

        @Override public Prepared poll() {
            if (stopped || released) throw new IllegalStateException("Duel preparation expired");
            if (prepared != null) return prepared;
            if (acquisitionFailure instanceof RuntimeException failure) throw failure;
            if (acquisitionFailure instanceof Error error) throw error;
            if (!acquired) {
                if (chunks.poll() && terrainReady() && factory.ready() && classSchemas.isDone()) {
                    classSchemas.join();
                    pendingAcquisition.add(this);
                }
                return null;
            }
            if (captured == null) {
                if (!ownership.pollReady(level.getServer().packetProcessor()) || !terrainReady()) return null;
                if (!terrain.matches(level)) { terrain = null; return null; }
                long time = RollbackTiming.start();
                try { capture(); } finally { RollbackTiming.finish(time, "enroll.capture"); }
                terrain = null;
                lastAssemblyTick = Bukkit.getCurrentTick();
                return null;
            }
            if (simulation == null) {
                ownership.requireReady();
                int tick = Bukkit.getCurrentTick();
                if (lastAssemblyTick == tick) return null;
                lastAssemblyTick = tick;
                if (assembly == null) {
                    if (!nativeServices.isDone()) return null;
                    assembly = Objects.requireNonNull(factory.prepare(definitions, captured, level, nativeServices.join()));
                    nativeServices = null;
                    return null;
                }
                simulation = assembly.get();
                if (simulation == null) return null;
                assembly = null;
                openRuntime();
            }
            if (transfer == null) {
                if (!encodedBootstrap.isDone() || !simulation.nativeMethods().isDone()) return null;
                simulation.nativeMethods().join();
                ownership.requireReady();
                transfer = server.beginEncodedRollbackBootstrap(captured.portable(), encodedBootstrap.join(),
                        RollbackDuelRuntime.TRANSFER, () -> {
                            if (stopped || released) return false;
                            ownership.requireReady();
                            return true;
                        });
                encodedBootstrap = null;
            }
            if (!transfer.poll()) return null;
            prepared = new Prepared(challenge, transfer.offer().fingerprint(), transfer.handoff(), RollbackDuelRuntime.CLOCK, this);
            return prepared;
        }

        private boolean terrainReady() {
            if (terrain == null) {
                var world = level.getWorld();
                var bounds = com.projectkorra.projectkorra.prediction.rollback.world.RollbackLightingBounds.capture(
                        request.terrain(), world.getMinHeight(), world.getMaxHeight(), CAPTURE_LIMITS.maximumCells());
                terrain = PaperRollbackTerrainCapture.prepare(level, bounds, CAPTURE_LIMITS);
            }
            if (!terrain.terrain().isDone()) return false;
            terrain.terrain().join();
            return true;
        }

        private void capture() {
            long seed = RollbackDuelRuntime.seed(request.session());
            var ids = request.sides().keySet();
            var reservation = CoreAbility.reserveRollbackIds(ids, RESERVED_IDS);
            var original = CoreAbility.captureRollbackRegistry(ids, reservation);
            long time = RollbackTiming.start();
            var services = PaperRollbackRosterSeed.captureServices(players.values(), seed, RollbackDuelRuntime.GRAPH.maximumObjects());
            time = RollbackTiming.finish(time, "enroll.playerServices");
            var graph = new PaperRollbackDuelSeed.GraphSetup(definitions.installed(), RollbackDuelRuntime.GRAPH,
                    lifecycle, pluginBindings());
            var views = bending.values().stream().map(BendingPlayer::getPlayer).toList();
            var regions = com.projectkorra.projectkorra.region.BukkitRegionProtectionBootstrap.captureRollback(views, request.terrain());
            layerLease = com.projectkorra.projectkorra.util.TempBlock.RollbackLayers.capture(views.getFirst().getWorld(),
                    request.terrain(), ids, this::stopBeforeMutation);
            layerLease.seed().earth().revertCheckDelay(platform.rollbackScheduler().earthRevertDelay(ProjectKorra.plugin.revertChecker));
            var globals = RollbackAbilityGlobals.capture(views);
            globals.captureMaintenance(platform.rollbackScheduler());
            globals.requireNoInitialActors(views.getFirst().getWorld(), request.terrain(), ids);
            globals.bindLayers(layerLease.seed());
            time = RollbackTiming.finish(time, "enroll.participantGlobals");
            var gameplay = new ArrayList<Object>(List.of(reservation, regions, globals));
            var interactions = request.interactions();
            if (interactions == null) {
                var rules = PaperRollbackMatchRules.capture(request, players.values().iterator().next().getBukkitEntity());
                gameplay.add(rules.floor());
                interactions = rules.interactions();
            }
            captured = PaperRollbackDuelSeed.captureOwned(ownership, request.withInteractions(interactions), challenge, definitions.definitions(),
                    players.values(), services, bending.values(), ProjectKorra.collisionManager, gameplay,
                    graph, List.of(), seed, Long.rotateLeft(seed, 29), CAPTURE_LIMITS, terrain,
                    codec -> live = new PaperRollbackMatchRestoration.LiveRoster(players, bending, reservation, original, codec, layerLease));
            time = RollbackTiming.finish(time, "enroll.seed");
            encodedBootstrap = PaperRollbackBootstraps.encodeCaptured(captured.portable(), RollbackDuelRuntime.TERRAIN);
            // The live scheduler uses this monitor to enter async callbacks. Check again at ownership transfer.
            synchronized (platform.rollbackScheduler()) {
                platform.rollbackScheduler().earthRevertDelay(ProjectKorra.plugin.revertChecker);
                layerLease.detach();
            }
            time = RollbackTiming.finish(time, "enroll.terrainHandoff");
            nativeServices = PaperRollbackAdvancements.prepareRoster(services, level.registryAccess());
            RollbackTiming.finish(time, "enroll.advancementCatalogue");
        }
        private void openRuntime() {
            long seed = RollbackDuelRuntime.seed(request.session());
            runtime = new PaperRollbackMatchRuntime<>(request, seed, simulation.combat(), server, new RollbackServerRuntime.Output<>() {
                @Override public void update(RollbackEngine.Update<RollbackDomain.Checkpoint, RollbackPlayerInput, Object> update) {
                    simulation.presentation().accept(update);
                    for (var effect : update.finalizedEffects()) {
                        if (effect.value() instanceof RollbackDuelInteractions.LiveBreak hit) matchBreaks.add(hit);
                    }
                }
                @Override public void stop(RollbackStartServerEndpoint.Failure failure) { release(); }
            }, simulation.corrections());
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
            long time = RollbackTiming.start();
            try {
                ownership.requireReady();
                runtime.tick(tick);
                if (!matchBreaks.isEmpty() && !stopped && !released) applyMatchBreaks();
            }
            finally { RollbackTiming.finish(time, "server.tick"); }
        }
        private void applyMatchBreaks() {
            // Neptune's bed objective, cancellation and team messages require real event listeners.
            // Stop at the confirmed checkpoint before asking the real player to break the block once.
            var confirmed = List.copyOf(matchBreaks);
            matchBreaks.clear();
            stopBeforeMutation();
            for (var hit : confirmed) {
                var player = Bukkit.getPlayer(hit.player());
                if (!players.containsKey(hit.player()) || player == null || !player.isOnline()
                        || !player.getWorld().getUID().equals(request.world())) continue;
                var pos = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position(hit.x(), hit.y(), hit.z());
                if (!request.terrain().contains(pos)) continue;
                var block = player.getWorld().getBlockAt(hit.x(), hit.y(), hit.z());
                if (block.getType().name().equals(hit.material())) player.breakBlock(block);
            }
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
            pendingAcquisition.remove(this);
            if (released) return;
            if (acquired) {
                if (cleanupPending && !ownership.pollCleanup(level.getServer().packetProcessor()))
                    throw new IllegalStateException("Waiting for the live packet queue before restoring the duel");
                if (running && restoration == null)
                    restoration = PaperRollbackMatchRestoration.prepare(simulation.combat(), captured.portable(),
                            simulation.scene(), simulation.graph(), level, live, System.nanoTime());
                try {
                    if (running) restoration.release(ownership);
                    else ownership.abort(() -> { if (layerLease != null) layerLease.abort(); });
                } catch (RuntimeException | Error failure) { cleanupPending = true; throw failure; }
            }
            assembly = null;
            if (terrain != null) { terrain.terrain().cancel(false); terrain = null; }
            if (encodedBootstrap != null) { encodedBootstrap.cancel(false); encodedBootstrap = null; }
            if (nativeServices != null) { nativeServices.cancel(false); nativeServices = null; }
            chunks.close();
            released = true;
        }
    }

    private List<RollbackGraphCodec.Binding> pluginBindings() {
        return Arrays.stream(Bukkit.getPluginManager().getPlugins()).map(plugin ->
                new RollbackGraphCodec.Binding("plugin/" + plugin.getName(), Object.class, plugin)).toList();
    }
}
