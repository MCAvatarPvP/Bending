package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;
import static com.projectkorra.projectkorra.prediction.rollback.RollbackIngress.Dispatch.*;

class RollbackIngressTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3), D = new UUID(0, 4);
    private static final RollbackPlayerInput IDLE = new RollbackPlayerInput(new RollbackMovementInput(0, 0, false, 0, 0), false, List.of());

    @Test void authenticatesBeforeDecodeAndRoutesLateInputToTheExistingSessionTimeline() {
        var fixture = new Fixture(A, B);
        var ingress = new RollbackIngress(20);
        var registration = ingress.enroll(fixture.session, ignored -> { });
        assertTrue(ingress.blocksLegacy(A)); assertTrue(ingress.blocksLegacy(B)); assertFalse(ingress.blocksLegacy(C));
        assertEquals(UNENROLLED, ingress.receive(new String("same-value"), null).dispatch());
        assertEquals(MALFORMED, ingress.receive(fixture.a, new byte[]{1}).dispatch());
        var stale = packet(UUID.randomUUID(), 1, 1);
        assertEquals(RollbackSession.Status.STALE_SESSION, ingress.receive(fixture.a, stale).receipt().status());
        assertEquals(RollbackSession.Status.ACCEPTED, ingress.receive(fixture.b, packet(fixture.session.id(), 1, 1)).receipt().status());
        fixture.session.advance();
        assertEquals(2, fixture.session.advance().head().state());
        assertEquals(RollbackSession.Status.ACCEPTED, ingress.receive(fixture.a, packet(fixture.session.id(), 1, -1)).receipt().status());
        var corrected = fixture.session.reconcile();
        assertEquals(1, corrected.replayedFrom());
        assertEquals(0, corrected.head().state());
        assertEquals(RollbackSession.Status.DUPLICATE, ingress.receive(fixture.a, packet(fixture.session.id(), 1, -1)).receipt().status());
        assertEquals(RollbackSession.Status.CONFLICTING_INPUT, ingress.receive(fixture.a, packet(fixture.session.id(), 1, 1)).receipt().status());
        assertThrows(IllegalStateException.class, registration::finishStop);
        registration.stop(RollbackIngress.StopReason.REQUESTED);
        assertTrue(ingress.blocksLegacy(A)); assertTrue(ingress.blocksLegacy(B));
        assertEquals(STOPPING, ingress.receive(fixture.b, packet(fixture.session.id(), 2, 1)).dispatch());
        registration.finishStop();
        assertFalse(ingress.blocksLegacy(A)); assertFalse(ingress.blocksLegacy(B));
    }

    @Test void boundsMalformedAndDuplicateWorkPerPeerAndBudgetsDoNotRewind() {
        var fixture = new Fixture(A, B);
        var ingress = new RollbackIngress(2);
        ingress.enroll(fixture.session, ignored -> { });
        assertEquals(MALFORMED, ingress.receive(fixture.a, new byte[RollbackInputPacket.MAXIMUM_BYTES + 1]).dispatch());
        assertEquals(DELIVERED, ingress.receive(fixture.a, packet(fixture.session.id(), 1, 1)).dispatch());
        fixture.session.advance();
        fixture.session.reconcile();
        assertEquals(THROTTLED, ingress.receive(fixture.a, packet(fixture.session.id(), 1, 1)).dispatch());
        assertEquals(DELIVERED, ingress.receive(fixture.b, packet(fixture.session.id(), 1, 0)).dispatch());
        ingress.beginTick();
        assertEquals(RollbackSession.Status.DUPLICATE, ingress.receive(fixture.a, packet(fixture.session.id(), 1, 1)).receipt().status());
        ingress.shutdown();
    }

    @Test void disconnectStopsWholeRosterOnceAndStaleReleaseCannotOpenTheNextSession() {
        var fixture = new Fixture(A, B);
        var ingress = new RollbackIngress(20);
        var stopped = new ArrayList<RollbackIngress.StopReason>();
        var registration = ingress.enroll(fixture.session, value -> stopped.add(value.stopReason()));
        ingress.validateConnections(peer -> peer.player().equals(A));
        ingress.validateConnections(peer -> false);
        assertEquals(List.of(RollbackIngress.StopReason.CONNECTION_CHANGED), stopped);
        assertTrue(fixture.session.closed());
        assertTrue(ingress.blocksLegacy(A)); assertTrue(ingress.blocksLegacy(B));
        var next = new Fixture(A, B);
        assertThrows(IllegalStateException.class, () -> ingress.enroll(next.session, ignored -> { }));
        registration.finishStop();
        ingress.enroll(next.session, ignored -> { });
        registration.finishStop();
        assertTrue(ingress.blocksLegacy(A)); assertTrue(ingress.blocksLegacy(B));
        assertEquals(UNENROLLED, ingress.receive(fixture.a, packet(fixture.session.id(), 1, 1)).dispatch());
        assertEquals(RollbackSession.Status.STALE_SESSION, ingress.receive(next.a, packet(fixture.session.id(), 1, 1)).receipt().status());
        ingress.shutdown();
    }

    @Test void overlapAndCallbackFailureCannotPartiallyEnrollOrReleaseEitherDuel() {
        var first = new Fixture(A, B);
        var second = new Fixture(C, D);
        var ingress = new RollbackIngress(20);
        var stopped = new ArrayList<UUID>();
        ingress.enroll(first.session, value -> { stopped.add(value.sessionId()); throw new IllegalStateException("Teardown failed"); });
        var overlap = new Fixture(B, C);
        assertThrows(IllegalStateException.class, () -> ingress.enroll(overlap.session, ignored -> { }));
        assertFalse(ingress.blocksLegacy(C));
        ingress.enroll(second.session, value -> stopped.add(value.sessionId()));
        assertThrows(IllegalStateException.class, () -> ingress.stopPlayer(B, RollbackIngress.StopReason.CLIENT_RESET));
        assertTrue(ingress.blocksLegacy(A)); assertTrue(ingress.blocksLegacy(B));
        assertFalse(second.session.closed());
        ingress.shutdown();
        assertEquals(List.of(first.session.id(), second.session.id()), stopped);
        for (UUID id : List.of(A, B, C, D)) assertFalse(ingress.blocksLegacy(id));
        assertThrows(IllegalStateException.class, () -> ingress.enroll(new Fixture(A, B).session, ignored -> { }));
    }

    @Test void externalSessionClosureAndThreadMisuseCannotLeaveAnActiveRoute() {
        var fixture = new Fixture(A, B);
        var ingress = new RollbackIngress(20);
        var stopped = new ArrayList<RollbackIngress.StopReason>();
        var registration = ingress.enroll(fixture.session, value -> stopped.add(value.stopReason()));
        assertTrue(CompletableFuture.supplyAsync(() -> ingress.blocksLegacy(A) && ingress.blocksLegacy(B)).join());
        var crossed = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(ingress::beginTick).join());
        assertInstanceOf(IllegalStateException.class, crossed.getCause());
        fixture.session.close();
        ingress.validateConnections(peer -> true);
        assertEquals(List.of(RollbackIngress.StopReason.SESSION_CLOSED), stopped);
        assertEquals(STOPPING, ingress.receive(fixture.a, packet(fixture.session.id(), 1, 1)).dispatch());
        registration.finishStop();
    }

    private static byte[] packet(UUID session, long tick, float forward) {
        return new RollbackInputPacket(session, 100 + tick, new RollbackMovementInput(0, forward, false, 0, 0), false, List.of()).encode();
    }
    private static final class Fixture {
        final Object a = new String("same-value"), b = new Object();
        final RollbackSession<Integer, String> session;
        Fixture(UUID first, UUID second) {
            var simulation = new RollbackSimulation<Integer, RollbackPlayerInput, String>() {
                int position;
                @Override public Integer snapshot() { return position; }
                @Override public void restore(Integer value) { position = value; }
                @Override public RollbackPlayerInput predict(UUID participant, RollbackPlayerInput previous) { return previous.predict(); }
                @Override public void step(long tick, Map<UUID, RollbackPlayerInput> inputs, RollbackStep<String> effects) {
                    for (var input : inputs.values()) position += Math.round(input.movement().forward());
                }
            };
            var engine = new RollbackEngine<>(simulation, Map.of(first, IDLE, second, IDLE), new RollbackEngine.Limits(3, 2, 10, 50_000_000), 1000);
            session = new RollbackSession<>(UUID.randomUUID(), 7, engine, List.of(new RollbackSession.Peer(first, a, 100), new RollbackSession.Peer(second, b, 100)));
        }
    }
}
