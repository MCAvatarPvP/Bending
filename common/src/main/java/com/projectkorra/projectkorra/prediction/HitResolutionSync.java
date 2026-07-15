package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;

/**
 * Loader-neutral gate for effects produced by a predicted hit claim.
 *
 * <p>The normal path remains synchronous. A prediction server may take
 * ownership of an effect and commit it after the defender's bounded network
 * reaction window has elapsed.</p>
 */
public final class HitResolutionSync {
    private static volatile Listener listener;

    private HitResolutionSync() {
    }

    public static void install(final Listener newListener) {
        listener = newListener;
    }

    public static void clear(final Listener expected) {
        if (listener == expected) listener = null;
    }

    public static boolean defer(final Effect effect, final Ability ability, final Entity target,
                                final Runnable commit) {
        final Listener current = listener;
        return current != null && ability != null && target != null && commit != null
                && current.defer(effect, ability, target, commit);
    }

    public enum Effect {
        DAMAGE,
        VELOCITY,
        STAMINA,
        SOUND
    }

    @FunctionalInterface
    public interface Listener {
        boolean defer(Effect effect, Ability ability, Entity target, Runnable commit);
    }
}
