package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackStepOutputTest {
    @Test void bindsExactlyOneClockAndClosesAfterFailureWithoutRetainingItsBuffer() {
        var output = new RollbackStepOutput<String>();
        var first = new RollbackStep<String>(1, 4);
        assertThrows(IllegalStateException.class, () -> output.open(first));
        try (var clock = RollbackClock.at(0, 1, 50_000_000)) {
            try (var binding = output.open(first)) {
                output.accept("first");
                assertThrows(IllegalStateException.class, () -> output.open(first));
                assertThrows(IllegalStateException.class, output::captureRollbackState);
                try (var nested = RollbackClock.at(0, 1, 50_000_000)) {
                    assertThrows(IllegalStateException.class, () -> output.accept("wrong clock"));
                }
                output.accept("second");
            }
            assertThrows(IllegalStateException.class, () -> output.accept("closed"));
            var discarded = new RollbackStep<String>(2, 1);
            assertThrows(IllegalStateException.class, () -> {
                try (var binding = output.open(discarded)) { output.accept("discard"); output.accept("over budget"); }
            });
            assertNull(output.captureRollbackState());
            var replacement = new RollbackStep<String>(2, 1);
            try (var binding = output.open(replacement)) { output.accept("replacement"); }
            assertEquals(List.of("replacement"), replacement.finish());
        }
        assertEquals(List.of("first", "second"), first.finish());
        assertThrows(IllegalStateException.class, () -> output.accept("outside"));
    }
    @Test void replayReplacesProvisionalOutputThroughTheStableConsumer() {
        var output = new RollbackStepOutput<String>();
        UUID player = new UUID(0, 1);
        var simulation = new RollbackSimulation<Integer, Boolean, String>() {
            int state;
            public Integer snapshot() { output.captureRollbackState(); return state; }
            public void restore(Integer value) { output.restoreRollbackState(null); state = value; }
            public Boolean predict(UUID id, Boolean previous) { return false; }
            public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<String> step) {
                try (var binding = output.open(step)) {
                    if (inputs.get(player)) { state++; output.accept("action:" + tick); }
                }
            }
        };
        var engine = new RollbackEngine<>(simulation, Map.of(player, false), new RollbackEngine.Limits(4, 2, 20, 50_000_000), 0);
        assertTrue(engine.advance().head().effects().isEmpty());
        engine.submit(player, 1, true);
        var revised = engine.reconcile();
        assertEquals(List.of("action:1"), revised.head().effects());
        assertTrue(engine.advance().head().effects().isEmpty(), "Missing input must not duplicate the one-shot output");
        assertThrows(IllegalStateException.class, () -> output.accept("late callback"));
    }
}
