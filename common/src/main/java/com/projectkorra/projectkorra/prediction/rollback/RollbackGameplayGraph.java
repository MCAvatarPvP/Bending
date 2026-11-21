package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.listener.CommonAbilityLifecycleListener;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** One local schema assembly contract for live capture, private replicas and settled export. */
public final class RollbackGameplayGraph {
    public enum Side { LIVE, PRIVATE }
    private RollbackGameplayGraph() { }

    /** Prepared state is still uninstalled; its owner includes configuration and bending in domain roots. */
    public static final class Imported implements RollbackStateCell<RollbackBendingState> {
        private final RollbackConfiguration configuration;
        private final RollbackGraphCodec codec;
        private RollbackBendingState bending;

        public Imported(RollbackConfiguration configuration, RollbackGraphCodec codec, RollbackBendingState bending) {
            this.configuration = Objects.requireNonNull(configuration);
            this.codec = Objects.requireNonNull(codec);
            this.bending = Objects.requireNonNull(bending);
        }
        public RollbackConfiguration configuration() { return configuration; }
        public RollbackGraphCodec codec() { return codec; }
        public RollbackBendingState bending() { return bending; }
        public List<?> roots() { return List.of(configuration, this); }
        @Override public RollbackBendingState captureRollbackState() { return bending; }
        @Override public void restoreRollbackState(RollbackBendingState saved) { bending = saved; }
        @Override public List<?> rollbackReferences() { return List.of(bending); }

        public Runnable prepareRepair(byte[] bytes, long tick) {
            return prepareRepair(bytes, tick, List::of);
        }

        /** Resolve spawned actor handles after the recipient has installed this frontier's native membership. */
        public Runnable prepareRepair(byte[] bytes, long tick, Supplier<List<RollbackGraphCodec.Binding>> actors) {
            byte[] payload = bytes.clone();
            return () -> {
                if (!RollbackDomain.active() || RollbackClock.active()
                        || com.projectkorra.projectkorra.ProjectKorra.collisionManager != bending.collisions()
                        || !(com.projectkorra.projectkorra.platform.Platform.scheduler() instanceof RollbackScheduler scheduler)
                        || scheduler.tick() != tick)
                    throw new IllegalStateException("Gameplay repair belongs to another private frame");
                var repaired = RollbackBendingState.decodeRepair(bending.players().keySet(), payload, codec.withBindings(actors.get()));
                for (var entry : repaired.players().entrySet()) {
                    var current = bending.players().get(entry.getKey()).getPlayer();
                    if (current.handle() != entry.getValue().getPlayer().handle())
                        throw new IllegalStateException("Gameplay repair changed private body identity");
                }
                repaired.install(true);
                bending = repaired;
            };
        }
    }

    public static Imported decode(Collection<Class<?>> installed, RollbackGraphCodec.Limits limits,
            Set<UUID> expected, byte[] graph, RollbackConfiguration.Data settings,
            Map<String, com.projectkorra.projectkorra.configuration.Config> configurations,
            RollbackRosterBindings roster, CommonAbilityLifecycleListener lifecycle,
            Collection<RollbackGraphCodec.Binding> additional) {
        if (!Objects.requireNonNull(roster).participants().equals(Set.copyOf(expected)))
            throw new IllegalArgumentException("Gameplay import differs from the negotiated roster");
        var configuration = RollbackConfiguration.prepare(settings, configurations);
        var codec = create(installed, limits, Side.PRIVATE, roster, configuration, lifecycle, additional, new RollbackGraphViews());
        var bending = RollbackBendingState.decode(expected, graph, codec);
        return new Imported(configuration, codec, bending);
    }
    /** Registries compare these definitions by identity. Both endpoints resolve their own installed instances. */
    static List<RollbackGraphCodec.Binding> definitionBindings() {
        var bindings = new ArrayList<>(CoreAbility.rollbackAttributeBindings());
        bindings.add(new RollbackGraphCodec.Binding("common/ProjectKorra", ProjectKorra.class, ProjectKorra.plugin));
        var elements = new LinkedHashSet<Element>(Arrays.asList(Element.getAllElements()));
        elements.addAll(Arrays.asList(Element.getAllSubElements()));
        for (var element : elements) {
            bindings.add(new RollbackGraphCodec.Binding("element/" + element.getName(), element.getClass(), element));
        }
        return bindings;
    }

    /** Additional bindings supply loader/addon service identities; they cannot replace core bindings. */
    public static RollbackGraphCodec create(Collection<Class<?>> installed, RollbackGraphCodec.Limits limits,
            Side side, RollbackRosterBindings roster, RollbackConfiguration configuration,
            CommonAbilityLifecycleListener lifecycle, Collection<RollbackGraphCodec.Binding> additional,
            Supplier<? extends Function<Object, RollbackStateTransfer.Replacement>> views) {
        if (RollbackDomain.active() || RollbackClock.active())
            throw new IllegalStateException("Construct gameplay transfer before replay");
        Objects.requireNonNull(side); Objects.requireNonNull(roster); Objects.requireNonNull(configuration);
        var bindings = new ArrayList<>(roster.bindings());
        bindings.addAll(side == Side.LIVE ? configuration.sourceBindings() : configuration.bindings());
        bindings.addAll(definitionBindings());
        bindings.add(Objects.requireNonNull(lifecycle).rollbackEffectsBinding());
        bindings.addAll(List.copyOf(additional));
        // Catalog rejects duplicate IDs and duplicate bound identities before any graph is captured.
        var catalog = RollbackGameplayCatalog.create(installed, bindings);
        return new RollbackGraphCodec(catalog, limits, roster, Objects.requireNonNull(views));
    }
}
