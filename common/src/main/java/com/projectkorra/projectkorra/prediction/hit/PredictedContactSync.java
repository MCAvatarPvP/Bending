package com.projectkorra.projectkorra.prediction.hit;

import com.projectkorra.projectkorra.prediction.rollback.RollbackDomain;
import com.projectkorra.projectkorra.prediction.authority.PredictionServices;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
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
        if (RollbackDomain.active()) {
            // Replayed contact mutates the logical victim on either loader. Never
            // let a leaked live target acquire that permission through this bypass.
            if (target != null) RollbackEntityBody.logicalBody(target);
            if (ability != null && ability.getPlayer() != null) {
                RollbackEntityBody.logicalBody(ability.getPlayer());
            }
            return false;
        }
        if (CooldownSync.isAuthoritative() || !(ability instanceof CoreAbility coreAbility)
                || target == null || coreAbility.getPlayer() == null
                || target.getUniqueId().equals(coreAbility.getPlayer().getUniqueId())) {
            return false;
        }
        final Listener current = PredictionServices.current(Listener.class, listener);
        if (current != null) {
            try {
                // Ability-owned projectiles and displays have UUIDs distinct
                // from their caster, but remain part of the local prediction.
                // Let their normal wrappers apply velocity and other state.
                if (current.isLocallyOwned(target)) {
                    return false;
                }
            } catch (final RuntimeException ignored) {
                if (PredictionServices.active()) throw ignored;
                // A failed ownership lookup must retain the safe remote-state
                // default below.
            }
        }
        // The mutation itself is suppressed by the caller, but the ability's
        // world/visual pass must finish. Throwing here used to abandon loops in
        // Torrent, WaterFlow and Discharge as soon as another player entered a
        // collider, leaving partial TempBlock shapes and abruptly stopped
        // effects. The client's normal range/duration/removal rules remain the
        // lifecycle authority; retaining a permanent "awaiting server" flag
        // made terminal AirBurst rays and other projectiles immortal.
        if (current != null) {
            try {
                current.onPredictedContact(coreAbility, target);
            } catch (final RuntimeException ignored) {
                if (PredictionServices.active()) throw ignored;
                // Evidence transport must never interrupt the visual/world pass.
            }
        }
        return true;
    }

    public static void install(final Listener next) {
        PredictionServices.requireGlobalMutation();
        listener = next;
    }

    public static void clear(final Listener expected) {
        PredictionServices.requireGlobalMutation();
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

        /**
         * Returns whether a non-player target belongs to the current local
         * prediction action rather than to authoritative world state.
         */
        default boolean isLocallyOwned(final Entity target) {
            return false;
        }
    }
}
