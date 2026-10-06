package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.Identifier;
import java.util.*;
import java.util.function.IntSupplier;

/** Assembles the captured arena and roster before the bending graph is imported. */
public final class FabricRollbackNativeScene implements RollbackStateCell<Void>, IntSupplier {
    /** Gameplay callbacks remain mandatory; none may retain a live world or publish replay outputs. */
    public record Bindings(RollbackBlockStore.Rules blocks, RollbackItems items, RollbackWorld.Actions actions,
            FabricRollbackWorldQueries.BlockEntities<?> blockEntities, FabricRollbackWorldServices.Events<?> events,
            Scoreboard scoreboard) {
        public Bindings {
            Objects.requireNonNull(blocks); Objects.requireNonNull(items); Objects.requireNonNull(actions);
            Objects.requireNonNull(blockEntities); Objects.requireNonNull(events); Objects.requireNonNull(scoreboard);
        }
    }
    private final Thread thread = Thread.currentThread();
    private final RollbackWorld logical;
    private final FabricRollbackLighting lighting;
    private final FabricRollbackSpatial spatial;
    private final FabricRollbackWorldServices services;
    private final FabricRollbackWorldQueries queries;
    private final FabricRollbackRoster roster;

    public FabricRollbackNativeScene(RollbackWorldSeed seed, RollbackRosterData players, Set<UUID> participants,
            DynamicRegistryManager.Immutable registries, FeatureSet features, Bindings bindings,
            long epochNanos, int maximumMutations, int maximumEntities, int maximumPlayerObjects) {
        if (RollbackDomain.active() || RollbackClock.active()) throw new IllegalStateException("Assemble native scene before replay");
        Objects.requireNonNull(seed); Objects.requireNonNull(players); Objects.requireNonNull(bindings);
        if (!players.players().keySet().equals(participants) || players.worldTime() != seed.settings().gameTime())
            throw new IllegalArgumentException("World and roster seed differ");
        if (maximumMutations < 1 || maximumEntities < participants.size() || maximumPlayerObjects < 1)
            throw new IllegalArgumentException("Native scene budgets");
        var dimension = registries.getOrThrow(RegistryKeys.DIMENSION_TYPE).getEntry(Identifier.of(seed.environment().dimensionType()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown captured dimension")).value();
        lighting = new FabricRollbackLighting(seed.terrain(), seed.requireLight(), registries, dimension.hasSkyLight());
        var geometry = new FabricRollbackGeometry(seed.minimumY(), seed.maximumY(), bindings.blockEntities());
        logical = new RollbackWorld(seed.identity(), seed.conditions(), seed.terrain(), lighting.rules(bindings.blocks(), this),
                maximumMutations, maximumEntities, bindings.items(), new RollbackWorldQueries(geometry), bindings.actions());
        spatial = new FabricRollbackSpatial(logical, registries, seed.environment(), seed.border(), lighting, bindings.scoreboard());
        services = new FabricRollbackWorldServices(seed.settings(), logical, registries, features, spatial, bindings.events());
        queries = new FabricRollbackWorldQueries(logical, services, bindings.blockEntities());
        roster = FabricRollbackRoster.instantiate(players, participants, queries, epochNanos, maximumPlayerObjects);
        queries.bindRoster(roster);
    }
    public RollbackWorld logical() { check(); return logical; }
    public FabricRollbackRoster roster() { check(); return roster; }
    public FabricRollbackWorldServices services() { check(); return services; }
    public FabricRollbackLighting lighting() { check(); return lighting; }
    /** Darkness is resolved after construction from the same rewindable world clock and weather. */
    @Override public int getAsInt() { check(); return spatial.environmentAttributes().skyDarkness(); }
    @Override public Void captureRollbackState() { check(); return null; }
    @Override public void restoreRollbackState(Void ignored) { check(); }
    @Override public List<?> rollbackReferences() {
        check(); var roots = new ArrayList<Object>(List.of(logical, lighting, spatial, services, queries));
        roots.addAll(roster.players().values()); return List.copyOf(roots);
    }
    private void check() { if (Thread.currentThread() != thread) throw new IllegalStateException("Native scene crossed threads"); }
}
