package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/** A single-tick player cohort. All native players exist before combat references are rebound. */
public final class PaperRollbackRosterSeed {
    public record Services(long randomSeed, int maximumObjects, PaperRollbackStatistics.Seed statistics,
                           PaperRollbackAdvancements.Seed advancements) {
        public Services {
            if (maximumObjects < 1) throw new IllegalArgumentException("Native player object budget");
            Objects.requireNonNull(statistics, "statistics"); Objects.requireNonNull(advancements, "advancements");
        }
    }

    private final Map<UUID, PaperRollbackPlayerSeed> players;
    private final long worldTime;

    private PaperRollbackRosterSeed(Map<UUID, PaperRollbackPlayerSeed> players, long worldTime) {
        this.players = Collections.unmodifiableMap(new LinkedHashMap<>(players)); this.worldTime = worldTime;
    }

    public static PaperRollbackRosterSeed capture(Collection<ServerPlayer> source, long capturedNanos) {
        requireSetup(); Objects.requireNonNull(source, "source roster");
        if (source.isEmpty() || source.size() > 128) throw new IllegalArgumentException("Native player roster budget");
        var ordered = source.stream().sorted(Comparator.comparing(ServerPlayer::getUUID)).toList();
        var world = ordered.getFirst().level(); long tick = world.getGameTime();
        var ids = new HashSet<UUID>(); var entityIds = new HashSet<Integer>();
        for (var player : ordered) {
            if (player.level() != world) throw new IllegalArgumentException("Roster capture crosses live worlds");
            if (!ids.add(player.getUUID()) || !entityIds.add(player.getId())) throw new IllegalArgumentException("Duplicate source roster identity");
        }
        var seeds = new LinkedHashMap<UUID, PaperRollbackPlayerSeed>();
        for (var player : ordered) seeds.put(player.getUUID(), PaperRollbackPlayerSeed.capture(player, capturedNanos));
        if (world.getGameTime() != tick) throw new IllegalStateException("World advanced during roster capture");
        return new PaperRollbackRosterSeed(seeds, tick);
    }

    public Set<UUID> participants() { return players.keySet(); }
    public long worldTime() { return worldTime; }

    /** Same identities, intrinsic state and private RNG seeds as the authoritative roster import. */
    public RollbackRosterData portable(Map<UUID, Services> services) {
        if (!services.keySet().equals(players.keySet())) throw new IllegalArgumentException("Portable roster requires the same player services");
        var result = new TreeMap<UUID, RollbackRosterData.Player>();
        players.forEach((id, seed) -> result.put(id, seed.portable(Objects.requireNonNull(services.get(id)).randomSeed())));
        return new RollbackRosterData(worldTime, result);
    }

    /** On failure discard the candidate private world; nothing ever mutates the source roster. */
    public Map<UUID, PaperRollbackNativePlayerState> instantiate(PaperRollbackWorldAccess world, long initialNanos,
                                                               Map<UUID, Services> services) {
        requireSetup(); Objects.requireNonNull(world, "private world"); Objects.requireNonNull(services, "player services");
        if (!services.keySet().equals(players.keySet())) throw new IllegalArgumentException("Import requires services for exactly the captured roster");
        var importedServices = Map.copyOf(services);
        world.requireRosterImport(players.size());
        for (var seed : players.values()) seed.validateRoster(world, players.keySet());
        var result = new LinkedHashMap<UUID, PaperRollbackNativePlayerState>();
        var nativePlayers = new LinkedHashMap<UUID, ServerPlayer>();
        for (var seed : players.values()) {
            var service = importedServices.get(seed.id());
            var state = seed.instantiateBody(world, initialNanos, service.randomSeed(), service.maximumObjects(), service.statistics(), service.advancements());
            result.put(seed.id(), state); nativePlayers.put(seed.id(), (ServerPlayer) state.ownedPlayer());
        }
        for (var seed : players.values()) seed.bindCombat(nativePlayers.get(seed.id()), nativePlayers);
        return Collections.unmodifiableMap(result);
    }

    private static void requireSetup() {
        if (!TickThread.isTickThread() || RollbackClock.active()) throw new IllegalStateException("Import the roster on the tick thread before replay");
    }
}
