package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Prevents stale remote-entity snapshots from owning a locally predicted
 * ability's lifecycle. Once a predicted ability observes a remote contact,
 * only reconciliation from the authoritative server may remove that instance.
 */
public final class PredictedContactSync {
    private static final Map<CoreAbility, Boolean> AWAITING_AUTHORITY =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Integer> FORCED_REMOVAL_DEPTH =
            ThreadLocal.withInitial(() -> 0);
    private static volatile Listener listener;

    private PredictedContactSync() {
    }

    public static void install(final Listener newListener) {
        listener = newListener;
    }

    /** Returns true when a non-authoritative remote target was claimed. */
    public static boolean mark(final Ability ability, final Entity target) {
        if (CooldownSync.isAuthoritative() || !(ability instanceof CoreAbility coreAbility)
                || target == null || coreAbility.getPlayer() == null
                || target.getUniqueId().equals(coreAbility.getPlayer().getUniqueId())) {
            return false;
        }
        AWAITING_AUTHORITY.put(coreAbility, Boolean.TRUE);
        final Listener current = listener;
        if (current != null) current.onPredictedContact(target);
        // Abort the remainder of this predicted progress call before an
        // ability-specific callback can set terminal flags, remove child
        // entities, or run cleanup ahead of CoreAbility#remove.
        if (AbilityExecutionContext.current() == coreAbility) {
            throw Abort.INSTANCE;
        }
        return true;
    }

    public static boolean suppressRemoval(final CoreAbility ability) {
        return ability != null && !CooldownSync.isAuthoritative()
                && FORCED_REMOVAL_DEPTH.get() == 0
                && AWAITING_AUTHORITY.containsKey(ability);
    }

    /** Runs rollback or server reconciliation without the prediction guard. */
    public static void forceRemoval(final CoreAbility ability, final Runnable removal) {
        if (removal == null) return;
        AWAITING_AUTHORITY.remove(ability);
        final int previous = FORCED_REMOVAL_DEPTH.get();
        FORCED_REMOVAL_DEPTH.set(previous + 1);
        try {
            removal.run();
        } finally {
            if (previous == 0) FORCED_REMOVAL_DEPTH.remove();
            else FORCED_REMOVAL_DEPTH.set(previous);
        }
    }

    public static void clear() {
        listener = null;
        AWAITING_AUTHORITY.clear();
        FORCED_REMOVAL_DEPTH.remove();
    }

    @FunctionalInterface
    public interface Listener {
        void onPredictedContact(Entity target);
    }

    static final class Abort extends RuntimeException {
        private static final Abort INSTANCE = new Abort();

        private Abort() {
            super(null, null, false, false);
        }
    }
}
