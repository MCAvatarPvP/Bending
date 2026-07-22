package com.projectkorra.projectkorra.prediction.hit;

import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;

/** Effects emitted only by an authoritative server contact. */
public final class ConfirmedHitEffects {
    private static final ThreadLocal<Integer> AUTHORITATIVE_SOUND_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    private ConfirmedHitEffects() {
    }

    /**
     * Broadcasts an authoritative impact sound. Exact clients never play this
     * speculatively, so the server broadcast is the one audible confirmation.
     */
    public static void sound(final Ability ability, final Entity target, final Runnable playSound) {
        if (!CooldownSync.isAuthoritative() || ability == null || target == null || playSound == null) return;
        broadcastSound(playSound);
    }

    public static boolean isBroadcastingAuthoritativeSound() {
        return AUTHORITATIVE_SOUND_DEPTH.get() > 0;
    }

    static void broadcastSound(final Runnable sound) {
        AUTHORITATIVE_SOUND_DEPTH.set(AUTHORITATIVE_SOUND_DEPTH.get() + 1);
        try {
            sound.run();
        } finally {
            final int remaining = AUTHORITATIVE_SOUND_DEPTH.get() - 1;
            if (remaining <= 0) AUTHORITATIVE_SOUND_DEPTH.remove();
            else AUTHORITATIVE_SOUND_DEPTH.set(remaining);
        }
    }
}
