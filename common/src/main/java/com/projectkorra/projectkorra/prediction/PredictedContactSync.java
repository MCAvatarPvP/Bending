package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;

/**
 * Keeps remote entity state authoritative without interrupting a locally
 * predicted ability's own visual and world lifecycle.
 */
public final class PredictedContactSync {
    private static final ThreadLocal<CoreAbility> FORCED_REMOVAL = new ThreadLocal<>();
    private static volatile Listener listener;

    private PredictedContactSync() {
    }

    /**
     * Returns true when a predicted client attempted to mutate a remote
     * entity. The caller must suppress that mutation and continue its local
     * visual/world pass.
     *
     * <p>The optional listener may report the contact as evidence. It never
     * grants damage, velocity, or lifecycle authority; the server must rewind
     * and validate it through the real ability query.</p>
     */
    public static boolean mark(final Ability ability, final Entity target) {
        if (CooldownSync.isAuthoritative() || !(ability instanceof CoreAbility coreAbility)
                || target == null || coreAbility.getPlayer() == null
                || target.getUniqueId().equals(coreAbility.getPlayer().getUniqueId())) {
            return false;
        }
        // The mutation itself is suppressed by the caller, but the ability's
        // world/visual pass must finish. Throwing here used to abandon loops in
        // Torrent, WaterFlow and Discharge as soon as another player entered a
        // collider, leaving partial TempBlock shapes and abruptly stopped
        // effects. The client's normal range/duration/removal rules remain the
        // lifecycle authority; retaining a permanent "awaiting server" flag
        // made terminal AirBurst rays and other projectiles immortal.
        final Listener current = listener;
        if (current != null) {
            try {
                current.onPredictedContact(coreAbility, target);
            } catch (final RuntimeException ignored) {
                // Evidence transport must never interrupt the visual/world pass.
            }
        }
        return true;
    }

    public static void install(final Listener next) {
        listener = next;
    }

    public static void clear(final Listener expected) {
        if (listener == expected) listener = null;
    }

    /** Runs explicit local cleanup or server reconciliation. */
    public static void forceRemoval(final CoreAbility ability, final Runnable removal) {
        if (removal == null) return;
        final CoreAbility previous = FORCED_REMOVAL.get();
        if (ability == null) FORCED_REMOVAL.remove();
        else FORCED_REMOVAL.set(ability);
        try {
            removal.run();
        } finally {
            if (previous == null) FORCED_REMOVAL.remove();
            else FORCED_REMOVAL.set(previous);
        }
    }

    public static boolean isForcedRemoval(final CoreAbility ability) {
        return ability != null && FORCED_REMOVAL.get() == ability;
    }

    @FunctionalInterface
    public interface Listener {
        void onPredictedContact(CoreAbility ability, Entity target);
    }
}
