package com.projectkorra.projectkorra.prediction.state;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

/** Publishes causal ownership before an ability changes native gliding state. */
public final class GlidingStateSync {
    public interface Listener {
        void beforeWrite(CoreAbility ability, Player target, boolean gliding);
    }

    private static volatile Listener listener;

    private GlidingStateSync() {
    }

    public static void install(final Listener value) {
        listener = value;
    }

    public static void clear(final Listener value) {
        if (listener == value) listener = null;
    }

    public static void apply(final CoreAbility ability, final Player target,
                             final boolean gliding, final Runnable write) {
        if (write == null) return;
        final Listener current = listener;
        if (current != null && target != null) current.beforeWrite(ability, target, gliding);
        write.run();
    }
}
