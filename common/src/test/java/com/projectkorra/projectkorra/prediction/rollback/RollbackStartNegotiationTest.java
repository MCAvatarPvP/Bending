package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static com.projectkorra.projectkorra.prediction.rollback.RollbackStartNegotiation.*;

class RollbackStartNegotiationTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), ID = new UUID(0, 10), CHALLENGE = new UUID(0, 11);
    private static final String HASH = "a".repeat(64);
    private static final Limits LIMITS = new Limits(80, 20, 2);

    @Test void unequalRoundTripsAgreeOnOneServerChosenTickAndFeedTheExistingInputSession() {
        var fixture = new Fixture();
        var first = new RollbackClientStart(ID, 1000, 80, () -> true);
        var second = new RollbackClientStart(ID, 5000, 80, () -> true);
        Probe probe = fixture.server.probe();
        ClockReply a = first.probe(probe, 1001); // server tick 101, offset +900
        ClockReply b = second.probe(probe, 5003); // server tick 103, offset +4900
        assertEquals(Reply.ACCEPTED, fixture.server.receive(fixture.a, a, 102));
        assertEquals(Reply.ACCEPTED, fixture.server.receive(fixture.b, b, 106));
        var schedules = fixture.server.schedule(106);
        assertEquals(122, fixture.server.serverStartTick());
        assertEquals(1022, schedules.get(A).clientTick());
        assertEquals(5022, schedules.get(B).clientTick());
        var ackA = first.schedule(schedules.get(A), 1007);
        var ackB = second.schedule(schedules.get(B), 5009);
        assertEquals(Reply.ACCEPTED, fixture.server.receive(fixture.a, ackA, 108));
        assertThrows(IllegalStateException.class, () -> fixture.server.commit(108));
        assertEquals(Reply.ACCEPTED, fixture.server.receive(fixture.b, ackB, 112));
        var committed = fixture.server.commit(112);
        first.commit(committed.get(A), 1013);
        second.commit(committed.get(B), 5015);
        assertFalse(first.start(1021)); assertFalse(second.start(5021));
        var session = fixture.server.start(122, 123, timeline());
        assertTrue(first.start(1022)); assertTrue(second.start(5022));
        assertFalse(first.start(1022));
        assertEquals(List.of(1022L, 5022L), session.peers().stream().map(RollbackSession.Peer::clientTickAtStart).toList());
        var ingress = new RollbackIngress(20);
        ingress.enroll(session, ignored -> { });
        session.advance(); session.advance();
        var lateInput = new RollbackInputPacket(ID, 1023, new RollbackMovementInput(0, 1, false, 0, 0), false, List.of());
        assertEquals(new RollbackSession.Receipt(RollbackSession.Status.ACCEPTED, 1), ingress.receive(fixture.a, lateInput.encode()).receipt());
        assertEquals(1, session.reconcile().replayedFrom());
        assertEquals(2, session.reconcile().head().state());
        assertEquals(Reply.WRONG_PHASE, fixture.server.receive(fixture.a, new ClockReply(ID, CHALLENGE, 9999), 123));
        assertThrows(IllegalStateException.class, () -> fixture.server.start(123, 123, timeline()));
        ingress.shutdown();
    }

    @Test void unsupportedOptedOutMismatchedAndOverlappingPeersCannotBegin() {
        Object a = new Object(), b = new Object();
        for (PreparedPeer rejected : List.of(new PreparedPeer(B, b, VERSION + 1, true, HASH),
                new PreparedPeer(B, b, VERSION - 1, true, HASH),
                new PreparedPeer(B, b, VERSION, false, HASH), new PreparedPeer(B, b, VERSION, true, "b".repeat(64)),
                new PreparedPeer(B, a, VERSION, true, HASH), new PreparedPeer(A, b, VERSION, true, HASH))) {
            assertThrows(IllegalArgumentException.class, () -> new RollbackStartNegotiation(ID, CHALLENGE, HASH,
                    List.of(peer(A, a), rejected), 100, LIMITS, () -> true));
        }
        assertThrows(IllegalStateException.class, () -> new RollbackStartNegotiation(ID, CHALLENGE, HASH,
                List.of(peer(A, a), peer(B, b)), 100, LIMITS, () -> false));
    }

    @Test void staleMessagesCannotChooseAnAnchorAndChangedRepliesAbortWholeAttempt() {
        var fixture = new Fixture();
        assertEquals(Reply.UNKNOWN_CONNECTION, fixture.server.receive(new Object(), new ClockReply(ID, CHALLENGE, 1), 101));
        assertEquals(Reply.STALE, fixture.server.receive(fixture.a, new ClockReply(ID, UUID.randomUUID(), 1), 101));
        assertEquals(Reply.ACCEPTED, fixture.server.receive(fixture.a, new ClockReply(ID, CHALLENGE, 1001), 102));
        assertEquals(Reply.DUPLICATE, fixture.server.receive(fixture.a, new ClockReply(ID, CHALLENGE, 1001), 103));
        assertThrows(IllegalStateException.class, () -> fixture.server.schedule(103));
        assertEquals(Reply.CONFLICT, fixture.server.receive(fixture.a, new ClockReply(ID, CHALLENGE, 1002), 104));
        assertEquals(Phase.ABORTED, fixture.server.phase());
        assertFalse(fixture.server.poll(105));
    }

    @Test void timeoutLostCommitReadinessChangeAndClockOverflowNeverStartOnePeer() {
        var timeout = new Fixture();
        assertFalse(timeout.server.poll(181));
        var changed = new Fixture();
        changed.ready.set(false);
        assertFalse(changed.server.poll(101));
        var overflow = new Fixture();
        overflow.server.receive(overflow.a, new ClockReply(ID, CHALLENGE, Long.MAX_VALUE), 101);
        overflow.server.receive(overflow.b, new ClockReply(ID, CHALLENGE, 5001), 102);
        assertThrows(ArithmeticException.class, () -> overflow.server.schedule(102));
        assertEquals(Phase.ABORTED, overflow.server.phase());
        var late = new Fixture();
        late.server.receive(late.a, new ClockReply(ID, CHALLENGE, 1001), 102);
        late.server.receive(late.b, new ClockReply(ID, CHALLENGE, 5003), 106);
        var schedules = late.server.schedule(106);
        assertEquals(Reply.ABORTED, late.server.receive(late.a, new Scheduled(schedules.get(A), 1015), 115));
        var client = new RollbackClientStart(ID, 1000, 80, () -> true);
        client.probe(new Probe(ID, CHALLENGE), 1001);
        client.schedule(schedules.get(A), 1007);
        assertFalse(client.start(1022)); // No commit received: keep the runtime inactive.
        assertEquals(RollbackClientStart.Phase.ABORTED, client.phase());
    }

    @Test void eachWireMessageRoundTripsAndRejectsWrongDirectionTruncationAndTrailingData() {
        var schedule = new Schedule(ID, CHALLENGE, 122, 1022);
        var server = List.<RollbackStartPacket.Message>of(new Probe(ID, CHALLENGE), schedule, new RollbackStartPacket.Commit(schedule));
        var client = List.<RollbackStartPacket.Message>of(new ClockReply(ID, CHALLENGE, 1001), new Scheduled(schedule, 1007));
        for (var direction : RollbackStartPacket.Direction.values()) {
            var messages = new ArrayList<>(direction == RollbackStartPacket.Direction.SERVER_TO_CLIENT ? server : client);
            messages.add(new RollbackStartPacket.Abort(ID, CHALLENGE, RollbackStartPacket.AbortReason.OPT_OUT));
            for (var message : messages) {
                byte[] bytes = RollbackStartPacket.encode(message, direction);
                assertTrue(bytes.length <= RollbackStartPacket.MAXIMUM_BYTES);
                assertEquals(message, RollbackStartPacket.decode(bytes, direction));
                assertThrows(IllegalArgumentException.class, () -> RollbackStartPacket.decode(Arrays.copyOf(bytes, bytes.length - 1), direction));
                assertThrows(IllegalArgumentException.class, () -> RollbackStartPacket.decode(Arrays.copyOf(bytes, bytes.length + 1), direction));
                if (!(message instanceof RollbackStartPacket.Abort)) {
                    var wrong = direction == RollbackStartPacket.Direction.SERVER_TO_CLIENT ? RollbackStartPacket.Direction.CLIENT_TO_SERVER : RollbackStartPacket.Direction.SERVER_TO_CLIENT;
                    assertThrows(IllegalArgumentException.class, () -> RollbackStartPacket.encode(message, wrong));
                    assertThrows(IllegalArgumentException.class, () -> RollbackStartPacket.decode(bytes, wrong));
                }
            }
        }
    }

    @Test void controlDispatchIgnoresStaleCancellationAndOptOutAbortsTheWholePendingStart() {
        var fixture = new Fixture();
        var client = new RollbackClientStart(ID, 1000, 80, () -> true);
        var response = client.receive(fixture.server.probe(), 1001);
        assertEquals(Reply.ACCEPTED, fixture.server.receive(fixture.a, response, 102));
        var stale = new RollbackStartPacket.Abort(ID, UUID.randomUUID(), RollbackStartPacket.AbortReason.TIMEOUT);
        assertNull(client.receive(stale, 1002));
        assertEquals(RollbackClientStart.Phase.PROBED, client.phase());
        assertEquals(Reply.STALE, fixture.server.receive(fixture.a, stale, 103));
        assertEquals(Phase.PROBING, fixture.server.phase());
        var cancelled = new RollbackStartPacket.Abort(ID, CHALLENGE, RollbackStartPacket.AbortReason.OPT_OUT);
        assertEquals(Reply.UNKNOWN_CONNECTION, fixture.server.receive(new Object(), cancelled, 103));
        assertEquals(Reply.ACCEPTED, fixture.server.receive(fixture.b, cancelled, 104));
        assertEquals(Phase.ABORTED, fixture.server.phase());
        assertNull(client.receive(cancelled, 1004));
        assertEquals(RollbackClientStart.Phase.ABORTED, client.phase());
        assertFalse(client.start(1100));
    }

    private static PreparedPeer peer(UUID id, Object connection) { return new PreparedPeer(id, connection, VERSION, true, HASH); }
    private static final class Fixture {
        final Object a = new Object(), b = new Object();
        final AtomicBoolean ready = new AtomicBoolean(true);
        final RollbackStartNegotiation server = new RollbackStartNegotiation(ID, CHALLENGE, HASH, List.of(peer(A, a), peer(B, b)), 100, LIMITS, ready::get);
    }
    private static RollbackTimeline<Integer, RollbackPlayerInput, String> timeline() {
        var idle = new RollbackPlayerInput(new RollbackMovementInput(0, 0, false, 0, 0), false, List.of());
        return new RollbackEngine<>(new RollbackSimulation<Integer, RollbackPlayerInput, String>() {
            int x;
            @Override public Integer snapshot() { return x; }
            @Override public void restore(Integer value) { x = value; }
            @Override public RollbackPlayerInput predict(UUID player, RollbackPlayerInput previous) { return previous.predict(); }
            @Override public void step(long tick, Map<UUID, RollbackPlayerInput> inputs, RollbackStep<String> effects) { inputs.values().forEach(input -> x += Math.round(input.movement().forward())); }
        }, Map.of(A, idle, B, idle), new RollbackEngine.Limits(3, 1, 10, 50_000_000), 1000);
    }
}
