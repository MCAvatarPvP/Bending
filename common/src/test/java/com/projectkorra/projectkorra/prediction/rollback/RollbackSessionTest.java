package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;
import static com.projectkorra.projectkorra.prediction.rollback.RollbackSession.Status.*;

class RollbackSessionTest {
    private static final UUID SESSION = new UUID(0, 100), A = new UUID(0, 1), B = new UUID(0, 2);
    private static final RollbackMovementInput STILL = new RollbackMovementInput(0, 0, false, 0, 0);
    private static final RollbackPlayerInput IDLE = new RollbackPlayerInput(STILL, false, List.of());
    private static RollbackInputPacket packet(long tick, long sequence) {
        return new RollbackInputPacket(SESSION, 100 + tick, STILL, false, sequence == 0 ? List.of()
                : List.of(new RollbackInputPacket.Edge(sequence, RollbackInputActions.Kind.SWING, -1, 0, 0)));
    }

    @Test void authenticatedConnectionAndSessionAnchorControlPlayerAndTickWithoutClamping() {
        var fixture = new Fixture(); var session = fixture.session;
        assertEquals(UNKNOWN_CONNECTION, session.receive(new String("a"), packet(1, 1)).status());
        assertEquals(STALE_SESSION, session.receive(fixture.a, new RollbackInputPacket(new UUID(0, 101), 101, STILL, false, List.of())).status());
        assertEquals(INVALID_TICK, session.receive(fixture.a, packet(0, 0)).status());
        assertEquals(TOO_FAR_AHEAD, session.receive(fixture.a, packet(4, 0)).status());
        assertEquals(TOO_FAR_AHEAD, session.receive(fixture.a, new RollbackInputPacket(SESSION, Long.MAX_VALUE, STILL, false, List.of())).status());
        var b = new RollbackInputPacket(SESSION, 501, STILL, false, packet(1, 1).actions());
        assertEquals(new RollbackSession.Receipt(ACCEPTED, 1), session.receive(fixture.b, b));
        var result = session.advance();
        assertEquals(0, result.head().inputs().get(A).actions().size());
        assertEquals(1, result.head().inputs().get(B).actions().size());
        assertEquals(List.of("2:1:" + b.playerInput(B, 77).actions().getFirst().action().seed()), result.head().effects());
        assertEquals(List.of(1L), session.acknowledgement(fixture.b).receivedTicks());
        assertTrue(session.acknowledgement(fixture.a).receivedTicks().isEmpty());
    }

    @Test void outOfOrderLateFramesReplayOnceAndReceiptsDoNotRewind() {
        var direct = new Fixture(); var late = new Fixture();
        direct.session.receive(direct.a, packet(1, 1)); direct.session.receive(direct.a, packet(2, 2));
        direct.session.advance(); var expected = direct.session.advance();
        late.session.advance(); late.session.advance();
        assertTrue(late.simulation.history.isEmpty());
        assertEquals(ACCEPTED, late.session.receive(late.a, packet(2, 2)).status());
        assertEquals(List.of(2L), late.session.acknowledgement(late.a).receivedTicks());
        assertEquals(ACTION_ORDER, late.session.receive(late.a, packet(1, 3)).status());
        assertEquals(ACCEPTED, late.session.receive(late.a, packet(1, 1)).status());
        var corrected = late.session.reconcile();
        assertEquals(1, corrected.replayedFrom());
        assertEquals(expected.head().state(), corrected.head().state());
        assertEquals(expected.head().effects(), corrected.head().effects());
        assertEquals(DUPLICATE, late.session.receive(late.a, packet(1, 1)).status());
        assertEquals(CONFLICTING_INPUT, late.session.receive(late.a, packet(1, 7)).status());
        var acknowledgement = late.session.acknowledgement(late.a);
        assertEquals(1, acknowledgement.revision()); assertEquals(2, acknowledgement.headTick());
        assertEquals(0, acknowledgement.finalizedTick()); assertEquals(List.of(1L, 2L), acknowledgement.receivedTicks());
        for (int tick = 3; tick <= 6; tick++) assertEquals(direct.session.advance().finalizedEffects(), late.session.advance().finalizedEffects());
        assertEquals(FINALIZED, late.session.receive(late.a, packet(1, 1)).status());
        assertEquals(ACTION_ORDER, late.session.receive(late.a, packet(7, 2)).status());
        assertEquals(ACCEPTED, late.session.receive(late.a, packet(7, 3)).status());
        assertEquals(List.of(7L), late.session.acknowledgement(late.a).receivedTicks());
        assertFalse(late.session.closed()); assertFalse(late.engine.diagnostics().failed());
    }

    @Test void checksActionOrderAcrossEmptyFramesAndPrunesBoundedReceipts() {
        var fixture = new Fixture(); var session = fixture.session;
        session.receive(fixture.a, packet(3, 3)); session.receive(fixture.a, packet(2, 0));
        assertEquals(ACTION_ORDER, session.receive(fixture.a, packet(1, 3)).status());
        assertEquals(ACCEPTED, session.receive(fixture.a, packet(1, 1)).status());
        session.advance(); session.advance(); session.advance();
        for (int tick = 4; tick <= 100; tick++) {
            assertEquals(ACCEPTED, session.receive(fixture.a, packet(tick, tick)).status()); session.advance();
            assertTrue(session.acknowledgement(fixture.a).receivedTicks().size() <= 3);
        }
        assertEquals(97, session.acknowledgement(fixture.a).finalizedTick());
        assertEquals(List.of(98L, 99L, 100L), session.acknowledgement(fixture.a).receivedTicks());
        assertEquals(ACTION_ORDER, session.receive(fixture.a, packet(101, 50)).status());
    }

    @Test void closeAndSimulationFailureStopBothPeersAndThreadMisuseIsRejected() {
        var fixture = new Fixture();
        var crossed = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> fixture.session.receive(fixture.a, packet(1, 1))).join());
        assertInstanceOf(IllegalStateException.class, crossed.getCause());
        fixture.simulation.fail = true;
        assertThrows(IllegalStateException.class, fixture.session::advance);
        assertTrue(fixture.session.closed());
        assertEquals(CLOSED, fixture.session.receive(fixture.a, packet(1, 1)).status());
        assertEquals(CLOSED, fixture.session.receive(fixture.b, packet(1, 1)).status());
        assertThrows(IllegalStateException.class, fixture.session::reconcile);
        assertThrows(IllegalStateException.class, () -> fixture.session.acknowledgement(fixture.a));
        var stopped = new Fixture(); stopped.session.close(); stopped.session.close();
        assertThrows(IllegalStateException.class, stopped.session::advance);
        assertEquals(0, stopped.engine.diagnostics().tick());
    }

    @Test void rejectsIncompleteDuplicateAndAlreadyUsedSessionRosters() {
        var fixture = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> new RollbackSession<>(SESSION, 77, fixture.engine, List.of(new RollbackSession.Peer(A, fixture.a, 100))));
        assertThrows(IllegalArgumentException.class, () -> new RollbackSession<>(SESSION, 77, fixture.engine, List.of(
                new RollbackSession.Peer(A, fixture.a, 100), new RollbackSession.Peer(B, fixture.a, 500))));
        fixture.engine.submit(A, 1, IDLE);
        assertThrows(IllegalArgumentException.class, () -> new RollbackSession<>(SESSION, 77, fixture.engine, fixture.peers()));
    }

    private static final class Fixture {
        final Object a = new String("a"), b = new Object();
        final Simulation simulation = new Simulation();
        final RollbackEngine<List<String>, RollbackPlayerInput, String> engine = new RollbackEngine<>(simulation, Map.of(A, IDLE, B, IDLE),
                new RollbackEngine.Limits(3, 2, 100, 50_000_000), 1_000);
        final RollbackSession<List<String>, String> session = new RollbackSession<>(SESSION, 77, engine, peers());
        List<RollbackSession.Peer> peers() { return List.of(new RollbackSession.Peer(A, a, 100), new RollbackSession.Peer(B, b, 500)); }
    }
    private static final class Simulation implements RollbackSimulation<List<String>, RollbackPlayerInput, String> {
        final List<String> history = new ArrayList<>();
        boolean fail;
        @Override public List<String> snapshot() { return List.copyOf(history); }
        @Override public void restore(List<String> snapshot) { history.clear(); history.addAll(snapshot); }
        @Override public RollbackPlayerInput predict(UUID participant, RollbackPlayerInput previous) { return previous.predict(); }
        @Override public void step(long tick, Map<UUID, RollbackPlayerInput> inputs, RollbackStep<String> effects) {
            if (fail) throw new IllegalStateException("Injected simulation failure");
            inputs.forEach((id, input) -> input.actions().forEach(edge -> {
                String value = id.getLeastSignificantBits() + ":" + edge.action().sequence() + ":" + edge.action().seed();
                history.add(value); effects.emit(value);
            }));
        }
    }
}
