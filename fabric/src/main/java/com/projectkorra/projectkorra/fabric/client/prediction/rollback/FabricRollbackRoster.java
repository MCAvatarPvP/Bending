package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.google.common.collect.ImmutableListMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackDomain;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerCombatData;
import com.projectkorra.projectkorra.prediction.rollback.RollbackRosterData;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.*;

/**
 * Builds all private native bodies from one Paper capture before rebinding combat links.
 * This owns a new candidate world: a failed import never publishes a partial roster.
 * Queries must be private session services; world/bending/service bootstrap and Paper
 * policy parity are still required before this roster can replace live duel simulation.
 */
public final class FabricRollbackRoster {
    private final Thread thread = Thread.currentThread();
    private final FabricRollbackWorldAccess world;
    private final Map<UUID, FabricRollbackNativePlayerState> players;
    private final Map<UUID, FabricRollbackPlayerValues.Prepared> values;
    private record Metadata(RollbackRosterData.Identity identity, long randomSeed) { }
    private final Map<UUID, Metadata> metadata;

    public static FabricRollbackRoster instantiate(RollbackRosterData data, Set<UUID> expectedParticipants, FabricRollbackWorldAccess.Queries<?> queries,
                                                  long initialNanos, int maximumObjects) {
        requireSetup(); Objects.requireNonNull(data); Objects.requireNonNull(queries);
        if (!data.players().keySet().equals(expectedParticipants)) throw new IllegalArgumentException("Native bootstrap roster differs from the agreed session");
        if (maximumObjects < 1) throw new IllegalArgumentException("Native player object budget");
        if (queries.time() != data.worldTime()) throw new IllegalArgumentException("Player and world seeds belong to different ticks");
        var prepared = new TreeMap<UUID, FabricRollbackPlayerValues.Prepared>();
        data.players().forEach((id, player) -> {
            player.context().rebase(initialNanos);
            prepared.put(id, FabricRollbackPlayerValues.prepare(player.values()));
        });
        var world = new FabricRollbackWorldAccess(queries);
        var players = new TreeMap<UUID, FabricRollbackNativePlayerState>();
        var combat = new TreeMap<UUID, RollbackPlayerCombatData>();
        for (var entry : data.players().entrySet()) {
            var seed = entry.getValue(); var identity = seed.identity();
            PlayerEntity body = new Player(world.world(), profile(identity), GameMode.valueOf(identity.mode().name()));
            body.setId(identity.entityId());
            ((com.projectkorra.projectkorra.fabric.mixin.client.EntityRollbackControlAccess) body).rollback$random(new FabricRollbackPaperRandom(seed.randomSeed()));
            var state = new FabricRollbackNativePlayerState(body, world, maximumObjects);
            prepared.get(entry.getKey()).apply(state);
            FabricRollbackPlayerVitals.apply(state, seed.vitals());
            FabricRollbackPlayerItems.apply(state, seed.items());
            FabricRollbackPlayerContextData.apply(state, seed.context(), initialNanos);
            players.put(entry.getKey(), state); combat.put(entry.getKey(), seed.combat());
        }
        FabricRollbackPlayerCombatData.apply(players, combat);
        if (world.world().getTime() != data.worldTime()) throw new IllegalStateException("World advanced during roster import");
        world.sealPlayers();
        return new FabricRollbackRoster(world, players, prepared, data);
    }
    private FabricRollbackRoster(FabricRollbackWorldAccess world, Map<UUID, FabricRollbackNativePlayerState> players,
                                 Map<UUID, FabricRollbackPlayerValues.Prepared> values, RollbackRosterData source) {
        this.world = world; this.players = Collections.unmodifiableMap(new LinkedHashMap<>(players));
        this.values = Map.copyOf(values);
        var metadata = new TreeMap<UUID, Metadata>();
        source.players().forEach((id, player) -> metadata.put(id, new Metadata(player.identity(), player.randomSeed())));
        this.metadata = Map.copyOf(metadata);
    }
    public FabricRollbackWorldAccess world() { checkThread(); return world; }
    public Map<UUID, FabricRollbackNativePlayerState> players() { checkThread(); return players; }
    /** Includes client preferences for the future presentation/server-policy services. */
    public RollbackRosterData.Identity identity(UUID id) {
        checkThread(); return Objects.requireNonNull(metadata.get(id), "Unknown roster player").identity();
    }
    /** Capture at an idle boundary. Paper-only value fields currently retain their imported values. */
    public RollbackRosterData capture(long capturedNanos) {
        checkThread(); requireSetup();
        var result = new TreeMap<UUID, RollbackRosterData.Player>();
        players.forEach((id, state) -> {
            var seed = metadata.get(id); var actual = state.identity();
            if (!actual.uuid().equals(id) || actual.networkId() != seed.identity().entityId() || !actual.name().equals(seed.identity().name())) {
                throw new IllegalStateException("Native roster identity changed");
            }
            result.put(id, new RollbackRosterData.Player(seed.identity(), seed.randomSeed(), values.get(id).capture(state),
                    FabricRollbackPlayerVitals.capture(state), FabricRollbackPlayerItems.capture(state),
                    FabricRollbackPlayerContextData.capture(state, capturedNanos), FabricRollbackPlayerCombatData.capture(state)));
        });
        return new RollbackRosterData(world.world().getTime(), result);
    }
    private static GameProfile profile(RollbackRosterData.Identity identity) {
        var properties = ImmutableListMultimap.<String, Property>builder();
        for (var property : identity.properties()) properties.put(property.key(), new Property(property.name(), property.value(), property.signature()));
        return new GameProfile(identity.id(), identity.name(), new PropertyMap(properties.build()));
    }
    private void checkThread() { if (Thread.currentThread() != thread) throw new IllegalStateException("Native roster crossed threads"); }
    private static void requireSetup() {
        if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Import/capture the roster before replay");
    }
    private static final class Player extends FabricRollbackSimulatedPlayer {
        private final GameMode mode;
        Player(World world, GameProfile profile, GameMode mode) { super(world, profile); this.mode = mode; }
        @Override public GameMode getGameMode() { return mode; }
    }
}
