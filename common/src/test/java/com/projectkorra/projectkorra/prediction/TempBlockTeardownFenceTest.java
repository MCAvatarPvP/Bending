package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempBlockTeardownFenceTest {
    @Test
    void lateWaterAfterAnAirTeardownCannotBecomeItsOwnAnswer() {
        final TempBlockTeardownFence<String, String> fence = new TempBlockTeardownFence<>();
        fence.arm("column", Set.of("water-0", "water-7"), "air", 100L);

        assertEquals("air", fence.maskIncoming("column", "water-0").orElseThrow());
        assertEquals("air", fence.audit("column", "water-7").orElseThrow());
        assertTrue(fence.keys().contains("column"),
                "matching stale water must not consume the durable teardown fence");
    }

    @Test
    void differentAuthoritativeStateReleasesOnlyThatCoordinate() {
        final TempBlockTeardownFence<String, String> fence = new TempBlockTeardownFence<>();
        fence.arm("column", Set.of("water"), "air", 100L);

        assertTrue(fence.maskIncoming("column", "gold").isEmpty());
        assertFalse(fence.keys().contains("column"));
    }

    @Test
    void auditOfTheCorrectUnderlayDoesNotPrematurelyReleaseTheFence() {
        final TempBlockTeardownFence<String, String> fence = new TempBlockTeardownFence<>();
        fence.arm("column", Set.of("water"), "air", 100L);

        assertTrue(fence.audit("column", "air").isEmpty());
        assertTrue(fence.keys().contains("column"));
    }

    @Test
    void repeatedLatePacketsRemainMaskedUntilDifferentAuthorityArrives() {
        final TempBlockTeardownFence<String, String> fence = new TempBlockTeardownFence<>();
        fence.arm("column", Set.of("water"), "air", 100L);

        assertEquals("air", fence.maskIncoming("column", "water").orElseThrow());
        assertEquals("air", fence.maskIncoming("column", "water").orElseThrow());
        assertEquals(1, fence.size());
    }

    @Test
    void retainedPacketCannotDisarmFenceBeforeALateFluidWrite() {
        final TempBlockTeardownFence<String, String> fence = new TempBlockTeardownFence<>();
        fence.arm("column", Set.of("water"), "air", 100L);

        assertTrue(fence.maskIncoming("column", "air").isEmpty());
        assertTrue(fence.keys().contains("column"));
        assertEquals("air", fence.audit("column", "water").orElseThrow());
    }

    @Test
    void rearmingAReusedCoordinateKeepsBothLateGenerations() {
        final TempBlockTeardownFence<String, String> fence = new TempBlockTeardownFence<>();
        fence.arm("column", Set.of("water-0"), "air", 100L);
        fence.arm("column", Set.of("water-7"), "stone", 110L);

        assertEquals("stone", fence.maskIncoming("column", "water-0").orElseThrow());
        assertEquals("stone", fence.maskIncoming("column", "water-7").orElseThrow());
        fence.expireBefore(105L);
        assertEquals(1, fence.size(), "rearming must refresh the coordinate lifetime");
    }

    @Test
    void expiryAndExplicitReleaseAreCoordinateScoped() {
        final TempBlockTeardownFence<String, String> fence = new TempBlockTeardownFence<>();
        fence.arm("old", Set.of("water"), "air", 10L);
        fence.arm("new", Set.of("ice"), "air", 20L);

        fence.expireBefore(15L);
        assertFalse(fence.keys().contains("old"));
        assertTrue(fence.keys().contains("new"));
        fence.release("new");
        assertEquals(0, fence.size());
    }
}
