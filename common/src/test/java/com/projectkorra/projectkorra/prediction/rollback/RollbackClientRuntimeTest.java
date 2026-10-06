package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RollbackClientRuntimeTest {
    private static final UUID ID = new UUID(0, 7), A = new UUID(0, 1), B = new UUID(0, 2);
    private static final RollbackPlayerInput IDLE = new RollbackPlayerInput(new RollbackMovementInput(0, 0, false, 0, 0), false, List.of());
    private static final Map<UUID, RollbackPlayerInput> INITIAL = Map.of(A, IDLE, B, IDLE);
    private static final long ANCHOR = 1000, SEED = 77;

    @Test void predictsBeforeSendingAndNeverRepeatsOneShotActionsDuringCorrection() {
        var f = new Fixture(8);
        assertFalse(f.runtime.action(RollbackInputActions.Kind.RIGHT_CLICK, -1, 0, 0));
        f.runtime.start(ANCHOR);
        f.runtime.action(RollbackInputActions.Kind.RIGHT_CLICK, -1, 30, 10);
        f.runtime.action(RollbackInputActions.Kind.SLOT_CHANGE, 4, 60, -10);
        f.controls = new RollbackClientRuntime.Held(new RollbackMovementInput(1, 0, true, 90, -20), true);
        f.runtime.tick(ANCHOR + 1);
        assertEquals(2, f.engine.head().state().actions());
        var packet = f.sent.getFirst();
        assertEquals(ANCHOR + 1, packet.clientTick());
        assertEquals(List.of(1L, 2L), packet.actions().stream().map(RollbackInputPacket.Edge::sequence).toList());
        assertEquals(List.of(30F, 60F), packet.actions().stream().map(RollbackInputPacket.Edge::yaw).toList());
        assertEquals(packet.playerInput(A, SEED), f.engine.head().inputs().get(A));
        f.runtime.tick(ANCHOR + 2);
        assertEquals(2, f.engine.head().state().actions()); assertTrue(f.sent.getLast().actions().isEmpty());
        for (var input : f.sent) assertEquals(RollbackSession.Status.ACCEPTED, f.server.receive(f.connection, input).status());
        f.server.receive(f.opponent, new RollbackInputPacket(ID, 1, new RollbackMovementInput(0, 1, false, 0, 0), false, List.of()));
        f.server.advance(); var publication = f.server.publish();
        f.runtime.authority(publication); f.runtime.authority(publication);
        assertEquals(2, f.sent.size(), "Replay must not send old inputs again");
        assertEquals(2, f.engine.head().state().actions());
        assertEquals(2, f.engine.head().state().distance());
        f.runtime.action(RollbackInputActions.Kind.SWING, -1, 0, 0);
        f.runtime.tick(ANCHOR + 3);
        assertEquals(3, f.sent.getLast().actions().getFirst().sequence());
    }

    @Test void missingInputIsRetractedOnlyOnServerFinalizationAndEffectsAreDeliveredOnce() {
        var f = new Fixture(8); f.runtime.start(ANCHOR);
        f.runtime.action(RollbackInputActions.Kind.RIGHT_CLICK, -1, 0, 0);
        f.runtime.tick(ANCHOR + 1);
        assertEquals(1, f.engine.head().state().actions());
        var finalized = new ArrayList<RollbackEngine.Effect<State>>();
        for (int tick = 1; tick <= 4; tick++) {
            finalized.addAll(f.server.advance().finalizedEffects());
            var publication = f.server.publish();
            f.runtime.authority(publication); f.runtime.authority(publication);
        }
        assertEquals(0, f.engine.head().state().actions());
        assertEquals(finalized, f.outputs.stream().flatMap(update -> update.finalizedEffects().stream()).toList());
        assertEquals(1, f.sent.size()); assertEquals(2, f.engine.confirmed().tick());
    }

    @Test void fullHistoryWaitsWithoutRetimingInputThenContinuesAfterAuthorityArrives() {
        var f = new Fixture(3); f.runtime.start(ANCHOR);
        for (int tick = 1; tick <= 4; tick++) f.runtime.tick(ANCHOR + tick);
        assertEquals(3, f.engine.head().tick()); assertEquals(0, f.engine.confirmed().tick());
        assertEquals(List.of(1001L, 1002L, 1003L, 1004L), f.sent.stream().map(RollbackInputPacket::clientTick).toList());
        var publications = new ArrayList<RollbackAuthorityUpdate>();
        for (int tick = 1; tick <= 4; tick++) {
            assertEquals(RollbackSession.Status.ACCEPTED, f.server.receive(f.connection, f.sent.get(tick - 1)).status());
            f.server.advance(); publications.add(f.server.publish());
        }
        publications.forEach(f.runtime::authority);
        f.runtime.tick(ANCHOR + 5);
        assertEquals(5, f.engine.head().tick()); assertEquals(2, f.engine.confirmed().tick());
        assertEquals(1005, f.sent.getLast().clientTick()); assertFalse(f.runtime.failed());
    }

    @Test void prolongedAuthorityLossAndClockJumpsCannotRewriteTheInputAnchor() {
        var full = new Fixture(1); full.runtime.start(ANCHOR);
        full.runtime.tick(ANCHOR + 1); full.runtime.tick(ANCHOR + 2); full.runtime.tick(ANCHOR + 3);
        assertThrows(IllegalStateException.class, () -> full.runtime.tick(ANCHOR + 4));
        assertTrue(full.runtime.failed()); assertEquals(3, full.sent.size());
        assertEquals(0, full.engine.confirmed().tick());
        var skipped = new Fixture(8); skipped.runtime.start(ANCHOR);
        assertThrows(IllegalStateException.class, () -> skipped.runtime.tick(ANCHOR + 2));
        assertTrue(skipped.runtime.failed()); assertTrue(skipped.sent.isEmpty());
    }

    @Test void outputAndSendFailureStopFurtherInputAndCleanupRunsOnceEvenWhenItFails() {
        for (boolean sending : List.of(false, true)) {
            var f = new Fixture(8); f.runtime.start(ANCHOR);
            f.failSend = sending; f.failOutput = !sending;
            assertThrows(IllegalStateException.class, () -> f.runtime.tick(ANCHOR + 1));
            assertTrue(f.runtime.failed()); assertFalse(f.runtime.action(RollbackInputActions.Kind.SWING, -1, 0, 0));
            assertThrows(IllegalStateException.class, () -> f.runtime.tick(ANCHOR + 2));
            f.failStop = true;
            var failure = new RollbackStartServerEndpoint.Failure(RollbackStartPacket.AbortReason.STATE_CHANGED, "test", null);
            assertThrows(IllegalStateException.class, () -> f.runtime.stop(failure));
            f.runtime.stop(failure); assertEquals(1, f.stops);
        }
    }

    @Test void actionBudgetFailsInsteadOfDroppingClicksAndStoppingDuringPresentationSendsNothing() {
        var f = new Fixture(8); f.runtime.start(ANCHOR);
        for (int i = 0; i < RollbackPlayerInput.MAXIMUM_ACTIONS; i++) f.runtime.action(RollbackInputActions.Kind.SWING, -1, 0, 0);
        assertThrows(IllegalStateException.class, () -> f.runtime.action(RollbackInputActions.Kind.SWING, -1, 0, 0));
        assertTrue(f.runtime.failed()); assertTrue(f.sent.isEmpty());
        var cancelled = new Fixture(8); cancelled.runtime.start(ANCHOR); cancelled.stopDuringOutput = true;
        cancelled.runtime.tick(ANCHOR + 1);
        assertTrue(cancelled.sent.isEmpty()); assertEquals(1, cancelled.stops); assertFalse(cancelled.runtime.running());
    }

    @Test void negotiatedEndpointStartsTheActualInputOwnerAndRetainsItsAbortRoute() {
        var f = new Fixture(8); UUID challenge = new UUID(0, 9);
        var control = new ArrayList<RollbackStartPacket.Message>();
        var endpoint = new RollbackStartClientEndpoint(control::add);
        endpoint.prepare(ID, 990, 40, () -> true, f.runtime);
        endpoint.receive(new RollbackStartNegotiation.Probe(ID, challenge), 991);
        var schedule = new RollbackStartNegotiation.Schedule(ID, challenge, 100, ANCHOR);
        endpoint.receive(schedule, 992); endpoint.receive(new RollbackStartPacket.Commit(schedule), 995);
        endpoint.tick(999); assertFalse(f.runtime.running());
        endpoint.tick(1000); assertTrue(f.runtime.running()); assertTrue(f.sent.isEmpty());
        f.runtime.action(RollbackInputActions.Kind.RIGHT_CLICK, -1, 0, 0);
        endpoint.tick(1001); assertEquals(1001, f.sent.getFirst().clientTick());
        f.server.receive(f.connection, f.sent.getFirst()); f.server.advance();
        endpoint.authority(f.server.publish());
        assertEquals(1, f.engine.head().state().actions());
        endpoint.receive(new RollbackStartPacket.Abort(ID, challenge, RollbackStartPacket.AbortReason.OPT_OUT), 1001);
        endpoint.tick(1002);
        assertEquals(1, f.stops); assertEquals(1, f.sent.size()); assertFalse(endpoint.ownsSession());
        assertEquals(2, control.size(), "Only probe/schedule replies; server abort must not echo");
    }

    private record State(int distance, int actions) { }
    private static final class Simulation implements RollbackSimulation<State, RollbackPlayerInput, State> {
        State value = new State(0, 0);
        @Override public State snapshot() { return value; }
        @Override public void restore(State snapshot) { value = snapshot; }
        @Override public RollbackPlayerInput predict(UUID player, RollbackPlayerInput previous) { return previous.predict(); }
        @Override public void step(long tick, Map<UUID, RollbackPlayerInput> inputs, RollbackStep<State> effects) {
            value = new State(value.distance + inputs.values().stream().mapToInt(input -> Math.round(input.movement().forward())).sum(),
                    value.actions + inputs.values().stream().mapToInt(input -> input.actions().size()).sum());
            effects.emit(value);
        }
    }
    private static final class Fixture implements RollbackClientRuntime.Output<State, State> {
        final Object connection = new Object(), opponent = new Object();
        final RollbackEngine<State, RollbackPlayerInput, State> engine;
        final RollbackClientRuntime<State, State> runtime;
        final RollbackSession<State, State> server = new RollbackSession<>(ID, SEED,
                new RollbackEngine<>(new Simulation(), INITIAL, new RollbackEngine.Limits(2, 1, 20, 50_000_000), 0, 0),
                List.of(new RollbackSession.Peer(A, connection, ANCHOR), new RollbackSession.Peer(B, opponent, 0)));
        final List<RollbackInputPacket> sent = new ArrayList<>();
        final List<RollbackEngine.Update<State, RollbackPlayerInput, State>> outputs = new ArrayList<>();
        RollbackClientRuntime.Held controls = new RollbackClientRuntime.Held(IDLE.movement(), false);
        boolean failOutput, failSend, failStop, stopDuringOutput;
        int stops;
        Fixture(int history) {
            engine = RollbackEngine.replica(new Simulation(), INITIAL, new RollbackEngine.Limits(history, 1, 20, 50_000_000), 0, 0);
            runtime = new RollbackClientRuntime<>(ID, A, SEED, engine, () -> controls, packet -> {
                assertFalse(outputs.isEmpty());
                assertTrue(engine.head().tick() > 0, "Local simulation must not wait for network send");
                if (failSend) throw new IllegalStateException("send failed");
                sent.add(RollbackInputPacket.decode(packet.encode()));
            }, this);
        }
        @Override public void update(RollbackEngine.Update<State, RollbackPlayerInput, State> update) {
            if (failOutput) throw new IllegalStateException("presentation failed");
            outputs.add(update);
            if (stopDuringOutput) runtime.stop(new RollbackStartServerEndpoint.Failure(RollbackStartPacket.AbortReason.STATE_CHANGED, "test", null));
        }
        @Override public void stop(RollbackStartServerEndpoint.Failure failure) {
            stops++; if (failStop) throw new IllegalStateException("cleanup failed");
        }
    }
}
