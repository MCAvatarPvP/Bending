package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Box;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.Motion;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackMovementSolver;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackMovementSolver.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class RollbackMovementSolverTest {
    private static final Box BOX = new Box(0, 0, 0, 1, 1, 1);
    private static final Input INPUT = new Input(BOX, new Motion(1, -1, 0), 0.6F, true);
    private static final Source<String> SOURCE = new Source<>() {
        @Override public List<Box> entities(Box bounds) { return List.of(); }
        @Override public Colliders<String> terrainAndBorder(Box bounds) { return new Colliders<>(List.of("fixture"), List.of()); }
    };

    @Test void boundedStepSearchStopsBeforeAdditionalNativeCollisionWork() {
        var nativeFixture = new NativeFixture();
        var solver = new RollbackMovementSolver<>(nativeFixture, new Limits(1, 8, 2));
        assertThrows(IllegalStateException.class, () -> solver.resolve(INPUT, SOURCE));
        assertEquals(1, nativeFixture.clips, "Only the initial clip fits before collection exhausts the work budget");
        var candidates = new RollbackMovementSolver<>(new NativeFixture(), new Limits(1, 1, 100));
        assertThrows(IllegalStateException.class, () -> candidates.resolve(INPUT, SOURCE));
        var oversized = new Source<String>() {
            @Override public List<Box> entities(Box bounds) { return List.of(BOX, BOX); }
            @Override public Colliders<String> terrainAndBorder(Box bounds) { throw new AssertionError("Entity budget must stop collection first"); }
        };
        assertThrows(IllegalStateException.class, () -> solver.resolve(INPUT, oversized));
    }

    @Test void malformedNativeStepOutputAndCrossThreadUseFailWithoutAResult() {
        var nativeFixture = new NativeFixture();
        nativeFixture.heights = new float[]{0.5F, 0.25F};
        var solver = new RollbackMovementSolver<>(nativeFixture, Limits.standard());
        assertThrows(IllegalStateException.class, () -> solver.resolve(INPUT, SOURCE));
        int calls = nativeFixture.clips;
        var failure = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> solver.resolve(INPUT, SOURCE)).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(calls, nativeFixture.clips);
        assertThrows(IllegalArgumentException.class, () -> RollbackMovementSolver.requireBlockQuery(new Box(-1e30, 0, 0, 0, 1, 1)));
    }

    /** Only tests resource/ABI guards; native movement expectations live in the platform suites. */
    private static final class NativeFixture implements Native<String> {
        int clips;
        float[] heights = {0.25F, 0.5F};
        @Override public Motion clip(Box bounds, Motion requested, Colliders<String> colliders) { clips++; return new Motion(0, 0, 0); }
        @Override public float[] stepHeights(Box bounds, Colliders<String> colliders, float maximum, float previousY) { return heights; }
    }
}
