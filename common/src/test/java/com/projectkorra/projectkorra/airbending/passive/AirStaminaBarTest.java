package com.projectkorra.projectkorra.airbending.passive;

import com.projectkorra.projectkorra.platform.mc.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AirStaminaBarTest {
    @Test
    void staminaScalesAcrossThePlayersActualMaximumAir() {
        assertEquals(0, AirStaminaBar.airTicks(-1.0F, 400));
        assertEquals(100, AirStaminaBar.airTicks(0.25F, 400));
        assertEquals(400, AirStaminaBar.airTicks(2.0F, 400));
    }

    @Test
    void releasingOnceRestoresVanillaThenLeavesRealUnderwaterAirAlone() {
        final FakePlayer player = new FakePlayer(300);
        final AirStaminaBar bar = new AirStaminaBar();

        bar.display(player, 0.25F);
        assertEquals(75, player.getRemainingAir());

        bar.release(player);
        assertEquals(300, player.getRemainingAir());
        assertEquals(2, player.writes);

        player.remainingAir = 173; // Vanilla breathing after entering water.
        bar.release(player);

        assertEquals(173, player.getRemainingAir());
        assertEquals(2, player.writes,
                "the stamina owner must not refill air after giving the meter back to vanilla");
    }

    @Test
    void aBriefSurfaceDoesNotRefillThePlayersRealBreath() {
        final FakePlayer player = new FakePlayer(300);
        final AirStaminaBar bar = new AirStaminaBar();
        player.remainingAir = 120;

        bar.display(player, 0.25F);
        assertEquals(75, player.getRemainingAir());
        bar.display(player, 0.25F);
        bar.release(player);

        assertEquals(124, player.getRemainingAir(),
                "hidden vanilla air should receive only its normal four-tick land regeneration");
    }

    private static final class FakePlayer extends Player {
        private final int maximumAir;
        private int remainingAir;
        private int writes;

        private FakePlayer(final int maximumAir) {
            this.maximumAir = maximumAir;
            this.remainingAir = maximumAir;
        }

        @Override
        public int getRemainingAir() {
            return this.remainingAir;
        }

        @Override
        public int getMaximumAir() {
            return this.maximumAir;
        }

        @Override
        public void setRemainingAir(final int ticks) {
            this.remainingAir = ticks;
            this.writes++;
        }
    }
}
