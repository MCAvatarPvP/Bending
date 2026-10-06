package com.projectkorra.projectkorra.airbending;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TornadoChargeProfileTest {
    private static final double EPSILON = 1.0e-9;

    @Test void pullDurationUsesTheSameChargeFractionAsSize() {
        TornadoChargeProfile profile = standardProfile();
        assertEquals(500, profile.pullDuration(750, 500, 2000));
        assertEquals(1250, profile.pullDuration(2750, 500, 2000));
        assertEquals(2000, profile.pullDuration(4750, 500, 2000));
        assertEquals(2000, profile.pullDuration(Long.MAX_VALUE, 500, 2000));
        assertEquals(2000, profile.pullDuration(4750, 500, 0));
        assertEquals(300, profile.pullDuration(750, 500, 300));
        assertEquals(Long.MAX_VALUE, profile.pullDuration(4750, 1, Long.MAX_VALUE));
    }


    @Test
    void quickChargeBecomesReadyAtTheMinimumWithTheSmallestSizeAndCooldown() {
        final TornadoChargeProfile profile = standardProfile();

        assertFalse(profile.ready(749));
        assertTrue(profile.ready(750));
        assertFalse(profile.complete(750));
        assertSize(profile.sample(750), 8.0, 2.0, 4.0, 4000L, 0.0);
    }

    @Test
    void holdingHalfwayBetweenThresholdsInterpolatesSizePullZoneAndCooldownTogether() {
        final TornadoChargeProfile profile = standardProfile();

        assertSize(profile.sample(2750), 16.0, 5.0, 8.0, 10000L, 0.5);
        assertSize(profile.sample(1750), 12.0, 3.5, 6.0, 7000L, 0.25);
        assertFalse(profile.complete(4749));
    }

    @Test
    void fullChargeAndOverchargingShareTheMaximumSizeAndCooldown() {
        final TornadoChargeProfile profile = standardProfile();

        assertTrue(profile.complete(4750));
        assertTrue(profile.ready(Long.MAX_VALUE));
        assertTrue(profile.complete(Long.MAX_VALUE));
        assertSize(profile.sample(4750), 24.0, 8.0, 12.0, 16000L, 1.0);
        assertEquals(profile.sample(4750), profile.sample(Long.MAX_VALUE));
    }

    @Test
    void earlyAndNegativeElapsedTimesCannotShrinkBelowTheMinimumOrUnderflow() {
        final TornadoChargeProfile profile = standardProfile();

        assertFalse(profile.ready(Long.MIN_VALUE));
        assertFalse(profile.complete(Long.MIN_VALUE));
        assertEquals(profile.sample(750), profile.sample(0));
        assertEquals(profile.sample(750), profile.sample(Long.MIN_VALUE));
    }

    @Test
    void moreChargeNeverReducesAnyDimensionOrCooldown() {
        final TornadoChargeProfile profile = standardProfile();
        TornadoChargeProfile.Size previous = profile.sample(0);

        for (long held = 25; held <= 6000; held += 25) {
            final TornadoChargeProfile.Size current = profile.sample(held);
            assertTrue(current.height() >= previous.height());
            assertTrue(current.radius() >= previous.radius());
            assertTrue(current.pullRadius() >= previous.pullRadius());
            assertTrue(current.cooldown() >= previous.cooldown());
            assertTrue(current.progress() >= previous.progress());
            assertTrue(current.progress() >= 0 && current.progress() <= 1);
            previous = current;
        }
    }

    @Test
    void reversedCooldownAndSizeBoundsCannotCreateNegativeGrowth() {
        final TornadoChargeProfile profile = new TornadoChargeProfile(
                500L, 1500L, 10000L, 1000L,
                12.0, 3.0, 5.0, 1.0, 7.0, 2.0);

        assertSize(profile.sample(500), 3.0, 1.0, 2.0, 1000L, 0.0);
        assertSize(profile.sample(1000), 3.0, 1.0, 2.0, 1000L, 0.5);
        assertSize(profile.sample(1500), 3.0, 1.0, 2.0, 1000L, 1.0);
    }

    @Test
    void zeroMaximumCooldownPreservesTheAvatarStateCooldownOverride() {
        final TornadoChargeProfile profile = new TornadoChargeProfile(
                500L, 1500L, 4000L, 0L,
                8.0, 24.0, 2.0, 8.0, 4.0, 12.0);

        assertEquals(0L, profile.sample(500).cooldown());
        assertEquals(0L, profile.sample(1000).cooldown());
        assertEquals(0L, profile.sample(1500).cooldown());
    }

    @Test
    void negativeCooldownsAreClampedToZero() {
        final TornadoChargeProfile profile = new TornadoChargeProfile(
                0L, 1000L, -500L, -100L,
                8.0, 24.0, 2.0, 8.0, 4.0, 12.0);

        assertEquals(0L, profile.sample(0).cooldown());
        assertEquals(0L, profile.sample(500).cooldown());
        assertEquals(0L, profile.sample(1000).cooldown());
    }

    @Test
    void negativeChargeTimesAllowImmediateFullChargeWithoutDivisionByZero() {
        final TornadoChargeProfile profile = new TornadoChargeProfile(
                -2000L, -1000L, 4000L, 16000L,
                8.0, 24.0, 2.0, 8.0, 4.0, 12.0);

        assertTrue(profile.ready(0));
        assertTrue(profile.complete(0));
        assertSize(profile.sample(0), 24.0, 8.0, 12.0, 16000L, 1.0);
    }

    @Test
    void reversedChargeThresholdsBecomeAnInstantSizeStepAtTheMinimum() {
        final TornadoChargeProfile profile = new TornadoChargeProfile(
                1500L, 500L, 4000L, 16000L,
                8.0, 24.0, 2.0, 8.0, 4.0, 12.0);

        assertFalse(profile.ready(1499));
        assertFalse(profile.complete(1499));
        assertSize(profile.sample(1499), 8.0, 2.0, 4.0, 4000L, 0.0);
        assertTrue(profile.ready(1500));
        assertTrue(profile.complete(1500));
        assertSize(profile.sample(1500), 24.0, 8.0, 12.0, 16000L, 1.0);
    }

    @Test
    void nonFiniteAndNegativeDimensionsStillProduceFinitePositiveMonotonicSizes() {
        final TornadoChargeProfile[] profiles = {
                new TornadoChargeProfile(0L, 1000L, 0L, 1000L,
                        Double.NaN, Double.POSITIVE_INFINITY,
                        Double.NEGATIVE_INFINITY, Double.NaN, -1.0, Double.POSITIVE_INFINITY),
                new TornadoChargeProfile(0L, 1000L, 0L, 1000L,
                        0.0, -1.0, -2.0, 0.0, Double.NaN, Double.NEGATIVE_INFINITY)
        };

        for (final TornadoChargeProfile profile : profiles) {
            final TornadoChargeProfile.Size small = profile.sample(0);
            final TornadoChargeProfile.Size medium = profile.sample(500);
            final TornadoChargeProfile.Size large = profile.sample(1000);
            assertSafeDimensions(small);
            assertSafeDimensions(medium);
            assertSafeDimensions(large);
            assertTrue(small.height() <= medium.height() && medium.height() <= large.height());
            assertTrue(small.radius() <= medium.radius() && medium.radius() <= large.radius());
            assertTrue(small.pullRadius() <= medium.pullRadius() && medium.pullRadius() <= large.pullRadius());
        }
    }

    @Test
    void excessiveConfiguredDimensionsStayWithinTheServerWorkBudget() {
        final TornadoChargeProfile profile = new TornadoChargeProfile(
                0L, 1000L, 0L, 1000L,
                Double.MAX_VALUE, Double.MAX_VALUE,
                Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);

        for (final long held : new long[]{0, 500, 1000, Long.MAX_VALUE}) {
            final TornadoChargeProfile.Size size = profile.sample(held);
            assertSafeDimensions(size);
            assertTrue(size.height() <= 64.0);
            assertTrue(size.radius() <= 32.0);
            assertTrue(size.pullRadius() <= 40.0);
        }
    }

    @Test
    void largeChargeTimesDoNotOverflowWhenSamplingNegativeElapsedTime() {
        final TornadoChargeProfile profile = new TornadoChargeProfile(
                Long.MAX_VALUE - 1000L, Long.MAX_VALUE, 1000L, 2000L,
                8.0, 24.0, 2.0, 8.0, 4.0, 12.0);

        assertSize(profile.sample(Long.MIN_VALUE), 8.0, 2.0, 4.0, 1000L, 0.0);
        assertSize(profile.sample(Long.MAX_VALUE - 500L), 16.0, 5.0, 8.0, 1500L, 0.5);
        assertSize(profile.sample(Long.MAX_VALUE), 24.0, 8.0, 12.0, 2000L, 1.0);
    }

    @Test
    void maximumRepresentableCooldownCannotOverflowToANegativeCooldown() {
        final TornadoChargeProfile profile = new TornadoChargeProfile(
                0L, 1000L, 1L, Long.MAX_VALUE,
                8.0, 24.0, 2.0, 8.0, 4.0, 12.0);

        assertEquals(1L, profile.sample(0).cooldown());
        assertTrue(profile.sample(999).cooldown() >= 1L);
        assertEquals(Long.MAX_VALUE, profile.sample(1000).cooldown());
        assertEquals(Long.MAX_VALUE, profile.sample(Long.MAX_VALUE).cooldown());
    }

    private static TornadoChargeProfile standardProfile() {
        return new TornadoChargeProfile(750L, 4750L, 4000L, 16000L,
                8.0, 24.0, 2.0, 8.0, 4.0, 12.0);
    }

    private static void assertSafeDimensions(final TornadoChargeProfile.Size size) {
        assertTrue(Double.isFinite(size.height()) && size.height() > 0);
        assertTrue(Double.isFinite(size.radius()) && size.radius() > 0);
        assertTrue(Double.isFinite(size.pullRadius()) && size.pullRadius() > 0);
    }

    private static void assertSize(final TornadoChargeProfile.Size size,
                                   final double height, final double radius, final double pullRadius,
                                   final long cooldown, final double progress) {
        assertEquals(height, size.height(), EPSILON);
        assertEquals(radius, size.radius(), EPSILON);
        assertEquals(pullRadius, size.pullRadius(), EPSILON);
        assertEquals(cooldown, size.cooldown());
        assertEquals(progress, size.progress(), EPSILON);
    }
}
