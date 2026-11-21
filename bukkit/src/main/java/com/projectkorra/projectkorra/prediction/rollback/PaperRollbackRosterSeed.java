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

    /** Capture real statistic counters and advancement progress before detaching the players. */
    public static Map<UUID, Services> captureServices(Collection<ServerPlayer> roster, long seed, int maximumObjects) {
        requireSetup();
        var services = new TreeMap<UUID, Services>();
        for (var player : roster) {
            UUID id = player.getUUID();
            var service = new Services(seed ^ id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 17),
                    maximumObjects, PaperRollbackStatistics.Seed.capture(player), PaperRollbackAdvancements.Seed.capture(player));
            if (services.putIfAbsent(id, service) != null) throw new IllegalArgumentException("Duplicate player in captured services");
        }
        return Collections.unmodifiableMap(services);
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
        var preparation = prepare(world, initialNanos, services);
        while (preparation.advance()) { /* Synchronous callers request the whole roster. */ }
        return preparation.finish();
    }

    Preparation prepare(PaperRollbackWorldAccess world, long initialNanos, Map<UUID, Services> services) {
        return new Preparation(world, initialNanos, services);
    }

    final class Preparation {
        private final PaperRollbackWorldAccess world;
        private final long initialNanos;
        private final Map<UUID, Services> services;
        private final Iterator<PaperRollbackPlayerSeed> remaining = players.values().iterator();
        private final Map<UUID, PaperRollbackNativePlayerState> bodies = new LinkedHashMap<>();
        private final Map<UUID, ServerPlayer> natives = new LinkedHashMap<>();

        private Preparation(PaperRollbackWorldAccess world, long initialNanos, Map<UUID, Services> services) {
            requireSetup();
            this.world = Objects.requireNonNull(world); this.initialNanos = initialNanos; this.services = Map.copyOf(services);
            if (!this.services.keySet().equals(players.keySet())) throw new IllegalArgumentException("Import requires services for exactly the captured roster");
            world.requireRosterImport(players.size());
            for (var seed : players.values()) seed.validateRoster(world, players.keySet());
        }
        boolean advance() {
            requireSetup();
            if (!remaining.hasNext()) return false;
            var seed = remaining.next();
            var service = services.get(seed.id());
            var state = seed.instantiateBody(world, initialNanos, service.randomSeed(), service.maximumObjects(), service.statistics(), service.advancements());
            bodies.put(seed.id(), state); natives.put(seed.id(), (ServerPlayer) state.ownedPlayer());
            return true;
        }
        Map<UUID, PaperRollbackNativePlayerState> finish() {
            requireSetup();
            if (remaining.hasNext()) throw new IllegalStateException("Native roster is incomplete");
            for (var seed : players.values()) seed.bindCombat(natives.get(seed.id()), natives);
            return Collections.unmodifiableMap(bodies);
        }
    }

    private static void requireSetup() {
        if (!TickThread.isTickThread() || RollbackClock.active()) throw new IllegalStateException("Import the roster on the tick thread before replay");
    }
}
