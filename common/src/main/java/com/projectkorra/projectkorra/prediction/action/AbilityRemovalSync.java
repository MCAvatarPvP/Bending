package com.projectkorra.projectkorra.prediction.action;

import com.projectkorra.projectkorra.ability.CoreAbility;

import java.util.UUID;

/**
 * Loader-neutral notification that the authoritative runtime ended an ability.
 */
public final class AbilityRemovalSync {
    private static volatile Listener listener;
    private static final ThreadLocal<Integer> EXTERNAL_CAUSE_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> AUTHORITATIVE_REJECTION_DEPTH = ThreadLocal.withInitial(() -> 0);

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
            // administrative cleanup do not. A scoped validation rejection is
            // also authoritative because independently scheduled charge/world
            // checks can disagree at a boundary. The client may predict an
            // ordinary close, but must obey either authoritative cause.
            current.onRemoved(ability, isExternallyCaused(ability), isAuthoritativeRejection());
        }
    }

    /**
     * Publishes the point at which a live ability changes controller.
     *
     * <p>The old predicting client still owns a local copy of the ability. It
     * cannot observe another player's redirect input, so authority must retire
     * that exact copy without ending the transferred server instance.</p>
     */
    public static void ownerTransferred(final CoreAbility ability, final UUID previousOwner,
                                        final UUID nextOwner) {
        final Listener current = listener;
        if (current != null && ability != null && previousOwner != null
                && nextOwner != null && !previousOwner.equals(nextOwner)) {
            current.onOwnerTransferred(ability, previousOwner, nextOwner);
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

    /**
     * Marks a server validation failure which must retire an optimistic local
     * lifecycle even though the ability removed itself from its own progress.
     */
    public static void runAuthoritativeRejection(final Runnable action) {
        if (action == null) return;
        final int previous = AUTHORITATIVE_REJECTION_DEPTH.get();
        AUTHORITATIVE_REJECTION_DEPTH.set(previous + 1);
        try {
            action.run();
        } finally {
            if (previous == 0) AUTHORITATIVE_REJECTION_DEPTH.remove();
            else AUTHORITATIVE_REJECTION_DEPTH.set(previous);
        }
    }

    static boolean isExternallyCaused(final CoreAbility ability) {
        return EXTERNAL_CAUSE_DEPTH.get() > 0 || AUTHORITATIVE_REJECTION_DEPTH.get() > 0
                || AbilityExecutionContext.current() != ability;
    }

    static boolean isAuthoritativeRejection() {
        return AUTHORITATIVE_REJECTION_DEPTH.get() > 0;
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

        default void onRemoved(final CoreAbility ability, final boolean externallyCaused,
                               final boolean predictionRejected) {
            onRemoved(ability, externallyCaused);
        }

        default void onOwnerTransferred(final CoreAbility ability, final UUID previousOwner,
                                        final UUID nextOwner) {
        }
    }
}
