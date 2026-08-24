package com.jedk1.jedcore.ability.firebending;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FireSkiCooldownTest {

    @Test
    void activeFireJetCooldownIsNotReplacedByFireSki() {
        final TrackingBendingPlayer bPlayer = new TrackingBendingPlayer(false);

        FireSkiCooldown.apply(bPlayer, 6_000, true);

        assertEquals(0, bPlayer.cooldownApplications);
    }

    @Test
    void existingFireJetCooldownIsNotReplacedByStandaloneFireSki() {
        final TrackingBendingPlayer bPlayer = new TrackingBendingPlayer(true);

        FireSkiCooldown.apply(bPlayer, 6_000, false);

        assertEquals(0, bPlayer.cooldownApplications);
    }

    @Test
    void fireSkiStillAppliesItsSharedCooldownWithoutAnActiveFireJetCooldown() {
        final TrackingBendingPlayer bPlayer = new TrackingBendingPlayer(false);

        FireSkiCooldown.apply(bPlayer, 6_000, false);

        assertEquals(1, bPlayer.cooldownApplications);
        assertEquals("FireJet", bPlayer.appliedAbility);
        assertEquals(6_000, bPlayer.appliedCooldown);
    }

    private static final class TrackingBendingPlayer extends BendingPlayer {
        private final boolean fireJetOnCooldown;
        private int cooldownApplications;
        private String appliedAbility;
        private long appliedCooldown;

        private TrackingBendingPlayer(boolean fireJetOnCooldown) {
            super(new Player());
            this.fireJetOnCooldown = fireJetOnCooldown;
        }

        @Override
        public boolean isOnCooldown(String ability) {
            return fireJetOnCooldown && "FireJet".equals(ability);
        }

        @Override
        public void addCooldown(String ability, long cooldown, boolean database) {
            cooldownApplications++;
            appliedAbility = ability;
            appliedCooldown = cooldown;
        }
    }
}
