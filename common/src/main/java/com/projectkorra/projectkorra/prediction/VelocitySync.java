package com.projectkorra.projectkorra.prediction;

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

    private VelocitySync() {
    }

    public static void install(final Listener newListener) {
        listener = newListener;
    }

    public static void clear(final Listener expected) {
        if (listener == expected) listener = null;
    }

    public static void publish(final Ability ability, final Entity target, final Vector velocity) {
        final Listener current = listener;
        if (current != null && ability != null && target != null && velocity != null) {
            current.onVelocity(ability, target, velocity.clone());
        }
    }

    @FunctionalInterface
    public interface Listener {
        void onVelocity(Ability ability, Entity target, Vector velocity);
    }
}
