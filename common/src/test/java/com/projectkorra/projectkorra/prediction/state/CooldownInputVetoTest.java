package com.projectkorra.projectkorra.prediction.state;

import com.projectkorra.projectkorra.prediction.state.CooldownSync;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownInputVetoTest {
    @Test
    void vetoIsScopedToTheExactPlayerAndCooldownNames() {
        final UUID player = UUID.randomUUID();
        final UUID other = UUID.randomUUID();

        assertFalse(CooldownSync.isInputVetoed(player, "AirSwipe"));
        CooldownSync.runInputVeto(player, List.of("AirSwipe"), () -> {
            assertTrue(CooldownSync.isInputVetoed(player, "airswipe"));
            assertFalse(CooldownSync.isInputVetoed(player, "AirSweep"),
                    "the completed combo must remain eligible while only its bound step is vetoed");
            assertFalse(CooldownSync.isInputVetoed(other, "AirSwipe"));
            return null;
        });
        assertFalse(CooldownSync.isInputVetoed(player, "AirSwipe"));
    }

    @Test
    void phaseChangeBranchCanVetoItsAliasWithoutBlockingTheOtherBranch() {
        final UUID player = UUID.randomUUID();
        CooldownSync.runInputVeto(player, List.of("PhaseChange", "PhaseChangeMelt"), () -> {
            assertTrue(CooldownSync.isInputVetoed(player, "PhaseChangeMelt"));
            assertFalse(CooldownSync.isInputVetoed(player, "PhaseChangeFreeze"));
            return null;
        });
    }

    @Test
    void leniencyIsScopedToTheExactPlayerAndCooldownNames() {
        final UUID player = UUID.randomUUID();
        final UUID other = UUID.randomUUID();
        final long now = 10_000L;

        assertEquals(now, CooldownSync.effectiveInputTime(player, "AirSwipe", now));
        CooldownSync.runInputLeniency(player, List.of("AirSwipe"),
                CooldownSync.INPUT_LENIENCY_MILLIS, () -> {
                    final long effective = CooldownSync.effectiveInputTime(player, "airswipe", now);
                    assertEquals(now + 100L, effective);
                    assertFalse(effective < now + 100L, "exactly 100 ms remaining must be accepted");
                    assertTrue(effective < now + 101L, "more than 100 ms remaining must stay on cooldown");
                    assertEquals(now, CooldownSync.effectiveInputTime(player, "AirSweep", now));
                    assertEquals(now, CooldownSync.effectiveInputTime(other, "AirSwipe", now));
                    return null;
                });
        assertEquals(now, CooldownSync.effectiveInputTime(player, "AirSwipe", now));
    }
}
