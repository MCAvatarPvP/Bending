package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.util.Cooldown;

/**
 * Aligns an authoritative cooldown to the logical time of a predicted input.
 */
public final class PredictionCooldownTimeline {
    private PredictionCooldownTimeline() {
    }

    public static long alignNewCooldown(BendingPlayer player, String ability, long previousExpiry,
                                        long logicalTransitMillis, long nowMillis) {
        if (player == null || ability == null) return 0L;
        Cooldown current = player.getCooldowns().get(ability);
        if (current == null || current.getCooldown() <= previousExpiry)
            return current == null ? 0L : current.getCooldown();
        long aligned = alignedExpiry(current.getCooldown(), logicalTransitMillis, nowMillis);
        if (aligned <= nowMillis) {
            player.getCooldowns().remove(ability);
            return 0L;
        }
        player.getCooldowns().put(ability, new Cooldown(aligned, current.isDatabase()));
        return aligned;
    }

    public static long alignedExpiry(long serverExpiry, long logicalTransitMillis, long nowMillis) {
        long aligned = serverExpiry - Math.max(0L, logicalTransitMillis);
        return aligned <= nowMillis ? 0L : aligned;
    }

    public static boolean allowsCooldownGuardedInput(boolean createdAnyAbility, boolean handled,
                                                     boolean hadExistingMatchingAbility) {
        return createdAnyAbility || handled && hadExistingMatchingAbility;
    }
}
