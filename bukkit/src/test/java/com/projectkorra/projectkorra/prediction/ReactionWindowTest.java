package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
        assertTrue(pending.add(() -> committed.add("stamina")));
        assertTrue(pending.add(() -> committed.add("sound")));

        assertEquals(PendingHitResolution.Result.WAITING, pending.resolve(13, PLAYER_BOX, 0.2));
        assertTrue(committed.isEmpty());
        assertEquals(PendingHitResolution.Result.COMMITTED, pending.resolve(14, PLAYER_BOX, 0.2));
        assertEquals(List.of("damage", "velocity", "stamina", "sound"), committed);
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
        pending.add(() -> committed.add("stamina"));
        pending.add(() -> committed.add("sound"));
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

    @Test
    void abilityRadiusIsAppliedInAdditionToNetworkTolerance() {
        HitGeometry radiusAware = new HitGeometry(List.of(new Vector(0.9, 0.9, 0)), 0.4);
        HitGeometry tooFar = new HitGeometry(List.of(new Vector(0.91, 0.9, 0)), 0.4);

        assertTrue(ReactionWindow.intersects(PLAYER_BOX, radiusAware, 0.2));
        assertFalse(ReactionWindow.intersects(PLAYER_BOX, tooFar, 0.2));
    }

    @Test
    void anyCollisionLocationCanConfirmAMultiStreamAbility() {
        HitGeometry streams = new HitGeometry(List.of(
                new Vector(10, 0.9, 10),
                new Vector(0.7, 0.9, 0),
                new Vector(-10, 0.9, -10)), 0.2);

        assertTrue(ReactionWindow.intersects(PLAYER_BOX, streams, 0.2));
    }

    @Test
    void pendingResolutionUsesTheLatestLiveStreamGeometry() {
        AtomicReference<HitGeometry> live = new AtomicReference<>(
                new HitGeometry(List.of(new Vector(2, 0.9, 0)), 0.25));
        PendingHitResolution pending = new PendingHitResolution(10,
                HitGeometry.point(new Vector(0, 0.9, 0)), live::get);
        List<String> committed = new ArrayList<>();
        pending.add(HitResolutionSync.Effect.DAMAGE, () -> committed.add("damage"));

        assertEquals(PendingHitResolution.Result.COMMITTED,
                pending.resolve(10, PLAYER_BOX.shift(2, 0, 0), 0.2));
        assertEquals(List.of("damage"), committed);
    }

    @Test
    void projectileMovingPastAStationaryPointBlankTargetDoesNotEraseItsCollision() {
        AtomicReference<HitGeometry> live = new AtomicReference<>(
                new HitGeometry(List.of(new Vector(3, 0.9, 0)), 0.25));
        PendingHitResolution pending = new PendingHitResolution(10,
                new HitGeometry(List.of(new Vector(0.5, 0.9, 0)), 0.25), live::get);
        List<String> committed = new ArrayList<>();
        pending.add(HitResolutionSync.Effect.DAMAGE, () -> committed.add("damage"));

        assertEquals(PendingHitResolution.Result.COMMITTED,
                pending.resolve(10, PLAYER_BOX, 0.2));
        assertEquals(List.of("damage"), committed);
    }

    @Test
    void exactCollisionContactSurvivesBrokenAbilityGeometryForEveryMove() {
        HitGeometry brokenAbilityGeometry = new HitGeometry(
                List.of(new Vector(100, 100, 100)), 0.1);
        HitGeometry collisionContact = HitGeometry.point(PLAYER_BOX.getCenter());
        PendingHitResolution stationary = new PendingHitResolution(10,
                brokenAbilityGeometry, collisionContact, () -> brokenAbilityGeometry);
        List<String> committed = new ArrayList<>();
        stationary.add(() -> committed.add("damage"));

        assertEquals(PendingHitResolution.Result.COMMITTED,
                stationary.resolve(10, PLAYER_BOX, 0.2));
        assertEquals(List.of("damage"), committed);

        PendingHitResolution dodged = new PendingHitResolution(10,
                brokenAbilityGeometry, collisionContact, () -> brokenAbilityGeometry);
        dodged.add(() -> committed.add("ghost"));
        assertEquals(PendingHitResolution.Result.DODGED,
                dodged.resolve(10, PLAYER_BOX.shift(2, 0, 0), 0.2));
        assertFalse(committed.contains("ghost"));
    }

    @Test
    void repeatedStreamEffectsReplaceRatherThanStackDuringOneDecision() {
        List<String> committed = new ArrayList<>();
        PendingHitResolution pending = new PendingHitResolution(10, new Vector(0, 0.9, 0));
        pending.add(HitResolutionSync.Effect.DAMAGE, () -> committed.add("old"));
        pending.add(HitResolutionSync.Effect.DAMAGE, () -> committed.add("latest"));

        assertEquals(PendingHitResolution.Result.COMMITTED,
                pending.resolve(10, PLAYER_BOX, 0.2));
        assertEquals(List.of("latest"), committed);
    }

    @Test
    void distinctStatusEffectsFromOneHitAreAllPreserved() {
        List<String> committed = new ArrayList<>();
        PendingHitResolution pending = new PendingHitResolution(10, new Vector(0, 0.9, 0));
        pending.add(HitResolutionSync.Effect.STATUS, () -> committed.add("fire"));
        pending.add(HitResolutionSync.Effect.STATUS, () -> committed.add("slow"));

        assertEquals(PendingHitResolution.Result.COMMITTED,
                pending.resolve(10, PLAYER_BOX, 0.2));
        assertEquals(List.of("fire", "slow"), committed);
    }

    @Test
    void nativeDamageVelocityAndStaminaFromOneServerHitShareAReactionDecision() {
        UUID target = UUID.randomUUID();
        List<String> committed = new ArrayList<>();
        NativeHitResolution pending = new NativeHitResolution(null, target,
                30, 35, PLAYER_BOX.getCenter());

        assertTrue(pending.matches(null, target));
        assertTrue(pending.matches(null, target),
                "later streams from the same ability share the unresolved decision");
        assertTrue(pending.add(() -> committed.add("damage")));
        assertTrue(pending.add(() -> committed.add("velocity")));
        assertTrue(pending.add(() -> committed.add("stamina")));
        assertTrue(pending.add(() -> committed.add("sound")));
        assertEquals(PendingHitResolution.Result.COMMITTED,
                pending.resolve(35, PLAYER_BOX, 0.2));
        assertEquals(List.of("damage", "velocity", "stamina", "sound"), committed);
    }
}
