package com.projectkorra.projectkorra.airbending.passive;

import com.projectkorra.projectkorra.platform.mc.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AirStaminaBarTest {

    @Test
    void staminaMapsToStableBubbleStatesAcrossThePlayersActualMaximumAir() {
        // Zero / below zero = no bubbles.
        assertEquals(0, AirStaminaBar.airTicks(-1.0F, 400));
        assertEquals(0, AirStaminaBar.airTicks(0.0F, 400));

        /*
         * 25% stamina:
         *
         * ceil(0.25 * 10) = 3 bubbles
         *
         * maximumAir = 400:
         * scaled vanilla offset = 3 * 400 / 300 = 4
         * ticks per bubble = 400 / 10 = 40
         *
         * 4 + (3 - 1) * 40 = 84
         */
        assertEquals(84, AirStaminaBar.airTicks(0.25F, 400));

        /*
         * Values above 1 are clamped to 1.
         *
         * 10 bubbles:
         * 4 + 9 * 40 = 364
         *
         * We intentionally do NOT return maximumAir because full vanilla
         * air is a special HUD state.
         */
        assertEquals(364, AirStaminaBar.airTicks(1.0F, 400));
        assertEquals(364, AirStaminaBar.airTicks(2.0F, 400));
    }

    @Test
    void normalMaximumAirUsesVanillaStableBubbleThresholds() {
        assertEquals(0, AirStaminaBar.airTicks(0.0F, 300));

        assertEquals(3, AirStaminaBar.airTicks(0.10F, 300));
        assertEquals(33, AirStaminaBar.airTicks(0.20F, 300));
        assertEquals(63, AirStaminaBar.airTicks(0.30F, 300));
        assertEquals(93, AirStaminaBar.airTicks(0.40F, 300));
        assertEquals(123, AirStaminaBar.airTicks(0.50F, 300));
        assertEquals(153, AirStaminaBar.airTicks(0.60F, 300));
        assertEquals(183, AirStaminaBar.airTicks(0.70F, 300));
        assertEquals(213, AirStaminaBar.airTicks(0.80F, 300));
        assertEquals(243, AirStaminaBar.airTicks(0.90F, 300));
        assertEquals(273, AirStaminaBar.airTicks(1.00F, 300));
    }

    @Test
    void staminaWithinSameBubbleBucketProducesSameAirTicks() {
        /*
         * 0.80001 through 0.9 belong to the ninth bubble bucket.
         *
         * This is the behavior that prevents values such as 0.8454
         * from producing arbitrary air values like 254.
         */
        assertEquals(243, AirStaminaBar.airTicks(0.80001F, 300));
        assertEquals(243, AirStaminaBar.airTicks(0.8454F, 300));
        assertEquals(243, AirStaminaBar.airTicks(0.8999F, 300));
        assertEquals(243, AirStaminaBar.airTicks(0.9000F, 300));
    }

    @Test
    void crossingBubbleBoundaryChangesToNextStableValue() {
        assertEquals(243, AirStaminaBar.airTicks(0.9000F, 300));
        assertEquals(273, AirStaminaBar.airTicks(0.90001F, 300));
    }

    @Test
    void nonFiniteStaminaProducesZeroAir() {
        assertEquals(0, AirStaminaBar.airTicks(Float.NaN, 300));
        assertEquals(0, AirStaminaBar.airTicks(Float.POSITIVE_INFINITY, 300));
        assertEquals(0, AirStaminaBar.airTicks(Float.NEGATIVE_INFINITY, 300));
    }

    @Test
    void nonPositiveMaximumAirProducesZeroAir() {
        assertEquals(0, AirStaminaBar.airTicks(0.5F, 0));
        assertEquals(0, AirStaminaBar.airTicks(0.5F, -100));
    }

    @Test
    void releasingOnceRestoresVanillaThenLeavesRealUnderwaterAirAlone() {
        final FakePlayer player = new FakePlayer(300);
        final AirStaminaBar bar = new AirStaminaBar();

        bar.display(player, 0.25F);

        // 25% is the third bubble bucket -> 63 air ticks.
        assertEquals(63, player.getRemainingAir());

        bar.release(player);

        // Restore the original vanilla value.
        assertEquals(300, player.getRemainingAir());
        assertEquals(2, player.writes);

        player.remainingAir = 173; // Vanilla breathing after entering water.

        bar.release(player);

        assertEquals(173, player.getRemainingAir());
        assertEquals(
                2,
                player.writes,
                "the stamina owner must not refill air after giving the meter back to vanilla"
        );
    }

    @Test
    void aBriefSurfaceDoesNotRefillThePlayersRealBreathBeyondVanillaRate() {
        final FakePlayer player = new FakePlayer(300);
        final AirStaminaBar bar = new AirStaminaBar();

        player.remainingAir = 120;

        /*
         * First call captures the actual vanilla air (120) and displays
         * stamina using the third stable bubble value (63).
         */
        bar.display(player, 0.25F);
        assertEquals(63, player.getRemainingAir());

        /*
         * Second stamina tick represents one vanilla land-regeneration
         * tick internally:
         *
         * 120 -> 124
         */
        bar.display(player, 0.25F);

        /*
         * Since the displayed value is already 63, the second display
         * doesn't need another setRemainingAir write.
         */
        assertEquals(63, player.getRemainingAir());

        bar.release(player);

        assertEquals(
                124,
                player.getRemainingAir(),
                "hidden vanilla air should receive only its normal four-tick land regeneration"
        );

        // display(63), release(124)
        assertEquals(2, player.writes);
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
