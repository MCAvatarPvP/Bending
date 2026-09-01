package com.projectkorra.projectkorra.prediction.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CombatNetworkTimingTest {
    @Test
    void oneWayBudgetMakesThreeTicksTheCapInsteadOfTheDefault() {
        assertEquals(0, CombatNetworkTiming.oneWayTicks(0, 3));
        assertEquals(1, CombatNetworkTiming.oneWayTicks(20, 3));
        assertEquals(1, CombatNetworkTiming.oneWayTicks(80, 3));
        assertEquals(2, CombatNetworkTiming.oneWayTicks(150, 3));
        assertEquals(3, CombatNetworkTiming.oneWayTicks(250, 3));
        assertEquals(3, CombatNetworkTiming.oneWayTicks(2_000, 3));
    }

    @Test
    void stableArrivalUsesOnlyOneWayLatency() {
        final CombatNetworkTiming.Sample sample = CombatNetworkTiming.sample(
                20, 100, 30, 110, 150, 3);

        assertEquals(108, sample.effectiveTick());
        assertEquals(2, sample.ageTicks());
        assertEquals(0, sample.jitterTicks());
    }

    @Test
    void responseWindowIsAdaptiveAndNeverZero() {
        assertEquals(1, CombatNetworkTiming.responseTicks(0, 0, 3));
        assertEquals(1, CombatNetworkTiming.responseTicks(80, 0, 3));
        assertEquals(2, CombatNetworkTiming.responseTicks(150, 0, 3));
        assertEquals(2, CombatNetworkTiming.responseTicks(80, 1, 3));
        assertEquals(3, CombatNetworkTiming.responseTicks(250, 2, 3));
    }

    @Test
    void observedLateArrivalAddsOnlyBoundedJitter() {
        final CombatNetworkTiming.Sample sample = CombatNetworkTiming.sample(
                20, 100, 30, 112, 80, 3);

        assertEquals(109, sample.effectiveTick());
        assertEquals(3, sample.ageTicks());
        assertEquals(2, sample.jitterTicks());
    }

    @Test
    void maliciouslyOldTickCannotEscapeTheHardMaximum() {
        final CombatNetworkTiming.Sample sample = CombatNetworkTiming.sample(
                20, 100, Long.MIN_VALUE, 112, 20, 3);

        assertEquals(109, sample.effectiveTick());
        assertEquals(3, sample.ageTicks());
    }
}
