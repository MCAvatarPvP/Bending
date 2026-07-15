package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;

/** Effects that must exist only after the authoritative hit decision commits. */
public final class ConfirmedHitEffects {
    private static final ThreadLocal<Integer> AUTHORITATIVE_SOUND_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    private ConfirmedHitEffects() {
    }

    /**
     * Queues an impact sound with damage/velocity/stamina for the same contact.
     * Exact clients never play this speculatively; the server broadcast is the
     * one audible confirmation for every nearby player, including the caster.
     */
    public static void sound(final Ability ability, final Entity target, final Runnable playSound) {
        if (!CooldownSync.isAuthoritative() || ability == null || target == null || playSound == null) return;
        final Runnable commit = () -> broadcastSound(playSound);
        if (!HitResolutionSync.defer(HitResolutionSync.Effect.SOUND, ability, target, commit)) {
            commit.run();
        }
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
