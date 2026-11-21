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
public final class FabricRollbackRoster implements com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell<FabricRollbackRoster.Retained> {
    public static final class Retained {
        private final FabricRollbackRoster owner;
        private final Map<UUID, FabricRollbackPlayerValues.Prepared> values;
        private final Map<UUID, net.minecraft.entity.Entity> history;
        private Retained(FabricRollbackRoster owner) { this.owner = owner; values = owner.values; history = owner.history; }
    }
    private final Thread thread = Thread.currentThread();
    private final FabricRollbackWorldAccess world;
    private final Map<UUID, FabricRollbackNativePlayerState> players;
    private Map<UUID, FabricRollbackPlayerValues.Prepared> values;
    private Map<UUID, net.minecraft.entity.Entity> history = Map.of();
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
        var references = new HashMap<UUID, net.minecraft.entity.Entity>();
        players.forEach((id, state) -> {
            var seed = metadata.get(id); var actual = state.identity();
            if (!actual.uuid().equals(id) || actual.networkId() != seed.identity().entityId() || !actual.name().equals(seed.identity().name())) {
                throw new IllegalStateException("Native roster identity changed");
            }
            result.put(id, new RollbackRosterData.Player(seed.identity(), seed.randomSeed(), values.get(id).capture(state),
                    FabricRollbackPlayerVitals.capture(state), FabricRollbackPlayerItems.capture(state),
                    FabricRollbackPlayerContextData.capture(state, capturedNanos), FabricRollbackPlayerCombatData.capture(state, entity -> {
                        var previous = references.putIfAbsent(entity.getUuid(), entity);
                        if (previous != null && previous != entity) throw new IllegalArgumentException("Combat pointers alias different native actors");
                    })));
        });
        for (var player : result.values()) for (var id : player.combat().references()) {
            if (players.containsKey(id) || references.containsKey(id)) continue;
            var body = history.get(id);
            if (body == null) body = world.actors().actor(id);
            if (body == null) throw new IllegalArgumentException("Missing combat actor " + id);
            references.put(id, body);
        }
        return new RollbackRosterData(world.world().getTime(), result, references.isEmpty() ? null : world.actors().history(references.values()));
    }
    /** Apply authoritative components to the same native bodies so logical player references retain their identity. */
    public void importConfirmed(RollbackRosterData confirmed, long capturedNanos) {
        importConfirmed(confirmed, capturedNanos, () -> { });
    }
    void importConfirmed(RollbackRosterData confirmed, long capturedNanos, Runnable installActors) {
        checkThread();
        if (RollbackClock.active() || !players.keySet().equals(confirmed.players().keySet())
                || world.world().getTime() != confirmed.worldTime())
            throw new IllegalArgumentException("Confirmed roster differs from the restored world/tick");
        var prepared = new TreeMap<UUID, FabricRollbackPlayerValues.Prepared>();
        var combat = new TreeMap<UUID, RollbackPlayerCombatData>();
        confirmed.players().forEach((id, seed) -> {
            var initial = metadata.get(id);
            if (!initial.identity().equals(seed.identity()) || initial.randomSeed() != seed.randomSeed())
                throw new IllegalArgumentException("Confirmed participant identity changed");
            seed.context().rebase(capturedNanos);
            prepared.put(id, FabricRollbackPlayerValues.prepareBoundary(seed.values()));
            combat.put(id, seed.combat());
        });
        var previous = new LinkedHashMap<FabricRollbackNativePlayerState, FabricRollbackNativePlayerState.Checkpoint>();
        players.values().forEach(body -> previous.put(body, body.captureRollbackState()));
        try {
            installActors.run();
            var combatActors = confirmed.combatActors() == null ? Map.<UUID, net.minecraft.entity.Entity>of()
                    : world.actors().importHistory(confirmed.combatActors());
            confirmed.players().forEach((id, seed) -> {
                var body = players.get(id);
                prepared.get(id).applyBoundary(body);
                FabricRollbackPlayerVitals.applyBoundary(body, seed.vitals());
                FabricRollbackPlayerItems.applyBoundary(body, seed.items());
                FabricRollbackPlayerContextData.applyBoundary(body, seed.context(), capturedNanos);
            });
            FabricRollbackPlayerCombatData.applyBoundary(players, combat, combatActors);
            history = combatActors;
            values = Map.copyOf(prepared);
        } catch (RuntimeException | Error failure) {
            previous.forEach(FabricRollbackNativePlayerState::restoreRollbackState);
            throw failure;
        }
    }
    @Override public Retained captureRollbackState() { checkThread(); return new Retained(this); }
    @Override public void restoreRollbackState(Retained saved) {
        checkThread();
        if (saved.owner != this) throw new IllegalArgumentException("Foreign native roster checkpoint");
        values = saved.values;
        history = saved.history;
    }
    @Override public List<?> rollbackReferences() { checkThread(); return List.copyOf(players.values()); }

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
