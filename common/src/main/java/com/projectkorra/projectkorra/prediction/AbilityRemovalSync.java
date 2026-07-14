package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ability.CoreAbility;

/**
 * Loader-neutral notification that the authoritative runtime ended an ability.
 */
public final class AbilityRemovalSync {
    private static volatile Listener listener;

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
        if (current != null && ability != null) current.onRemoved(ability);
    }

    public interface Listener {
        void onRemoved(CoreAbility ability);
    }
}
