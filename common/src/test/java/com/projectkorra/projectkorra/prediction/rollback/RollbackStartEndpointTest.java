package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.projectkorra.projectkorra.prediction.rollback.RollbackStartPacket.AbortReason.*;
import static com.projectkorra.projectkorra.prediction.rollback.RollbackStartPacket.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackStartEndpointTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);
    private static final UUID ID = new UUID(0, 10), CHALLENGE = new UUID(0, 11);
    private static final String HASH = "a".repeat(64);
    private static final RollbackStartNegotiation.Limits LIMITS = new RollbackStartNegotiation.Limits(80, 20, 2);

    @Test void snapshotImportReceiptsFeedTheRealClockBarrierAndStartTheSession() {
        var f = new Fixture(); byte[] data = new byte[RollbackBootstrapPacket.DATA_BYTES * 2 + 17];
        var offer = new RollbackBootstrapPacket.Offer(ID, CHALLENGE, RollbackStartNegotiation.VERSION, HASH,
                RollbackBootstrapData.fingerprint(data), data.length);
        var assembling = new IdentityHashMap<Object, RollbackBootstrapPacket.Assembler>();
        var receipts = new ArrayDeque<Map.Entry<Object, byte[]>>();
        var bootstraps = new RollbackBootstrapServerEndpoint(new RollbackBootstrapServerEndpoint.Transport() {
            @Override public boolean current(RollbackBootstrapServerEndpoint.Peer peer) { return f.connected.contains(peer.connection()); }
            @Override public void send(RollbackBootstrapServerEndpoint.Peer peer, RollbackBootstrapPacket.Message source) {
                var message = RollbackBootstrapPacket.decode(RollbackBootstrapPacket.encode(source, SERVER_TO_CLIENT), SERVER_TO_CLIENT);
                if (message instanceof RollbackBootstrapPacket.Offer header) assembling.put(peer.connection(), new RollbackBootstrapPacket.Assembler(header, f.tick, 30, data.length));
                if (message instanceof RollbackBootstrapPacket.Part part) {
                    byte[] complete = assembling.get(peer.connection()).receive(part, f.tick);
                    if (complete != null) {
                        assertArrayEquals(data, complete);
                        (peer.connection() == f.a.connection ? f.a : f.b).prepare();
                        receipts.add(Map.entry(peer.connection(), RollbackBootstrapPacket.encode(new RollbackBootstrapPacket.Ready(ID, CHALLENGE, offer.fingerprint()), CLIENT_TO_SERVER)));
                    }
                }
            }
        });
        var transfer = bootstraps.begin(offer, data, List.of(new RollbackBootstrapServerEndpoint.Peer(A, f.a.connection),
                new RollbackBootstrapServerEndpoint.Peer(B, f.b.connection)), f.tick, new RollbackBootstrapServerEndpoint.Limits(30, 3), () -> true);
        for (int n = 0; !transfer.poll(f.tick); n++) {
            assertTrue(n < 10);
            while (!receipts.isEmpty()) { var receipt = receipts.remove(); bootstraps.receive(receipt.getKey(), receipt.getValue(), f.tick); }
            f.until(f.tick + 1);
        }
        assertTrue(f.runtime.starts.isEmpty()); assertTrue(f.a.runtime.starts.isEmpty()); assertTrue(f.b.runtime.starts.isEmpty());
        var prepared = transfer.handoff(f.tick);
        f.registration = f.server.begin(ID, CHALLENGE, offer.fingerprint(), prepared, f.tick, LIMITS, f.ready::get, f.runtime);
        f.until(140);
        assertEquals(1, f.runtime.starts.size()); assertEquals(1, f.a.runtime.starts.size()); assertEquals(1, f.b.runtime.starts.size());
        assertFalse(f.runtime.session.closed()); assertTrue(f.runtime.session.reconcile().head().tick() > 0);
        f.registration.close(STATE_CHANGED); transfer.close(STATE_CHANGED); f.until(145);
        assertTrue(f.runtime.session.closed()); assertFalse(f.a.endpoint.ownsSession()); assertFalse(f.b.endpoint.ownsSession());
    }

    @Test void queuedWireTrafficStartsUnequalLatencyPeersOnceAndTicksTheRealSession() {
        var f = new Fixture();
        f.begin(); f.until(126);
        assertEquals(List.of(122L), f.runtime.starts);
        assertEquals(List.of(1022L), f.a.runtime.starts);
        assertEquals(List.of(5022L), f.b.runtime.starts);
        assertEquals(List.of(123L, 124L, 125L, 126L), f.runtime.ticks);
        assertEquals(List.of(1023L, 1024L, 1025L, 1026L), f.a.runtime.ticks);
        assertEquals(List.of(5023L, 5024L, 5025L, 5026L), f.b.runtime.ticks);
        assertEquals(List.of(1022L, 5022L), f.runtime.session.peers().stream().map(RollbackSession.Peer::clientTickAtStart).toList());
        f.server.tick(126); f.a.endpoint.tick(1026); f.b.endpoint.tick(5026);
        assertEquals(4, f.runtime.ticks.size()); assertEquals(4, f.a.runtime.ticks.size());
        assertEquals(4, f.runtime.session.reconcile().head().tick());
        assertTrue(f.server.owns(A)); assertTrue(f.a.endpoint.ownsSession());
        f.registration.close(STATE_CHANGED); f.until(130);
        assertTrue(f.runtime.session.closed());
        assertEquals(1, f.runtime.stops.size()); assertEquals(1, f.a.runtime.stops.size()); assertEquals(1, f.b.runtime.stops.size());
        assertFalse(f.server.owns(A)); assertFalse(f.a.endpoint.ownsSession()); assertFalse(f.b.endpoint.ownsSession());
    }

    @Test void missedCommitCancelsTheServerEvenAfterItsRuntimeStarted() {
        var f = new Fixture(); f.dropSecondCommit = true;
        f.begin(); f.until(122);
        assertEquals(List.of(122L), f.runtime.starts);
        assertEquals(List.of(1022L), f.a.runtime.starts);
        assertTrue(f.b.runtime.starts.isEmpty()); assertEquals(1, f.b.runtime.stops.size());
        assertFalse(f.runtime.session.closed()); // The cancellation is still in transit.
        f.until(126);
        assertTrue(f.runtime.session.closed());
        assertEquals(List.of(123L, 124L), f.runtime.ticks);
        assertEquals(1, f.runtime.stops.size()); assertEquals(1, f.a.runtime.stops.size());
        assertFalse(f.server.owns(A)); assertFalse(f.a.endpoint.ownsSession());
    }

    @Test void readinessChangesAndReplacedConnectionsStopAllPeersAfterHandoff() {
        for (boolean disconnect : List.of(false, true)) {
            var f = new Fixture(); f.begin(); f.until(124);
            if (disconnect) f.connected.remove(f.b.connection); else f.ready.set(false);
            f.until(128);
            assertTrue(f.runtime.session.closed());
            assertEquals(disconnect ? DISCONNECTED : STATE_CHANGED, f.runtime.stops.getFirst().reason());
            assertFalse(f.a.endpoint.ownsSession()); assertFalse(f.server.owns(B));
            if (!disconnect) assertFalse(f.b.endpoint.ownsSession());
            else f.b.endpoint.stop(DISCONNECTED); // Local loader observes its own disconnect.
        }
    }

    @Test void unknownMalformedAndStaleTrafficCannotCancelOrReplaceAReservedRoster() {
        var f = new Fixture(); f.begin();
        byte[] cancel = RollbackStartPacket.encode(new RollbackStartPacket.Abort(ID, CHALLENGE, OPT_OUT), CLIENT_TO_SERVER);
        f.server.receive(new Object(), cancel, 100);
        f.server.receive(f.a.connection, new byte[] { 1, 2 }, 100);
        f.server.receive(f.a.connection, RollbackStartPacket.encode(
                new RollbackStartPacket.Abort(ID, UUID.randomUUID(), OPT_OUT), CLIENT_TO_SERVER), 100);
        assertThrows(IllegalStateException.class, () -> f.server.begin(UUID.randomUUID(), CHALLENGE, HASH, f.peers(), 100,
                LIMITS, () -> true, new ServerRuntime()));
        f.until(124);
        assertTrue(f.runtime.stops.isEmpty()); assertEquals(1, f.runtime.starts.size());
        assertThrows(IllegalStateException.class, f.registration::finishStop);
        f.server.receive(f.b.connection, cancel, 124); f.until(128);
        assertTrue(f.runtime.session.closed()); assertEquals(OPT_OUT, f.runtime.stops.getFirst().reason());
    }

    @Test void partialProbeDeliveryAndRuntimeStartFailureReleaseOnlyAfterCleanup() {
        var sending = new Fixture(); sending.failSecondProbe = true;
        assertThrows(IllegalStateException.class, sending::begin);
        sending.until(104);
        assertTrue(sending.runtime.starts.isEmpty()); assertEquals(1, sending.runtime.stops.size());
        assertFalse(sending.server.owns(A)); assertFalse(sending.a.endpoint.ownsSession());
        assertFalse(sending.b.endpoint.ownsSession());
        assertNotNull(sending.runtime.stops.getFirst().cause());

        var starting = new Fixture(); starting.runtime.failStart = true;
        starting.begin(); starting.until(126);
        assertTrue(starting.runtime.session.closed()); assertTrue(starting.runtime.ticks.isEmpty());
        assertEquals(1, starting.runtime.stops.size()); assertFalse(starting.server.owns(A));
        assertFalse(starting.a.endpoint.ownsSession()); assertFalse(starting.b.endpoint.ownsSession());
    }

    @Test void clientRuntimeFailureSendsCancellationAndUnpreparedClientsNeverStart() {
        var f = new Fixture(); f.a.runtime.failTick = true;
        f.begin(); f.until(128);
        assertTrue(f.runtime.session.closed()); assertEquals(1, f.a.runtime.stops.size());
        assertEquals(1, f.b.runtime.stops.size()); assertFalse(f.a.endpoint.ownsSession());
        var replies = new ArrayList<RollbackStartPacket.Message>();
        var unprepared = new RollbackStartClientEndpoint(replies::add);
        unprepared.receive(new RollbackStartNegotiation.Probe(ID, CHALLENGE), 1);
        assertEquals(List.of(new RollbackStartPacket.Abort(ID, CHALLENGE, INCOMPATIBLE)), replies);
        assertFalse(unprepared.ownsSession());
    }

    @Test void startTimeoutDoesNotLeaveAClientOrRosterReserved() {
        var f = new Fixture(); f.begin(); f.queue.clear(); // No probes reach either prepared client.
        f.until(182);
        assertTrue(f.runtime.starts.isEmpty()); assertTrue(f.a.runtime.starts.isEmpty());
        assertEquals(1, f.runtime.stops.size()); assertEquals(1, f.a.runtime.stops.size()); assertEquals(1, f.b.runtime.stops.size());
        assertFalse(f.server.owns(A)); assertFalse(f.a.endpoint.ownsSession());
    }

    @Test void cleanupFailureKeepsClientOwnershipUntilExplicitRepairAndCannotRunAgain() {
        var f = new Fixture(); f.begin(); f.until(123);
        f.a.runtime.failStop = true;
        assertThrows(IllegalStateException.class, () -> f.a.endpoint.stop(OPT_OUT));
        assertTrue(f.a.endpoint.ownsSession());
        assertThrows(IllegalStateException.class, () -> f.a.endpoint.prepare(UUID.randomUUID(), 1023, 80, () -> true, new ClientRuntime()));
        f.until(128);
        assertEquals(1, f.a.runtime.stops.size()); assertTrue(f.runtime.session.closed());
        assertEquals(List.of(1023L), f.a.runtime.ticks);
        f.a.endpoint.finishStop(UUID.randomUUID()); assertTrue(f.a.endpoint.ownsSession());
        f.a.endpoint.finishStop(ID); assertFalse(f.a.endpoint.ownsSession());
    }

    @Test void authorityWaitsForNegotiatedStartAndCannotReachAStoppedRuntime() {
        var f = new Fixture(); f.begin(); f.until(121);
        var first = authority(ID, 1);
        f.a.endpoint.authority(authority(UUID.randomUUID(), 1));
        f.a.endpoint.authority(first);
        assertTrue(f.a.runtime.starts.isEmpty()); assertTrue(f.a.runtime.authority.isEmpty());
        f.until(122);
        assertEquals(List.of(first), f.a.runtime.authority);
        var second = authority(ID, 2);
        f.a.endpoint.authority(second);
        assertEquals(List.of(first, second), f.a.runtime.authority);
        f.a.runtime.failStop = true;
        assertThrows(IllegalStateException.class, () -> f.a.endpoint.stop(OPT_OUT));
        assertTrue(f.a.endpoint.ownsSession()); assertFalse(f.a.endpoint.acceptsAuthority());
        f.a.endpoint.authority(authority(ID, 3));
        assertEquals(2, f.a.runtime.authority.size());
        f.until(126); assertTrue(f.runtime.session.closed());
        f.a.endpoint.finishStop(ID);
    }

    @Test void rejectedAuthorityCancelsTheWholeSessionBeforeOrAfterClientStart() {
        for (boolean beforeStart : List.of(false, true)) {
            var f = new Fixture(); f.begin(); f.until(beforeStart ? 121 : 123);
            f.a.runtime.failAuthority = true;
            f.a.endpoint.authority(authority(ID, 1));
            f.until(128);
            assertTrue(f.runtime.session.closed());
            assertEquals(1, f.a.runtime.stops.size()); assertEquals(1, f.b.runtime.stops.size());
            assertNotNull(f.a.runtime.stops.getFirst().cause());
            assertFalse(f.a.endpoint.ownsSession()); assertFalse(f.server.owns(A));
        }
    }

    @Test void authorityQueueIsBoundedAndClearedWhenStartIsCancelled() {
        var f = new Fixture(); f.begin(); f.until(121);
        for (int publication = 1; publication <= 9; publication++) f.a.endpoint.authority(authority(ID, publication));
        assertFalse(f.a.endpoint.ownsSession());
        f.until(128);
        assertTrue(f.a.runtime.starts.isEmpty()); assertTrue(f.a.runtime.authority.isEmpty());
        assertTrue(f.runtime.starts.isEmpty()); assertEquals(1, f.runtime.stops.size());
        assertEquals(1, f.a.runtime.stops.size());
    }

    private static RollbackAuthorityUpdate authority(UUID session, long publication) {
        var idle = new RollbackPlayerInput(new RollbackMovementInput(0, 0, false, 0, 0), false, List.of());
        return new RollbackAuthorityUpdate(session, publication, 0, 1, 0, 1,
                List.of(Map.of(A, idle, B, idle)), Map.of(A, List.of(), B, List.of()));
    }

    @Test void shutdownContinuesOtherSessionsWhenOneCleanupFailsAndRetainsFailedOwnership() {
        var stopped = new ArrayList<UUID>();
        var server = new RollbackStartServerEndpoint(new RollbackStartServerEndpoint.Transport() {
            @Override public boolean current(RollbackStartNegotiation.PreparedPeer peer) { return true; }
            @Override public void send(RollbackStartNegotiation.PreparedPeer peer, RollbackStartPacket.Message message) { }
        });
        var broken = server.begin(ID, CHALLENGE, HASH, List.of(peer(A, new Object()), peer(B, new Object())), 1, LIMITS, () -> true,
                new ServerRuntime() {
                    @Override public void stop(RollbackStartServerEndpoint.Failure failure) { stopped.add(A); throw new IllegalStateException("cleanup failed"); }
                });
        UUID c = new UUID(0, 3), d = new UUID(0, 4);
        server.begin(new UUID(0, 20), CHALLENGE, HASH, List.of(peer(c, new Object()), peer(d, new Object())), 1, LIMITS, () -> true,
                new ServerRuntime() {
                    @Override public void stop(RollbackStartServerEndpoint.Failure failure) { stopped.add(c); }
                });
        assertThrows(IllegalStateException.class, server::shutdown);
        assertEquals(List.of(A, c), stopped); assertTrue(server.owns(A)); assertFalse(server.owns(c));
        server.tick(2); server.shutdown(); assertEquals(2, stopped.size());
        broken.finishStop(); assertFalse(server.owns(A));
    }

    private static RollbackStartNegotiation.PreparedPeer peer(UUID player, Object connection) {
        return new RollbackStartNegotiation.PreparedPeer(player, connection, RollbackStartNegotiation.VERSION, true, HASH);
    }

    private record Delivery(long at, long order, Runnable accept) { }
    private static final class Fixture {
        long tick = 100, order;
        boolean dropSecondCommit, failSecondProbe;
        final AtomicBoolean ready = new AtomicBoolean(true);
        final Set<Object> connected = Collections.newSetFromMap(new IdentityHashMap<>());
        final PriorityQueue<Delivery> queue = new PriorityQueue<>(Comparator.comparingLong(Delivery::at).thenComparingLong(Delivery::order));
        final ServerRuntime runtime = new ServerRuntime();
        final Client a = new Client(A, 1, 900), b = new Client(B, 3, 4900);
        final RollbackStartServerEndpoint server = new RollbackStartServerEndpoint(new RollbackStartServerEndpoint.Transport() {
            @Override public boolean current(RollbackStartNegotiation.PreparedPeer peer) { return connected.contains(peer.connection()); }
            @Override public void send(RollbackStartNegotiation.PreparedPeer peer, RollbackStartPacket.Message message) {
                Client client = peer.player().equals(A) ? a : b;
                if (client == b && failSecondProbe && message instanceof RollbackStartNegotiation.Probe) throw new IllegalStateException("send failed");
                if (client == b && dropSecondCommit && message instanceof RollbackStartPacket.Commit) return;
                byte[] bytes = RollbackStartPacket.encode(message, SERVER_TO_CLIENT);
                queue.add(new Delivery(tick + client.delay, order++, () -> client.endpoint.receive(
                        RollbackStartPacket.decode(bytes, SERVER_TO_CLIENT), tick + client.offset)));
            }
        });
        RollbackStartServerEndpoint.Registration registration;
        Fixture() { connected.add(a.connection); connected.add(b.connection); }
        List<RollbackStartNegotiation.PreparedPeer> peers() { return List.of(peer(A, a.connection), peer(B, b.connection)); }
        void begin() {
            a.prepare(); b.prepare();
            registration = server.begin(ID, CHALLENGE, HASH, peers(), tick, LIMITS, ready::get, runtime);
        }
        void until(long target) {
            while (tick < target) {
                tick++;
                while (!queue.isEmpty() && queue.peek().at <= tick) queue.remove().accept.run();
                server.tick(tick); a.endpoint.tick(tick + a.offset); b.endpoint.tick(tick + b.offset);
            }
        }
        final class Client {
            final UUID player;
            final int delay, offset;
            final Object connection = new Object();
            final ClientRuntime runtime = new ClientRuntime();
            final RollbackStartClientEndpoint endpoint;
            Client(UUID player, int delay, int offset) {
                this.player = player; this.delay = delay; this.offset = offset;
                endpoint = new RollbackStartClientEndpoint(message -> {
                    byte[] bytes = RollbackStartPacket.encode(message, CLIENT_TO_SERVER);
                    queue.add(new Delivery(tick + delay, order++, () -> server.receive(connection, bytes, tick)));
                });
            }
            void prepare() { endpoint.prepare(ID, tick + offset, 80, () -> true, runtime); }
        }
    }

    private static class ServerRuntime implements RollbackStartServerEndpoint.Runtime {
        final List<Long> starts = new ArrayList<>(), ticks = new ArrayList<>();
        final List<RollbackStartServerEndpoint.Failure> stops = new ArrayList<>();
        RollbackSession<Integer, String> session;
        boolean failStart;
        @Override public void start(RollbackStartNegotiation negotiation, long tick) {
            starts.add(tick); session = negotiation.start(tick, 42, timeline());
            if (failStart) throw new IllegalStateException("runtime start failed after creating session");
        }
        @Override public void tick(long tick) { ticks.add(tick); session.advance(); }
        @Override public void stop(RollbackStartServerEndpoint.Failure failure) { stops.add(failure); }
    }
    private static final class ClientRuntime implements RollbackStartClientEndpoint.Runtime {
        final List<Long> starts = new ArrayList<>(), ticks = new ArrayList<>();
        final List<RollbackStartServerEndpoint.Failure> stops = new ArrayList<>();
        boolean failTick, failStop, failAuthority;
        final List<RollbackAuthorityUpdate> authority = new ArrayList<>();
        @Override public void start(long tick) { starts.add(tick); }
        @Override public void tick(long tick) { ticks.add(tick); if (failTick) throw new IllegalStateException("client tick failed"); }
        @Override public void authority(RollbackAuthorityUpdate update) {
            assertFalse(starts.isEmpty(), "Authority cannot mutate a runtime before its negotiated start");
            if (failAuthority) throw new IllegalStateException("authority correction failed");
            authority.add(update);
        }
        @Override public void stop(RollbackStartServerEndpoint.Failure failure) {
            stops.add(failure); if (failStop) throw new IllegalStateException("client cleanup failed");
        }
    }
    private static RollbackTimeline<Integer, RollbackPlayerInput, String> timeline() {
        var idle = new RollbackPlayerInput(new RollbackMovementInput(0, 0, false, 0, 0), false, List.of());
        return new RollbackEngine<>(new RollbackSimulation<Integer, RollbackPlayerInput, String>() {
            int x;
            @Override public Integer snapshot() { return x; }
            @Override public void restore(Integer value) { x = value; }
            @Override public RollbackPlayerInput predict(UUID player, RollbackPlayerInput previous) { return previous.predict(); }
            @Override public void step(long tick, Map<UUID, RollbackPlayerInput> inputs, RollbackStep<String> effects) { x++; }
        }, Map.of(A, idle, B, idle), new RollbackEngine.Limits(3, 1, 10, 50_000_000), 1000);
    }
}
