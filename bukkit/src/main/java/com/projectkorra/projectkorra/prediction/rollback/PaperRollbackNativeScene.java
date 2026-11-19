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
            PaperRollbackWorldQueries.BlockEntities<?> blockEntities, PaperRollbackWorldServices.Events<?> events) {
        public Bindings(RollbackBlockStore.Rules blocks, RollbackItems items, RollbackWorld.Actions actions,
                RegistryAccess registries, PaperRollbackWorldServices.Events<?> events) {
            this(blocks, items, actions, new PaperRollbackBlockEntities(registries), events);
        }

        public Bindings {
            Objects.requireNonNull(blocks); Objects.requireNonNull(items); Objects.requireNonNull(actions);
            Objects.requireNonNull(blockEntities); Objects.requireNonNull(events);
        }
    }
    private final Thread thread = Thread.currentThread();
    private final RollbackWorld logical;
    private final PaperRollbackLighting lighting;
    private final PaperRollbackSpatial spatial;
    private final PaperRollbackWorldServices services;
    private final PaperRollbackWorldQueries queries;
    private final PaperRollbackWorldAccess world;
    private final Map<UUID, PaperRollbackNativePlayerState> roster;

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
        var geometry = new PaperRollbackGeometry(seed.minimumY(), seed.maximumY(), bindings.blockEntities());
        logical = new RollbackWorld(seed.identity(), seed.conditions(), seed.terrain(), lighting.rules(bindings.blocks(), this),
                maximumMutations, maximumEntities, bindings.items(), new RollbackWorldQueries(geometry), bindings.actions());
        if (bindings.blockEntities() instanceof PaperRollbackBlockEntities tiles) tiles.bind(logical.terrain());
        spatial = new PaperRollbackSpatial(logical, registries, seed.environment(), seed.border(), lighting);
        services = new PaperRollbackWorldServices(seed.settings(), logical, registries, features, spatial, bindings.events());
        queries = new PaperRollbackWorldQueries(logical, services, bindings.blockEntities());
        world = new PaperRollbackWorldAccess(queries, services, maximumChunks);
        roster = players.instantiate(world, epochNanos, playerServices);
        queries.bindRoster(world, roster);
    }
    public RollbackWorld logical() { check(); return logical; }
    public Map<UUID, PaperRollbackNativePlayerState> roster() { check(); return roster; }
    /** Detach this exact scene's native bodies at a settled tick, before returning to the live domain. */
    RollbackRosterData captureRoster(RollbackRosterData initial, long nanos) {
        check();
        if (!initial.players().keySet().equals(roster.keySet())) throw new IllegalArgumentException("Outgoing roster changed");
        var current = new TreeMap<UUID, RollbackRosterData.Player>();
        for (var entry : roster.entrySet()) {
            var body = (net.minecraft.server.level.ServerPlayer) entry.getValue().ownedPlayer();
            var original = initial.players().get(entry.getKey());
            if (body.level() != world.world() || !world.ownsPlayer(body)
                    || !body.getUUID().equals(entry.getKey()) || body.getId() != original.identity().entityId())
                throw new IllegalStateException("Private roster identity changed before handoff");
            // randomSeed is construction metadata. The live handoff writes intrinsic components only.
            var player = PaperRollbackPlayerSeed.capture(body, nanos).portable(original.randomSeed());
            current.put(entry.getKey(), player);
        }
        return new RollbackRosterData(world.world().getGameTime(), current);
    }

    public PaperRollbackWorldAccess world() { check(); return world; }
    public PaperRollbackWorldServices services() { check(); return services; }
    public PaperRollbackLighting lighting() { check(); return lighting; }
    /** Darkness is resolved after construction from the same rewindable world clock and weather. */
    @Override public int getAsInt() { check(); return spatial.environmentAttributes().skyDarkness(); }
    @Override public Void captureRollbackState() { check(); return null; }
    @Override public void restoreRollbackState(Void ignored) { check(); }
    @Override public List<?> rollbackReferences() {
        check(); var roots = new ArrayList<Object>(List.of(logical, lighting, spatial, services, queries));
        roots.addAll(roster.values()); return List.copyOf(roots);
    }
    private void check() { if (Thread.currentThread() != thread) throw new IllegalStateException("Native scene crossed threads"); }
}
