package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RollbackPlayerInputTest {
    private static final RollbackMovementInput MOVE = new RollbackMovementInput(1, 0, true, 20, -10);
    private static RollbackPlayerInput.Edge edge(long sequence, float yaw) {
        return new RollbackPlayerInput.Edge(new RollbackInputActions.Action(sequence, 7, RollbackInputActions.Kind.SWING, -1), yaw, 15);
    }

    @Test void retainsActionAimButPredictsOnlyHeldControls() {
        var source = new ArrayList<>(List.of(edge(1, 450), edge(2, -450)));
        var frame = new RollbackPlayerInput(MOVE, true, source);
        source.clear();
        assertEquals(2, frame.actions().size());
        assertEquals(90, frame.actions().getFirst().yaw());
        assertEquals(-90, frame.actions().getLast().yaw());
        var predicted = frame.predict();
        assertEquals(MOVE, predicted.movement()); assertTrue(predicted.sprinting());
        assertTrue(predicted.actions().isEmpty()); assertSame(predicted, predicted.predict());
        assertThrows(UnsupportedOperationException.class, () -> frame.actions().clear());
    }

    @Test void rejectsDuplicateUnorderedUnboundedAndInvalidAimInputs() {
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerInput(MOVE, false, List.of(edge(2, 0), edge(1, 0))));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerInput(MOVE, false, List.of(edge(1, 0), edge(1, 0))));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerInput(MOVE, false,
                java.util.stream.LongStream.rangeClosed(1, RollbackPlayerInput.MAXIMUM_ACTIONS + 1).mapToObj(id -> edge(id, 0)).toList()));
        assertThrows(IllegalArgumentException.class, () -> edge(1, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new RollbackPlayerInput.Edge(edge(1, 0).action(), 0, 91));
    }
}
