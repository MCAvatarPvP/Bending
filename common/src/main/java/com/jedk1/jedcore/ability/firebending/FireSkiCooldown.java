package com.jedk1.jedcore.ability.firebending;

import com.projectkorra.projectkorra.BendingPlayer;

final class FireSkiCooldown {
    private FireSkiCooldown() {
    }

    static void apply(BendingPlayer bPlayer, long cooldown, boolean activeFireJet) {
        // Removing an active FireJet applies its own (possibly modified)
        // cooldown. Keep FireJet authoritative even when that cooldown is zero
        // or its cooldown event was cancelled, rather than applying FireSki's.
        if (!activeFireJet && !bPlayer.isOnCooldown("FireJet")) {
            bPlayer.addCooldown("FireJet", cooldown);
        }
    }
}
