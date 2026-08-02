package com.projectkorra.projectkorra.prediction.movement;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

/**
 * Loader-neutral observation point for ability-owned velocity writes.  The
 * velocity itself remains server authoritative and is still sent by vanilla;
 * this metadata lets an exact-prediction client identify its own echo without
 * mistaking unrelated knockback for it.
 */
public final class VelocitySync {
    private static volatile Listener listener;
    private static final ThreadLocal<Integer> COMMIT_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Entity> PREDICTED_REMOTE_TARGET = new ThreadLocal<>();

    private VelocitySync() {
    }

    public static void install(final Listener newListener) {
        listener = newListener;
    }

    public static void clear(final Listener expected) {
        if (listener == expected) {
            listener = null;
            COMMIT_DEPTH.remove();
            PREDICTED_REMOTE_TARGET.remove();
        }
    }

    public static void publish(final Ability ability, final Entity target, final Vector velocity) {
        final Listener current = listener;
        if (current != null && ability != null && target != null && velocity != null) {
            current.onVelocity(ability, target, velocity.clone());
        }
    }

    /** Prevents platform interception from deferring a standard velocity twice. */
    public static void commit(final Runnable write) {
        if (write == null) return;
        final int previous = COMMIT_DEPTH.get();
        COMMIT_DEPTH.set(previous + 1);
        try {
            write.run();
        } finally {
            if (previous == 0) COMMIT_DEPTH.remove();
            else COMMIT_DEPTH.set(previous);
        }
    }

    /**
     * Scopes an explicitly opted-in remote velocity write. Fabric's prediction
     * wrappers use this target identity to allow only the synchronous velocity
     * mutation; damage and all other remote state remain server-authoritative.
     */
    public static void commitPredictedRemote(final Entity target, final Runnable write) {
        if (write == null) return;
        final Entity previous = PREDICTED_REMOTE_TARGET.get();
        if (target == null) PREDICTED_REMOTE_TARGET.remove();
        else PREDICTED_REMOTE_TARGET.set(target);
        try {
            commit(write);
        } finally {
            if (previous == null) PREDICTED_REMOTE_TARGET.remove();
            else PREDICTED_REMOTE_TARGET.set(previous);
        }
    }

    public static boolean isPredictedRemoteTarget(final Entity target) {
        final Entity expected = PREDICTED_REMOTE_TARGET.get();
        if (expected == null || target == null) return false;
        if (expected == target) return true;
        return expected.getUniqueId() != null
                && expected.getUniqueId().equals(target.getUniqueId());
    }

    /** Captures legacy/addon velocity writes which bypass GeneralMethods. */
    public static void applyDirect(final Ability ability, final Entity target,
                                   final Vector velocity, final Runnable write) {
        if (write == null) return;
        if (COMMIT_DEPTH.get() > 0 || ability == null || target == null || velocity == null
                || ability.getPlayer() == null) {
            write.run();
            return;
        }
        // Direct addon/core writes still need an ownership receipt; otherwise
        // the predicting caster applies its local impulse and the vanilla echo.
        publish(ability, target, velocity);
        commit(write);
    }

    @FunctionalInterface
    public interface Listener {
        void onVelocity(Ability ability, Entity target, Vector velocity);
    }
}
