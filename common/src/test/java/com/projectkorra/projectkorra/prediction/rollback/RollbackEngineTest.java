package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static com.projectkorra.projectkorra.prediction.rollback.RollbackEngine.Submission.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackEngineTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);
    private static final long STEP = 50_000_000L;

    private record Input(int motion, boolean activate) {
        static Input idle() { return new Input(0, false); }
    }

    private record State(int a, int b, int activations, long millis, List<Integer> path) {
        State { path = List.copyOf(path); }
    }

    private static class Simulation implements RollbackSimulation<State, Input, String> {
        State state = new State(0, 0, 0, 0, List.of());
        RollbackStep<String> lastStep;

        @Override public State snapshot() { return state; }
        @Override public void restore(State snapshot) { state = snapshot; }
        @Override public Input predict(UUID id, Input previous) { return new Input(previous.motion(), false); }

        @Override public void step(long tick, Map<UUID, Input> inputs, RollbackStep<String> context) {
            assertEquals(List.of(A, B), List.copyOf(inputs.keySet()));
            lastStep = context;
            // Variable movement is deliberately tick-dependent and must be replayed.
            int a = state.a() + inputs.get(A).motion() * (tick % 3 == 0 ? 3 : 1);
            int b = state.b() + inputs.get(B).motion();
            int activations = state.activations();
            for (Map.Entry<UUID, Input> entry : inputs.entrySet()) {
                if (entry.getValue().activate()) {
                    activations++;
                    context.emit("activate:" + entry.getKey());
                }
            }
            if (a != b && Math.abs(a - b) <= 1) context.emit("contact:" + a + ":" + b);
            var path = new ArrayList<>(state.path());
            path.add(a - b);
            state = new State(a, b, activations, RollbackClock.millis(), path);
        }
    }

    private RollbackEngine<State, Input, String> engine(Simulation simulation, int window) {
        return new RollbackEngine<>(simulation, Map.of(B, Input.idle(), A, Input.idle()),
                new RollbackEngine.Limits(window, 2, 100, STEP), 1_000);
    }

    @Test void authoritativeStateRepairsDivergenceWithoutRepeatingFinalizedEffectsOrLosingInputs() {
        var simulation = new Simulation();
        var replica = RollbackEngine.replica(simulation, Map.of(A, Input.idle(), B, Input.idle()),
                new RollbackEngine.Limits(8, 2, 100, STEP), 1000, 0);
        replica.submit(A, 1, new Input(1, true)); replica.advance();
        var finalEffects = replica.confirm(1).finalizedEffects(); assertFalse(finalEffects.isEmpty());
        replica.submit(A, 2, new Input(2, true)); replica.advance(); replica.advance();
        replica.correct(B, 2, new Input(3, false)); // Also repair a pending input revision.
        var baseline = new State(10, 20, 1, 1050, List.of(-10));
        var update = replica.correctState(1, baseline);
        assertEquals(2, update.replayedFrom()); assertTrue(update.finalizedEffects().isEmpty());
        assertEquals(baseline, update.confirmed().state());
        assertEquals(18, update.head().state().a()); assertEquals(26, update.head().state().b());
        assertEquals(2, update.head().state().activations());
        assertEquals(RollbackEngine.Submission.DUPLICATE, replica.submit(A, 2, new Input(2, true)));
        assertEquals(RollbackEngine.Submission.CONFLICTING_INPUT, replica.submit(A, 2, Input.idle()));
        assertEquals(-1, replica.reconcile().replayedFrom());
        var confirmed = replica.confirm(3);
        assertTrue(confirmed.finalizedEffects().stream().allMatch(effect -> effect.tick() > 1));
        assertEquals(1, confirmed.finalizedEffects().stream().filter(effect -> effect.value().startsWith("activate:")).count());
        assertTrue(replica.confirm(3).finalizedEffects().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> replica.correctState(2, baseline));
        assertThrows(IllegalArgumentException.class, () -> replica.correctState(4, baseline));
        assertThrows(IllegalStateException.class, () -> engine(new Simulation(), 4).correctState(0, baseline));
    }

    @Test void stateRepairAtHeadChangesStateWithoutReplayingAndFailedImportPoisonsSession() {
        var simulation = new Simulation();
        var replica = RollbackEngine.replica(simulation, Map.of(A, Input.idle(), B, Input.idle()),
                new RollbackEngine.Limits(8, 2, 100, STEP), 1000, 0);
        var corrected = new State(4, 8, 0, 1000, List.of());
        var update = replica.correctState(0, corrected);
        assertEquals(corrected, simulation.state); assertEquals(corrected, update.head().state());
        assertEquals(update.head(), update.confirmed()); assertEquals(-1, update.replayedFrom());
        assertEquals(1, update.revision()); assertEquals(0, replica.diagnostics().replayedSteps());
        var broken = RollbackEngine.replica(new Simulation() {
            @Override public void restore(State state) { throw new IllegalStateException("import failed"); }
        }, Map.of(A, Input.idle(), B, Input.idle()), new RollbackEngine.Limits(8, 2, 100, STEP), 1000, 0);
        assertThrows(IllegalArgumentException.class, () -> broken.correctState(1, corrected));
        assertFalse(broken.diagnostics().failed());
        assertThrows(IllegalStateException.class, () -> broken.correctState(0, corrected));
        assertTrue(broken.diagnostics().failed());
        assertThrows(IllegalStateException.class, broken::advance);
        assertThrows(IllegalStateException.class, () -> broken.correctState(0, corrected));
    }

    @Test void lateInputReplaysVariableMovementAndReplacesProvisionalEffects() {
        var onTime = engine(new Simulation(), 4);
        var late = engine(new Simulation(), 4);
        for (int tick = 1; tick <= 3; tick++) {
            Input a = new Input(1, tick == 1);
            Input b = new Input(2, false);
            onTime.submit(A, tick, a);
            onTime.submit(B, tick, b);
            late.submit(A, tick, a);
            onTime.advance();
            assertTrue(late.advance().finalizedEffects().isEmpty());
        }
        assertNotEquals(onTime.head().state(), late.head().state());
        for (int tick = 3; tick >= 1; tick--) assertEquals(ACCEPTED, late.submit(B, tick, new Input(2, false)));
        var corrected = late.reconcile();
        assertEquals(1, corrected.replayedFrom());
        assertEquals(onTime.head(), corrected.head());
        assertEquals(1, corrected.revision());
        assertEquals(3, late.diagnostics().replayedSteps());
        assertEquals(-1, late.reconcile().replayedFrom());
        assertTrue(corrected.finalizedEffects().isEmpty());
    }

    @Test void oneShotActionsAreNotRepeatedWhileMovementIsPredicted() {
        var engine = engine(new Simulation(), 3);
        engine.submit(A, 1, new Input(1, true));
        engine.advance();
        engine.advance();
        engine.advance();
        assertEquals(1, engine.head().state().activations());
        assertEquals(5, engine.head().state().a());
    }

    @Test void finalizedEffectsAreReturnedOnceAndHistoryCannotBeChangedAfterCommit() {
        var engine = engine(new Simulation(), 2);
        engine.submit(A, 1, new Input(1, true));
        assertTrue(engine.advance().finalizedEffects().isEmpty());
        assertTrue(engine.advance().finalizedEffects().isEmpty());
        var finalized = engine.advance();
        assertEquals(1, finalized.confirmed().tick());
        assertEquals(List.of(new RollbackEngine.Effect<>(1, 0, "activate:" + A),
                new RollbackEngine.Effect<>(1, 1, "contact:1:0")), finalized.finalizedEffects());
        assertEquals(FINALIZED, engine.submit(B, 1, new Input(5, true)));
        assertTrue(engine.reconcile().finalizedEffects().isEmpty());
        assertTrue(engine.advance().finalizedEffects().stream().noneMatch(effect -> effect.tick() == 1));
        assertEquals(3, engine.diagnostics().snapshots());
    }

    @Test void removedPredictionsNeverReachTheFinalizedEffectStream() {
        var engine = engine(new Simulation(), 2);
        engine.submit(A, 1, new Input(1, false));
        assertEquals(List.of("contact:1:0"), engine.advance().head().effects());
        engine.submit(B, 1, new Input(5, false));
        engine.advance();
        assertTrue(engine.advance().finalizedEffects().isEmpty());
        assertEquals(5, engine.confirmed().state().b());
    }

    @Test void inputsAreBoundedAuthenticatedByCallerAndImmutableOnceAccepted() {
        var engine = engine(new Simulation(), 2);
        assertEquals(UNKNOWN_PARTICIPANT, engine.submit(new UUID(0, 3), 1, Input.idle()));
        assertEquals(FINALIZED, engine.submit(A, 0, Input.idle()));
        assertEquals(TOO_FAR_AHEAD, engine.submit(A, 4, Input.idle()));
        assertEquals(TOO_FAR_AHEAD, engine.submit(A, Long.MAX_VALUE, Input.idle()));
        assertEquals(ACCEPTED, engine.submit(A, 3, new Input(1, true)));
        assertEquals(DUPLICATE, engine.submit(A, 3, new Input(1, true)));
        assertEquals(CONFLICTING_INPUT, engine.submit(A, 3, new Input(2, true)));
        engine.advance();
        engine.advance();
        assertEquals(1, engine.advance().head().state().activations());
        assertEquals(3, engine.head().state().a());
    }

    @Test void outOfOrderInputsConvergeToTheOnTimeRunIncludingEveryCommittedEffect() {
        var reference = engine(new Simulation(), 5);
        var delayed = engine(new Simulation(), 5);
        record Packet(long tick, UUID player, Input input) {}
        Map<Integer, List<Packet>> delivery = new LinkedHashMap<>();
        var expectedEffects = new ArrayList<RollbackEngine.Effect<String>>();
        var actualEffects = new ArrayList<RollbackEngine.Effect<String>>();
        Random random = new Random(91);
        for (int tick = 1; tick <= 300; tick++) {
            for (UUID player : List.of(A, B)) {
                Input input = tick <= 280 ? new Input(random.nextInt(3) - 1, random.nextInt(9) == 0) : Input.idle();
                reference.submit(player, tick, input);
                delivery.computeIfAbsent(tick + random.nextInt(5), ignored -> new ArrayList<>())
                        .add(new Packet(tick, player, input));
            }
            for (Packet packet : delivery.getOrDefault(tick, List.of()).reversed()) {
                assertEquals(ACCEPTED, delayed.submit(packet.player(), packet.tick(), packet.input()));
                assertEquals(DUPLICATE, delayed.submit(packet.player(), packet.tick(), packet.input()));
            }
            expectedEffects.addAll(reference.advance().finalizedEffects());
            actualEffects.addAll(delayed.advance().finalizedEffects());
            assertEquals(reference.confirmed(), delayed.confirmed());
            assertTrue(delayed.diagnostics().snapshots() <= 6);
            assertTrue(delayed.diagnostics().queuedInputTicks() <= 8);
        }
        assertEquals(expectedEffects, actualEffects);
        assertEquals(reference.head().state(), delayed.head().state());
    }

    @Test void replayRestoresSimulationTimeAndDoesNotLeakItIntoTheLivePlatform() {
        var engine = engine(new Simulation(), 3);
        assertFalse(RollbackClock.active());
        engine.advance();
        engine.advance();
        assertEquals(1_100, engine.head().state().millis());
        assertFalse(RollbackClock.active());
        engine.submit(A, 1, new Input(1, false));
        engine.reconcile();
        assertEquals(1_100, engine.head().state().millis());
        assertFalse(RollbackClock.active());
    }

    @Test void aFailedStepStopsTheSessionAndCannotPublishPartialEffects() {
        Simulation simulation = new Simulation() {
            @Override public void step(long tick, Map<UUID, Input> inputs, RollbackStep<String> context) {
                super.step(tick, inputs, context);
                throw new IllegalStateException("adapter failed");
            }
        };
        var engine = engine(simulation, 3);
        assertThrows(IllegalStateException.class, engine::advance);
        assertTrue(engine.diagnostics().failed());
        assertEquals(0, engine.confirmed().tick());
        assertFalse(RollbackClock.active());
        assertThrows(IllegalStateException.class, engine::advance);
        assertThrows(IllegalStateException.class, () -> simulation.lastStep.emit("late mutation"));
    }

    @Test void zeroRollbackWindowStillAcceptsTheNextTickAndCommitsImmediately() {
        var engine = new RollbackEngine<>(new Simulation(), Map.of(A, Input.idle(), B, Input.idle()),
                new RollbackEngine.Limits(0, 0, 10, STEP), 0);
        assertEquals(ACCEPTED, engine.submit(A, 1, new Input(2, true)));
        assertEquals(TOO_FAR_AHEAD, engine.submit(A, 2, Input.idle()));
        var update = engine.advance();
        assertEquals(update.head(), update.confirmed());
        assertEquals(1, update.finalizedEffects().size());
        assertEquals(FINALIZED, engine.submit(B, 1, Input.idle()));
        assertEquals(1, engine.diagnostics().snapshots());
    }
}
