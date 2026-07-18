package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ability.CoreAbility;

import java.util.UUID;

/**
 * Loader-neutral notification that the authoritative runtime ended an ability.
 */
public final class AbilityRemovalSync {
    private static volatile Listener listener;
    private static final ThreadLocal<Integer> EXTERNAL_CAUSE_DEPTH = ThreadLocal.withInitial(() -> 0);

    private AbilityRemovalSync() {
    }

    public static void install(Listener value) {
        listener = value;
    }

    public static void clear(Listener value) {
        if (listener == value) listener = null;
    }

    public static void publish(CoreAbility ability) {
        Listener current = listener;
        if (current != null && ability != null) {
            // A normal range/duration/input close runs inside the ability's
            // own progress context. Collisions, another move, toggles, and
            // administrative cleanup do not. The client may predict the
            // former lifecycle, but the latter is authoritative information
            // it cannot derive from its local world alone.
            current.onRemoved(ability, isExternallyCaused(ability));
        }
    }

    /**
     * Marks removals performed by an authoritative interaction between
     * abilities, such as the collision manager. The local owner cannot infer
     * collisions with another player's server-only ability instance.
     */
    public static void runExternalCause(final Runnable action) {
        if (action == null) return;
        final int previous = EXTERNAL_CAUSE_DEPTH.get();
        EXTERNAL_CAUSE_DEPTH.set(previous + 1);
        try {
            action.run();
        } finally {
            if (previous == 0) EXTERNAL_CAUSE_DEPTH.remove();
            else EXTERNAL_CAUSE_DEPTH.set(previous);
        }
    }

    static boolean isExternallyCaused(final CoreAbility ability) {
        return EXTERNAL_CAUSE_DEPTH.get() > 0 || AbilityExecutionContext.current() != ability;
    }

    /** True only for an explicitly marked ability collision, not ordinary input cleanup. */
    public static boolean isCollisionCause() {
        return EXTERNAL_CAUSE_DEPTH.get() > 0;
    }

    /**
     * Stable cross-loader identity for an ability implementation.
     *
     * <p>{@link CoreAbility#getName()} is a display/input name and is not
     * unique. For example, {@code WaterSpout} and its short-lived
     * {@code WaterSpoutWave} probe both return {@code "WaterSpout"}. A
     * delayed removal must therefore identify the concrete implementation or
     * it can close a different live ability created by the same input.</p>
     */
    public static String typeId(CoreAbility ability) {
        return ability == null ? "" : typeId(ability.getClass());
    }

    public static String typeId(Class<? extends CoreAbility> abilityType) {
        return abilityType == null ? "" : abilityType.getName();
    }

    public static boolean isType(CoreAbility ability, String typeId) {
        return ability != null && typeId != null && typeId.equals(ability.getClass().getName());
    }

    /**
     * Counts the live instances of one concrete implementation for one owner.
     * Display names are deliberately insufficient here: WaterSpout and
     * WaterSpoutWave both expose {@code "WaterSpout"}.
     */
    public static int activeTypeCount(final UUID playerId, final String typeId) {
        if (playerId == null || typeId == null || typeId.isBlank()) return 0;
        int count = 0;
        for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
            if (ability == null || ability.isRemoved() || ability.getPlayer() == null) continue;
            if (playerId.equals(ability.getPlayer().getUniqueId()) && isType(ability, typeId)) count++;
        }
        return count;
    }

    public interface Listener {
        void onRemoved(CoreAbility ability, boolean externallyCaused);
    }
}
