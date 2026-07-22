package com.projectkorra.projectkorra.prediction.hit;

import com.projectkorra.projectkorra.prediction.hit.HitRewind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HitRewindTest {
    @Test
    void rewindIncludesBothPlayersOneWayLatency() {
        assertEquals(1, HitRewind.combinedRewindTicks(0, 0, 12));
        assertEquals(4, HitRewind.combinedRewindTicks(100, 150, 12));
        assertEquals(4, HitRewind.combinedRewindTicks(150, 100, 12));
    }

    @Test
    void rewindIsBoundedByTheServerHardMaximum() {
        assertEquals(12, HitRewind.combinedRewindTicks(2_000, 2_000, 12));
        assertEquals(0, HitRewind.combinedRewindTicks(100, 100, 0));
    }

    @Test
    void mappedClientTickCannotEscapeTheCombinedWindow() {
        assertEquals(108, HitRewind.mapClientTick(
                20, 100, 30, 112, 100, 150, 12));
        assertEquals(108, HitRewind.mapClientTick(
                20, 100, Long.MIN_VALUE, 112, 100, 150, 12));
        assertEquals(112, HitRewind.mapClientTick(
                20, 100, Long.MAX_VALUE, 112, 100, 150, 12));
    }
}
