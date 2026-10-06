package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.projectkorra.projectkorra.prediction.rollback.RollbackRound.DamageResult.*;
import static org.junit.jupiter.api.Assertions.*;

public class RollbackRoundTest {
    private static final UUID SESSION = new UUID(0, 1), A = new UUID(0, 2), B = new UUID(0, 3), C = new UUID(0, 4), D = new UUID(0, 5);
    @Test void graphCheckpointsRestoreProvisionalResultsButCannotEraseDeliveredResults() {
        var round = duel();
        var graph = new RollbackStateGraph(value -> false, field -> true, 1000);
        var before = graph.capture(List.of(round), List.of());
        round.beginTick(1);
        round.damage(A, B, 4, 6, false, false);
        before.restore();
        assertTrue(round.active(A));
        assertTrue(round.provisionalDefeats().isEmpty());
        round.beginTick(1);
        round.damage(A, B, 4, 6, false, false);
        var delivered = new ArrayList<RollbackRound.Defeat>();
        round.finalizeThrough(1, delivered::add);
        assertThrows(IllegalStateException.class, before::restore);
        assertEquals(1, delivered.size());
    }

    private RollbackRound duel() { return new RollbackRound(SESSION, Map.of(A, A, B, B)); }

    @Test public void lateDodgeRetractsTheProvisionalDefeatBeforeAnyMatchResultIsDelivered() {
        var round = duel();
        var beforeHit = round.snapshot();
        var delivered = new ArrayList<RollbackRound.Defeat>();
        round.beginTick(1);
        assertEquals(DEFEAT, round.damage(A, B, 4, 6, false, false));
        assertTrue(round.ended()); assertFalse(round.active(B));
        assertEquals(1, round.provisionalDefeats().size());
        round.finalizeThrough(0, delivered::add);
        assertTrue(delivered.isEmpty());
        round.restore(beforeHit);
        round.beginTick(1);
        // Replayed movement/collision no longer produces the hit.
        round.beginTick(2);
        assertTrue(round.active(A)); assertTrue(round.active(B));
        assertFalse(round.ended()); assertTrue(round.provisionalDefeats().isEmpty());
        round.finalizeThrough(2, delivered::add);
        assertTrue(delivered.isEmpty());
    }

    @Test public void teamDefeatsRetainAttributionAndOnlyFinalizeOnceInTickOrder() {
        var round = new RollbackRound(SESSION, Map.of(A, A, B, A, C, C, D, C));
        var delivered = new ArrayList<RollbackRound.Defeat>();
        round.beginTick(1);
        assertEquals(ALLOW, round.damage(A, C, 20, 3, false, false));
        assertEquals(DEFEAT, round.damage(A, null, 3, 4, false, false));
        assertFalse(round.ended()); assertFalse(round.active(A)); assertTrue(round.active(B));
        assertEquals(CANCEL, round.damage(C, A, 20, 30, false, false));
        var first = round.snapshot();
        round.beginTick(2);
        assertEquals(DEFEAT, round.damage(B, D, 20, 20, false, false));
        assertTrue(round.ended());
        round.finalizeThrough(1, delivered::add);
        assertEquals(List.of(new RollbackRound.Defeat(SESSION, 1, A, C)), delivered);
        round.finalizeThrough(1, delivered::add);
        assertEquals(1, delivered.size());
        round.restore(first);
        round.beginTick(2);
        assertEquals(DEFEAT, round.damage(B, D, 20, 20, false, false));
        round.finalizeThrough(2, delivered::add);
        round.finalizeThrough(2, delivered::add);
        assertEquals(List.of(new RollbackRound.Defeat(SESSION, 1, A, C), new RollbackRound.Defeat(SESSION, 2, B, D)), delivered);
        assertTrue(round.provisionalDefeats().isEmpty());
    }

    @Test public void finalizedDefeatCannotBeRewrittenEvenByALaterCheckpointFromAnotherBranch() {
        var round = duel();
        var initial = round.snapshot();
        round.beginTick(1); round.beginTick(2);
        var discardedBranch = round.snapshot();
        round.restore(initial);
        round.beginTick(1);
        assertEquals(DEFEAT, round.damage(A, B, 5, 8, false, false));
        round.beginTick(2);
        round.finalizeThrough(1, defeat -> { });
        assertThrows(IllegalStateException.class, () -> round.restore(initial));
        assertThrows(IllegalStateException.class, () -> round.restore(discardedBranch));
        assertThrows(IllegalArgumentException.class, () -> round.restore(duel().snapshot()));
        assertThrows(IllegalArgumentException.class, () -> round.finalizeThrough(3, defeat -> { }));
        assertThrows(IllegalArgumentException.class, () -> round.finalizeThrough(0, defeat -> { }));
        assertTrue(round.ended());
    }

    @Test public void cancelledHitsAndTotemExceptionDoNotProduceDefeats() {
        var round = duel();
        round.beginTick(1);
        assertEquals(CANCEL, round.damage(A, B, 4, 100, false, true));
        assertEquals(ALLOW, round.damage(A, B, 4, 100, true, false));
        assertEquals(ALLOW, round.damage(A, B, 4, 0, false, false));
        assertEquals(ALLOW, round.damage(A, B, 4, 3, false, false));
        assertTrue(round.provisionalDefeats().isEmpty()); assertTrue(round.active(A));
        assertEquals(DEFEAT, round.damage(A, B, 4, 4, false, false));
        assertEquals(CANCEL, round.damage(A, B, 4, 4, false, false));
        assertEquals(1, round.provisionalDefeats().size());
    }

    @Test public void deliveryFailureStopsTheSessionWithoutRepeatingPartialMatchSideEffects() {
        var round = duel();
        round.beginTick(1);
        round.damage(A, B, 5, 8, false, false);
        var saved = round.snapshot();
        var calls = new ArrayList<RollbackRound.Defeat>();
        assertThrows(IllegalStateException.class, () -> round.finalizeThrough(1, defeat -> {
            assertThrows(IllegalStateException.class, round::snapshot);
            calls.add(defeat); throw new IllegalStateException("match callback failed");
        }));
        assertThrows(IllegalStateException.class, () -> round.finalizeThrough(1, calls::add));
        assertThrows(IllegalStateException.class, () -> round.restore(saved));
        assertEquals(1, calls.size());
    }

    @Test public void foreignParticipantsAndCrossThreadAccessFailBeforeStateChanges() {
        var round = duel();
        assertThrows(IllegalStateException.class, () -> round.damage(A, B, 5, 8, false, false));
        assertThrows(IllegalArgumentException.class, () -> round.beginTick(2));
        round.beginTick(1);
        assertThrows(IllegalArgumentException.class, () -> round.damage(C, A, 5, 8, false, false));
        assertThrows(IllegalArgumentException.class, () -> round.damage(A, C, 5, 8, false, false));
        assertThrows(IllegalArgumentException.class, () -> round.damage(A, B, Double.NaN, 8, false, false));
        assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(round::snapshot).join());
        assertTrue(round.active(A)); assertTrue(round.provisionalDefeats().isEmpty());
    }
}
