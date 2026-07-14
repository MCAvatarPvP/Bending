package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactionWindowTest {
    private static final BoundingBox PLAYER_BOX = new BoundingBox(
            new Vector(-0.3, 0, -0.3), new Vector(0.3, 1.8, 0.3));

    @Test
    void compensationUsesCappedRoundTripTimeAndJitter() {
        assertEquals(1, ReactionWindow.compensationTicks(0, 200, 25));
        assertEquals(4, ReactionWindow.compensationTicks(150, 200, 25));
        assertEquals(5, ReactionWindow.compensationTicks(1_000, 200, 25));
        assertEquals(1, ReactionWindow.compensationTicks(-100, -1, -1));
    }

    @Test
    void unseenInstantHitGivesZeroPingDefenderAVisibleReactionBudget() {
        assertEquals(5, ReactionWindow.visibilityDelayTicks(0,
                200, 0, 200, 25));
    }

    @Test
    void timeAlreadyVisibleIsDeductedFromReactionDelay() {
        assertEquals(3, ReactionWindow.visibilityDelayTicks(2,
                200, 0, 200, 25));
        assertEquals(0, ReactionWindow.visibilityDelayTicks(5,
                200, 0, 200, 25));
    }

    @Test
    void defenderNetworkBudgetIsCappedEvenForExtremePing() {
        assertEquals(9, ReactionWindow.visibilityDelayTicks(0,
                200, 1_000, 200, 25));
        assertEquals(9, ReactionWindow.visibilityDelayTicks(0,
                200, 200, 200, 25));
    }

    @Test
    void stationaryHitCommitsEffectsInOriginalOrderAtDeadline() {
        List<String> committed = new ArrayList<>();
        PendingHitResolution pending = new PendingHitResolution(14, new Vector(0, 0.9, 0));
        assertTrue(pending.add(() -> committed.add("damage")));
        assertTrue(pending.add(() -> committed.add("velocity")));

        assertEquals(PendingHitResolution.Result.WAITING, pending.resolve(13, PLAYER_BOX, 0.2));
        assertTrue(committed.isEmpty());
        assertEquals(PendingHitResolution.Result.COMMITTED, pending.resolve(14, PLAYER_BOX, 0.2));
        assertEquals(List.of("damage", "velocity"), committed);
        assertEquals(PendingHitResolution.Result.ALREADY_RESOLVED,
                pending.resolve(15, PLAYER_BOX, 0.2));
        assertFalse(pending.add(() -> committed.add("late")));
    }

    @Test
    void movingHitboxAwayFromClaimedContactDodgesAllEffects() {
        List<String> committed = new ArrayList<>();
        PendingHitResolution pending = new PendingHitResolution(20, new Vector(0, 0.9, 0));
        pending.add(() -> committed.add("damage"));
        pending.add(() -> committed.add("velocity"));
        BoundingBox moved = PLAYER_BOX.shift(1.0, 0, 0);

        assertEquals(PendingHitResolution.Result.DODGED, pending.resolve(20, moved, 0.2));
        assertTrue(committed.isEmpty());
    }

    @Test
    void toleranceAbsorbsTinyBoundaryDifferencesButNotARealDodge() {
        assertTrue(ReactionWindow.containsContact(PLAYER_BOX, new Vector(0.45, 0.9, 0), 0.2));
        assertFalse(ReactionWindow.containsContact(PLAYER_BOX, new Vector(0.55, 0.9, 0), 0.2));
        assertFalse(ReactionWindow.containsContact(PLAYER_BOX,
                new Vector(Double.NaN, 0.9, 0), 0.2));
    }
}
