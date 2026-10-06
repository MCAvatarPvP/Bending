package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.listener.CommonInputHandler;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackTimedExecutionServicesTest {
    private static final class Time implements RollbackTimedExecutionServices.WorldTime<Long> {
        long tick;
        @Override public void advanceTick(long next) {
            if (next != tick + 1) throw new IllegalStateException("Skipped or unrestored tick");
            tick = next;
        }
        @Override public Long captureRollbackState() { return tick; }
        @Override public void restoreRollbackState(Long value) { tick = value; }
    }
    private static final class Phases implements RollbackPlayerExecution.Services<String, Integer> {
        final Time time; int completed; boolean fail;
        Phases(Time time) { this.time = time; }
        @Override public void begin(RollbackStep<String> effects) {
            assertEquals(effects.tick(), time.tick);
            effects.emit("world-time:" + time.tick);
            if (fail) throw new IllegalStateException("Native begin failed");
        }
        @Override public void action(RollbackPlayer player, RollbackPlayerInput.Edge edge, CommonInputHandler.InputResult result) { }
        @Override public void tickWorld(long tick) { assertEquals(time.tick, tick); }
        @Override public void end() { completed++; }
        @Override public Integer captureRollbackState() { return completed; }
        @Override public void restoreRollbackState(Integer value) { completed = value; }
    }
    @Test void worldTimeAdvancesBeforeNativePhasesAndRewindsWithTheExecutionRoots() {
        var time = new Time(); var phases = new Phases(time);
        var services = new RollbackTimedExecutionServices<String>(time, phases);
        var graph = new RollbackStateGraph(value -> false, field -> true, 100);
        var before = graph.capture(List.of(services), List.of());
        var first = new RollbackStep<String>(1, 4);
        assertThrows(IllegalStateException.class, () -> services.begin(first));
        try (var clock = RollbackClock.at(0, 1, 50_000_000)) {
            services.begin(first);
            assertThrows(IllegalStateException.class, services::captureRollbackState);
            try (var nested = RollbackClock.at(0, 1, 50_000_000)) {
                assertThrows(IllegalStateException.class, () -> services.tickWorld(1));
            }
            services.tickWorld(1); services.end(); services.end();
        }
        assertEquals(1, phases.completed); assertEquals(1, time.tick);
        before.restore(); assertEquals(0, phases.completed); assertEquals(0, time.tick);
        var replay = new RollbackStep<String>(1, 4);
        try (var clock = RollbackClock.at(0, 1, 50_000_000)) {
            services.begin(replay); services.tickWorld(1); services.end();
        }
        assertEquals(first.finish(), replay.finish());
        before.restore(); phases.fail = true;
        try (var clock = RollbackClock.at(0, 1, 50_000_000)) {
            assertThrows(IllegalStateException.class, () -> services.begin(new RollbackStep<>(1, 4)));
            services.end();
        }
        assertNull(services.captureRollbackState());
        before.restore(); assertEquals(0, time.tick); assertEquals(0, phases.completed);
    }
}
