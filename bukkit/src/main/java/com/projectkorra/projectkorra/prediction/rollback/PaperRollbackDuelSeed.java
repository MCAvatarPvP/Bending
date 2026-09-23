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

    public static PaperRollbackDuelSeed capture(PaperRollbackMatchBootstrap.Request request, UUID challenge, String definitions,
            Collection<ServerPlayer> players, Map<UUID, PaperRollbackRosterSeed.Services> playerServices,
            Collection<BendingPlayer> bendingPlayers, CollisionManager collisions, Collection<?> gameplayServices,
            RollbackGraphCodec sourceCodec, Collection<String> additionalPermissionNodes,
            long randomSeed, long soundSeed, PaperRollbackTerrainCapture.Limits terrainLimits) {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active()) {
            throw new IllegalStateException("Capture a duel on the live server tick thread before replay");
        }
        Objects.requireNonNull(request); Objects.requireNonNull(challenge); Objects.requireNonNull(sourceCodec);
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
        var nativeRoster = PaperRollbackRosterSeed.capture(nativePlayers.values(), nanos);
        var permissionNodes = new ArrayList<>(PaperRollbackPlayerAccess.gameplayNodes());
        permissionNodes.addAll(Objects.requireNonNull(additionalPermissionNodes));
        var access = PaperRollbackPlayerAccess.capture(nativePlayers.values().stream().map(ServerPlayer::getBukkitEntity).toList(),
                org.bukkit.Bukkit.getPluginManager().getPermissions(), permissionNodes);
        var worldSeed = PaperRollbackWorldSeed.capture(world, request.terrain(), randomSeed, soundSeed, terrainLimits);
        var graph = RollbackBendingState.encode(bending.values(), collisions, gameplayServices, sourceCodec);
        var portable = new RollbackBootstrapData(request.session(), challenge, request.match(), request.round(), millis, nanos, definitions,
                request.sides(), worldSeed, nativeRoster.portable(playerServices), configuration, access, graph);
        if (world.getGameTime() != tick) throw new IllegalStateException("World advanced during duel capture");
        return new PaperRollbackDuelSeed(nativeRoster, portable);
    }
}
