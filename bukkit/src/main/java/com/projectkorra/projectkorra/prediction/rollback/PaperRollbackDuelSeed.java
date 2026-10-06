package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.util.CollisionManager;
import com.projectkorra.projectkorra.prediction.state.PredictionConfigSync;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * Captures native players, their world and the existing dynamic bending graph at one
 * live tick boundary. The preparation owner must hold gameplay quiescent from capture
 * until ownership handoff; this read-only operation does not freeze or enroll players.
 */
public record PaperRollbackDuelSeed(PaperRollbackRosterSeed nativeRoster, RollbackBootstrapData portable) {
    public PaperRollbackDuelSeed {
        Objects.requireNonNull(nativeRoster); Objects.requireNonNull(portable);
        if (!nativeRoster.participants().equals(portable.sides().keySet()) || nativeRoster.worldTime() != portable.roster().worldTime()) {
            throw new IllegalArgumentException("Native and portable bootstrap differ");
        }
    }

    /** Installed definitions and explicitly audited loader/addon service bindings for production capture. */
    public record GraphSetup(List<Class<?>> installed, RollbackGraphCodec.Limits limits,
            com.projectkorra.projectkorra.listener.CommonAbilityLifecycleListener lifecycle,
            List<RollbackGraphCodec.Binding> services) {
        public GraphSetup {
            installed = List.copyOf(installed); services = List.copyOf(services);
            Objects.requireNonNull(limits); Objects.requireNonNull(lifecycle);
        }
    }

    /** Builds the source codec from the same configuration snapshot shipped to the clients. */
    public static PaperRollbackDuelSeed captureOwned(PaperRollbackLiveOwnership ownership,
            PaperRollbackMatchBootstrap.Request request, UUID challenge, String definitions,
            Collection<ServerPlayer> players, Map<UUID, PaperRollbackRosterSeed.Services> playerServices,
            Collection<BendingPlayer> bendingPlayers, CollisionManager collisions, Collection<?> gameplayServices,
            GraphSetup graph, Collection<String> additionalPermissionNodes,
            long randomSeed, long soundSeed, PaperRollbackTerrainCapture.Limits terrainLimits) {
        Objects.requireNonNull(graph); Objects.requireNonNull(request);
        return Objects.requireNonNull(ownership).capture(request.sides().keySet(), tasks -> {
            var services = new ArrayList<Object>(gameplayServices);
            if (services.stream().anyMatch(service -> service instanceof RollbackTaskBindings
                    || service instanceof RollbackTaskBindings.Capture))
                throw new IllegalArgumentException("Owned capture supplies its own task bindings");
            services.add(tasks);
            return capture(request, challenge, definitions, players, playerServices, bendingPlayers, collisions,
                    services, data -> {
                        var roster = bendingPlayers.stream().map(BendingPlayer::getPlayer).toList();
                        var bindings = new RollbackRosterBindings(roster.getFirst().getWorld(), roster);
                        var configuration = RollbackConfiguration.prepare(data, PredictionConfigSync.sources());
                        return RollbackGameplayGraph.create(graph.installed(), graph.limits(), RollbackGameplayGraph.Side.LIVE,
                                bindings, configuration, graph.lifecycle(), graph.services(), new PaperRollbackGraphViews());
                    }, additionalPermissionNodes, randomSeed, soundSeed, terrainLimits);
        });
    }
    /** Production capture: include frozen callbacks and verify ownership before and after encoding. */
    public static PaperRollbackDuelSeed captureOwned(PaperRollbackLiveOwnership ownership,
            PaperRollbackMatchBootstrap.Request request, UUID challenge, String definitions,
            Collection<ServerPlayer> players, Map<UUID, PaperRollbackRosterSeed.Services> playerServices,
            Collection<BendingPlayer> bendingPlayers, CollisionManager collisions, Collection<?> gameplayServices,
            RollbackGraphCodec sourceCodec, Collection<String> additionalPermissionNodes,
            long randomSeed, long soundSeed, PaperRollbackTerrainCapture.Limits terrainLimits) {
        Objects.requireNonNull(request);
        return Objects.requireNonNull(ownership).capture(request.sides().keySet(), tasks -> {
            var services = new ArrayList<Object>(gameplayServices);
            if (services.stream().anyMatch(service -> service instanceof RollbackTaskBindings
                    || service instanceof RollbackTaskBindings.Capture))
                throw new IllegalArgumentException("Owned capture supplies its own task bindings");
            services.add(tasks);
            return capture(request, challenge, definitions, players, playerServices, bendingPlayers,
                    collisions, services, sourceCodec, additionalPermissionNodes, randomSeed, soundSeed, terrainLimits);
        });
    }
    public static PaperRollbackDuelSeed capture(PaperRollbackMatchBootstrap.Request request, UUID challenge, String definitions,
            Collection<ServerPlayer> players, Map<UUID, PaperRollbackRosterSeed.Services> playerServices,
            Collection<BendingPlayer> bendingPlayers, CollisionManager collisions, Collection<?> gameplayServices,
            RollbackGraphCodec sourceCodec, Collection<String> additionalPermissionNodes,
            long randomSeed, long soundSeed, PaperRollbackTerrainCapture.Limits terrainLimits) {
        Objects.requireNonNull(sourceCodec);
        return capture(request, challenge, definitions, players, playerServices, bendingPlayers, collisions,
                gameplayServices, ignored -> sourceCodec, additionalPermissionNodes, randomSeed, soundSeed, terrainLimits);
    }
    private static PaperRollbackDuelSeed capture(PaperRollbackMatchBootstrap.Request request, UUID challenge, String definitions,
            Collection<ServerPlayer> players, Map<UUID, PaperRollbackRosterSeed.Services> playerServices,
            Collection<BendingPlayer> bendingPlayers, CollisionManager collisions, Collection<?> gameplayServices,
            java.util.function.Function<RollbackConfiguration.Data, RollbackGraphCodec> codecFactory,
            Collection<String> additionalPermissionNodes, long randomSeed, long soundSeed,
            PaperRollbackTerrainCapture.Limits terrainLimits) {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active()) {
            throw new IllegalStateException("Capture a duel on the live server tick thread before replay");
        }
        Objects.requireNonNull(request); Objects.requireNonNull(challenge); Objects.requireNonNull(codecFactory);
        var nativePlayers = new TreeMap<UUID, ServerPlayer>();
        for (var player : players) if (nativePlayers.putIfAbsent(player.getUUID(), player) != null) throw new IllegalArgumentException("Duplicate native player");
        var bending = new TreeMap<UUID, BendingPlayer>();
        for (var player : bendingPlayers) if (bending.putIfAbsent(player.getUUID(), player) != null) throw new IllegalArgumentException("Duplicate bending player");
        if (!nativePlayers.keySet().equals(request.sides().keySet()) || !bending.keySet().equals(request.sides().keySet())
                || !playerServices.keySet().equals(request.sides().keySet())) throw new IllegalArgumentException("Capture requires the exact Neptune roster");
        var world = nativePlayers.firstEntry().getValue().level();
        if (!world.getWorld().getUID().equals(request.world())) throw new IllegalArgumentException("Capture world differs from Neptune round");
        for (var player : nativePlayers.values()) {
            if (player.level() != world || bending.get(player.getUUID()).getPlayer() == null
                    || bending.get(player.getUUID()).getPlayer().handle() != player.getBukkitEntity()) {
                throw new IllegalArgumentException("Bending/native capture ownership differs");
            }
        }
        long tick = world.getGameTime(), millis = System.currentTimeMillis(), nanos = System.nanoTime();
        var configuration = RollbackConfiguration.captureData(PredictionConfigSync.sources());
        var sourceCodec = Objects.requireNonNull(codecFactory.apply(configuration));
        var nativeRoster = PaperRollbackRosterSeed.capture(nativePlayers.values(), nanos);
        var permissionNodes = new ArrayList<>(PaperRollbackPlayerAccess.gameplayNodes());
        permissionNodes.addAll(Objects.requireNonNull(additionalPermissionNodes));
        var access = PaperRollbackPlayerAccess.capture(nativePlayers.values().stream().map(ServerPlayer::getBukkitEntity).toList(),
                org.bukkit.Bukkit.getPluginManager().getPermissions(), permissionNodes);
        var worldSeed = PaperRollbackWorldSeed.capture(world, request.terrain(), randomSeed, soundSeed, terrainLimits);
        var services = new ArrayList<Object>(gameplayServices);
        if (services.stream().anyMatch(RollbackEventBindings.class::isInstance))
            throw new IllegalArgumentException("Duel capture owns event registration capture");
        services.add(RollbackEventBindings.capture(com.projectkorra.projectkorra.platform.Platform.events()));
        var graph = RollbackBendingState.encode(bending.values(), collisions, services, sourceCodec);
        var portable = new RollbackBootstrapData(request.session(), challenge, request.match(), request.round(), millis, nanos, definitions,
                request.sides(), worldSeed, nativeRoster.portable(playerServices), configuration, access, graph);
        if (world.getGameTime() != tick) throw new IllegalStateException("World advanced during duel capture");
        return new PaperRollbackDuelSeed(nativeRoster, portable);
    }
}
