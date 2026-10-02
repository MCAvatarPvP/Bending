package com.projectkorra.projectkorra.prediction.rollback;

import com.google.common.cache.LoadingCache;
import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.TextFilter;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.level.GameType;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Constructor services for an owned, real ServerPlayer. No server, connection,
 * filesystem or player-list constructor runs. Unimplemented service operations
 * fail at their boundary. These services support audited player ticking but do not
 * supply death handling, a live transport or complete world/item interactions.
 */
final class PaperRollbackServerPlayerServices {
    private final PaperRollbackWorldAccess world;
    private final PaperRollbackStatistics.Seed statisticsSeed;
    private final PaperRollbackAdvancements.Seed advancementSeed;
    private PaperRollbackStatistics statistics;
    private PaperRollbackAdvancements advancementState;
    private PaperRollbackConnection connection;
    private PaperRollbackMenu menu;
    private final Set<Object> boundaries = Collections.newSetFromMap(new IdentityHashMap<>());
    private ServerPlayer player;
    private ContainerSynchronizer containers;
    private LoadingCache<?, ?> containerHashes;
    private boolean constructing = true;

    PaperRollbackServerPlayerServices(PaperRollbackWorldAccess world, PaperRollbackStatistics.Seed statisticsSeed, PaperRollbackAdvancements.Seed advancementSeed) {
        this.world = Objects.requireNonNull(world, "world");
        this.statisticsSeed = Objects.requireNonNull(statisticsSeed, "statistics seed");
        this.advancementSeed = Objects.requireNonNull(advancementSeed, "advancement seed");
    }

    ServerPlayer create(GameProfile profile, ClientInformation information, GameType gameType) {
        if (!constructing || player != null) throw new IllegalStateException("Native server player was already constructed");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(information, "information");
        Objects.requireNonNull(gameType, "gameType");
        statistics = new PaperRollbackStatistics(world, () -> player, statisticsSeed);
        var stats = boundary(statistics.counter());
        advancementState = new PaperRollbackAdvancements(world, advancementSeed);
        var advancements = boundary(advancementState.tracker());
        // Text filtering is outside the private combat simulation.
        var filter = boundary(RollbackNativeQueryShell.create(TextFilter.class).instance());
        var players = boundary(RollbackNativeQueryShell.create(PlayerList.class)
                .query(value -> value.getPlayerStats((ServerPlayer) null), null, args -> { requirePlayer(args[0]); return stats; })
                .query(value -> value.getPlayerAdvancements(null), null, args -> { requirePlayer(args[0]); return advancements; })
                .instance());
        var server = boundary(RollbackNativeQueryShell.create(MinecraftServer.class)
                .constant(MinecraftServer::getPlayerList, players)
                .constant(MinecraftServer::getDefaultGameType, gameType)
                .constant(MinecraftServer::getForcedGameType, null)
                .query(value -> value.createTextFilterForPlayer(null), null, args -> { requirePlayer(args[0]); return filter; })
                .query(value -> value.createGameModeForPlayer(null), null, args -> {
                    requirePlayer(args[0]); return new ServerPlayerGameMode(player);
                }).instance());
        try {
            var created = new InputPlayer(server, world.world(), profile, information);
            if (created != player) throw new IllegalStateException("Native server constructor changed player identity");
            advancementState.bindPlayer(created);
            connection = new PaperRollbackConnection(world, created);
            created.connection = boundary(connection.listener());
            menu = new PaperRollbackMenu(world, created);
            boundary(menu.listener());
            containers = created.containerSynchronizer;
            containerHashes = PaperRollbackPrivateAccess.containerHashes(created);
            boundaries.add(containerHashes);
            return created;
        } finally { constructing = false; }
    }

    private <T> T boundary(T value) { boundaries.add(value); return value; }

    @SuppressWarnings("WrapperReferenceEquality")
    private void requirePlayer(Object value) {
        if (!(value instanceof ServerPlayer candidate) || candidate.level() != world.world()) {
            throw new IllegalArgumentException("Native service belongs to another player/world");
        }
        if (constructing && player == null) player = candidate;
        if (candidate != player) throw new IllegalArgumentException("Native service belongs to another player");
    }

    boolean owns(ServerPlayer value) { return !constructing && player == value; }
    boolean external(Object value) { return boundaries.contains(value); }
    PaperRollbackStatistics statistics() { return statistics; }
    PaperRollbackAdvancements advancements() { return advancementState; }
    PaperRollbackConnection connection() { return connection; }
    void initializeMenu() { check(); menu.initialize(); }

    void check() {
        requirePlayer(player);
        if (player.connection != connection.listener() || player.connection.player != player) throw new IllegalStateException("Private player connection changed identity");
        if (player.containerSynchronizer != containers) {
            throw new IllegalStateException("Native container synchronizer changed identity");
        }
    }

    void restored() {
        // This cache only memoizes component hashes for container synchronization.
        // It owns no game state. Clear hashes from a discarded branch rather than
        // snapshotting Guava's locks/queues or retaining stale mutable component keys.
        containerHashes.invalidateAll();
        containerHashes.cleanUp();
    }
    /** Replaces only the base input decay; all original ServerPlayer tick, damage and travel bodies remain in use. */
    private static final class InputPlayer extends ServerPlayer {
        InputPlayer(MinecraftServer server, net.minecraft.server.level.ServerLevel world, GameProfile profile, ClientInformation information) {
            super(server, world, profile, information);
        }
        @Override protected void applyInput() { PaperRollbackMovementFactors.apply(this); }
    }
}
