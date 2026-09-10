package com.projectkorra.projectkorra.prediction.authority;

import com.projectkorra.projectkorra.prediction.state.CooldownSync;

/** Server-confirmed effects which must also reach players predicting their abilities. */
public final class AuthoritativeEffects {
    private static final ThreadLocal<Integer> BROADCAST_DEPTH = ThreadLocal.withInitial(() -> 0);

    private AuthoritativeEffects() {
    }

    public static boolean isBroadcasting() {
        return BROADCAST_DEPTH.get() > 0;
    }

    public static void run(final Runnable effects) {
        if (!CooldownSync.isAuthoritative() || effects == null) return;
        final int previous = BROADCAST_DEPTH.get();
        BROADCAST_DEPTH.set(previous + 1);
        try {
            effects.run();
        } finally {
            if (previous == 0) BROADCAST_DEPTH.remove();
            else BROADCAST_DEPTH.set(previous);
        }
    }
}
