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

    private VelocitySync() {
    }

    public static void install(final Listener newListener) {
        listener = newListener;
    }

    public static void clear(final Listener expected) {
        if (listener == expected) {
            listener = null;
            COMMIT_DEPTH.remove();
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
