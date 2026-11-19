package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.*;
import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.resources.Identifier;
import java.util.*;
import java.util.function.IntSupplier;

/** Assembles the captured arena and roster before the bending graph is imported. */
public final class PaperRollbackNativeScene implements RollbackStateCell<Void>, IntSupplier {
    /** Gameplay callbacks remain mandatory; none may retain a live world or publish replay outputs. */
    public record Bindings(RollbackBlockStore.Rules blocks, RollbackItems items, RollbackWorld.Actions actions,
            PaperRollbackWorldQueries.BlockEntities<?> blockEntities, PaperRollbackWorldServices.Events<?> events,
            java.util.function.Consumer<Runnable> compile) {
        public Bindings(RollbackBlockStore.Rules blocks, RollbackItems items, RollbackWorld.Actions actions,
                PaperRollbackWorldQueries.BlockEntities<?> blockEntities, PaperRollbackWorldServices.Events<?> events) {
            this(blocks, items, actions, blockEntities, events, Runnable::run);
        }
        public Bindings(RollbackBlockStore.Rules blocks, RollbackItems items, RollbackWorld.Actions actions,
                RegistryAccess registries, PaperRollbackWorldServices.Events<?> events) {
            this(blocks, items, actions, new PaperRollbackBlockEntities(registries), events);
        }

        public Bindings {
            Objects.requireNonNull(blocks); Objects.requireNonNull(items); Objects.requireNonNull(actions);
            Objects.requireNonNull(blockEntities); Objects.requireNonNull(events); Objects.requireNonNull(compile);
        }
    }
    private final Thread thread = Thread.currentThread();
    private final RollbackWorld logical;
    private final PaperRollbackLighting lighting;
    private PaperRollbackScheduledTicks ticks;
    private PaperRollbackItemLifecycle itemLifecycle;
    private final PaperRollbackGeometry geometry;
    private final PaperRollbackSpatial spatial;
    private final PaperRollbackWorldServices services;
    private final PaperRollbackWorldQueries queries;
    private final PaperRollbackWorldAccess world;
    private Map<UUID, PaperRollbackNativePlayerState> roster;

    /** Builds a match scene with Neptune's last-stage lethal-hit policy installed before its first checkpoint. */
    public PaperRollbackNativeScene(RollbackWorldSeed seed, PaperRollbackRosterSeed players, RollbackRound round,
            RegistryAccess.Frozen registries, FeatureFlagSet features, Bindings bindings,
            Map<UUID, PaperRollbackRosterSeed.Services> playerServices,
            long epochNanos, int maximumMutations, int maximumEntities, int maximumChunks) {
        this(seed, players, round.participants(), registries, features, bindings, playerServices,
                epochNanos, maximumMutations, maximumEntities, maximumChunks);
        world.bindRound(round);
    }

    public PaperRollbackNativeScene(RollbackWorldSeed seed, PaperRollbackRosterSeed players, Set<UUID> participants,
            RegistryAccess.Frozen registries, FeatureFlagSet features, Bindings bindings,
            Map<UUID, PaperRollbackRosterSeed.Services> playerServices,
            long epochNanos, int maximumMutations, int maximumEntities, int maximumChunks) {
        this(seed, players, participants, registries, features, bindings, playerServices, maximumMutations, maximumEntities, maximumChunks);
        finish(seed, bindings, players.instantiate(world, epochNanos, playerServices));
    }

    /** Construct the bound world, then import one native player per poll before publishing it. */
    static java.util.function.Supplier<PaperRollbackNativeScene> prepare(RollbackWorldSeed seed, PaperRollbackRosterSeed players,
            RollbackRound round, RegistryAccess.Frozen registries, FeatureFlagSet features, Bindings bindings,
            Map<UUID, PaperRollbackRosterSeed.Services> playerServices, long epochNanos,
            int maximumMutations, int maximumEntities, int maximumChunks) {
        long time = RollbackTiming.start();
        var scene = new PaperRollbackNativeScene(seed, players, round.participants(), registries, features, bindings, playerServices,
                maximumMutations, maximumEntities, maximumChunks);
        var roster = players.prepare(scene.world, epochNanos, playerServices);
        RollbackTiming.finish(time, "scene.nativeWorld");
        return () -> {
            long start = RollbackTiming.start();
            if (roster.advance()) {
                RollbackTiming.finish(start, "scene.nativePlayer");
                return null;
            }
            scene.finish(seed, bindings, roster.finish());
            scene.world.bindRound(round);
            RollbackTiming.finish(start, "scene.nativeBindings");
            return scene;
        };
    }

    private PaperRollbackNativeScene(RollbackWorldSeed seed, PaperRollbackRosterSeed players, Set<UUID> participants,
            RegistryAccess.Frozen registries, FeatureFlagSet features, Bindings bindings,
            Map<UUID, PaperRollbackRosterSeed.Services> playerServices,
            int maximumMutations, int maximumEntities, int maximumChunks) {
        if (!TickThread.isTickThread() || RollbackDomain.active() || RollbackClock.active()) throw new IllegalStateException("Assemble native scene before replay");
        Objects.requireNonNull(seed); Objects.requireNonNull(players); Objects.requireNonNull(bindings);
        playerServices = Map.copyOf(playerServices);
        if (!playerServices.keySet().equals(participants)) throw new IllegalArgumentException("Player service roster differs");
        if (!players.participants().equals(participants) || players.worldTime() != seed.settings().gameTime())
            throw new IllegalArgumentException("World and roster seed differ");
        if (maximumMutations < 1 || maximumEntities < participants.size() || maximumChunks < 1)
            throw new IllegalArgumentException("Native scene budgets");
        var dimension = registries.lookupOrThrow(Registries.DIMENSION_TYPE).get(Identifier.parse(seed.environment().dimensionType()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown captured dimension")).value();
        lighting = new PaperRollbackLighting(seed.terrain(), seed.requireLight(), registries, dimension.hasSkyLight());
        geometry = new PaperRollbackGeometry(seed.minimumY(), seed.maximumY(), bindings.blockEntities());
        logical = new RollbackWorld(seed.identity(), seed.conditions(), seed.terrain(), lighting.rules(bindings.blocks(), this),
                maximumMutations, maximumEntities, bindings.items(), new RollbackWorldQueries(geometry), bindings.actions());
        if (bindings.blockEntities() instanceof PaperRollbackBlockEntities tiles) tiles.bind(logical.terrain());
        spatial = new PaperRollbackSpatial(logical, registries, seed.environment(), seed.border(), lighting);
        services = new PaperRollbackWorldServices(seed.settings(), logical, registries, features, spatial, bindings.events());
        queries = new PaperRollbackWorldQueries(logical, services, bindings.blockEntities());
        world = new PaperRollbackWorldAccess(queries, services, maximumChunks, bindings.compile());
    }

    private void finish(RollbackWorldSeed seed, Bindings bindings, Map<UUID, PaperRollbackNativePlayerState> bodies) {
        if (roster != null) throw new IllegalStateException("Native scene already assembled");
        roster = bodies;
        queries.bindRoster(world, roster);
        world.scoreboards().importCombatTeams(seed.settings().interactions().teams(), roster);
        ticks = new PaperRollbackScheduledTicks(world, seed.ticks());
        services.bindTicks(ticks);
        world.bindTicks(ticks);
        if (bindings.blocks() instanceof PaperRollbackBlockRules nativeRules) {
            nativeRules.bind(this, geometry);
            world.bindTerrain(nativeRules);
        }
        itemLifecycle = new PaperRollbackItemLifecycle(world);
        world.finishNativePreparation();
    }
    public RollbackWorld logical() { check(); return logical; }
    public Map<UUID, PaperRollbackNativePlayerState> roster() { check(); return roster; }
    /** Detach this exact scene's native bodies at a settled tick, before returning to the live domain. */
    RollbackRosterData captureRoster(RollbackRosterData initial, long nanos) {
        check();
        if (!initial.players().keySet().equals(roster.keySet())) throw new IllegalArgumentException("Outgoing roster changed");
        var current = new TreeMap<UUID, RollbackRosterData.Player>();
        var rows = new java.util.HashMap<UUID, RollbackActorData.Row>();
        var sources = new java.util.HashMap<UUID, net.minecraft.world.entity.Entity>();
        java.util.function.Consumer<net.minecraft.world.entity.Entity> history = entity -> {
            var identity = world.actorIdentity(entity);
            if (identity == null) throw new IllegalArgumentException("Combat history references a foreign actor");
            var previous = sources.putIfAbsent(identity.uuid(), entity);
            if (previous != null && previous != entity) throw new IllegalArgumentException("Combat actor identity alias conflict");
            rows.computeIfAbsent(identity.uuid(), id ->
                    new RollbackActorData.Row(identity, PaperRollbackActor.exportState(entity, identity)));
        };
        for (var entry : roster.entrySet()) {
            var body = (net.minecraft.server.level.ServerPlayer) entry.getValue().ownedPlayer();
            var original = initial.players().get(entry.getKey());
            if (body.level() != world.world() || !world.ownsPlayer(body)
                    || !body.getUUID().equals(entry.getKey()) || body.getId() != original.identity().entityId())
                throw new IllegalStateException("Private roster identity changed before handoff");
            // randomSeed is construction metadata. The live handoff writes intrinsic components only.
            var player = PaperRollbackPlayerSeed.capture(body, nanos, history).portable(original.randomSeed());
            current.put(entry.getKey(), player);
        }
        return new RollbackRosterData(world.world().getGameTime(), current, rows.isEmpty() ? null : world.actors().history(rows.values()));
    }

    public PaperRollbackWorldAccess world() { check(); return world; }
    public PaperRollbackWorldServices services() { check(); return services; }
    PaperRollbackItemLifecycle itemLifecycle() { check(); return itemLifecycle; }
    PaperRollbackScheduledTicks ticks() { check(); return ticks; }
    public PaperRollbackLighting lighting() { check(); return lighting; }
    /** Darkness is resolved after construction from the same rewindable world clock and weather. */
    @Override public int getAsInt() { check(); return spatial.environmentAttributes().skyDarkness(); }
    @Override public Void captureRollbackState() { check(); return null; }
    @Override public void restoreRollbackState(Void ignored) { check(); }
    @Override public List<?> rollbackReferences() {
        check(); var roots = new ArrayList<Object>(List.of(logical, lighting, spatial, services, queries, ticks));
        roots.addAll(roster.values()); return List.copyOf(roots);
    }
    private void check() { if (Thread.currentThread() != thread) throw new IllegalStateException("Native scene crossed threads"); }
}
