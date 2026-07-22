package com.projectkorra.projectkorra.prediction.state;

import com.projectkorra.projectkorra.prediction.state.CooldownSync;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

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
}
