package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.listener.CommonAbilityLifecycleListener;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** One local schema assembly contract for live capture, private replicas and settled export. */
public final class RollbackGameplayGraph {
    public enum Side { LIVE, PRIVATE }
    private RollbackGameplayGraph() { }

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
        bindings.addAll(CoreAbility.rollbackAttributeBindings());
        bindings.add(Objects.requireNonNull(lifecycle).rollbackEffectsBinding());
        bindings.addAll(List.copyOf(additional));
        // Catalog rejects duplicate IDs and duplicate bound identities before any graph is captured.
        var catalog = RollbackGameplayCatalog.create(installed, bindings);
        return new RollbackGraphCodec(catalog, limits, roster, Objects.requireNonNull(views));
    }
}
