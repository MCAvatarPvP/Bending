package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.projectkorra.projectkorra.prediction.rollback.RollbackStartPacket.Direction.*;
import static com.projectkorra.projectkorra.prediction.rollback.RollbackStartPacket.AbortReason.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackBootstrapTransportTest {
    private static final UUID ID = new UUID(0, 1), CHALLENGE = new UUID(0, 2), A = new UUID(0, 3), B = new UUID(0, 4);
    private static final byte[] DATA = new byte[RollbackBootstrapPacket.DATA_BYTES * 2 + 17];
    private static RollbackBootstrapPacket.Offer offer() {
        return new RollbackBootstrapPacket.Offer(ID, CHALLENGE, RollbackStartNegotiation.VERSION, "ab".repeat(32), RollbackBootstrapData.fingerprint(DATA), DATA.length);
    }
    private static RollbackBootstrapPacket.Part part(int index) {
        return new RollbackBootstrapPacket.Part(ID, CHALLENGE, index, Arrays.copyOfRange(DATA, index * RollbackBootstrapPacket.DATA_BYTES,
                index * RollbackBootstrapPacket.DATA_BYTES + offer().partSize(index)));
    }

    @Test void directionCheckedPacketsRoundTripInsidePluginMessageLimits() {
        for (var message : List.<RollbackBootstrapPacket.Message>of(offer(), part(0), part(2), new RollbackBootstrapPacket.Cancel(ID, CHALLENGE, TIMEOUT))) {
            var bytes = RollbackBootstrapPacket.encode(message, SERVER_TO_CLIENT);
            assertTrue(bytes.length < 32_766); assertEquals(message, RollbackBootstrapPacket.decode(bytes, SERVER_TO_CLIENT));
            if (!(message instanceof RollbackBootstrapPacket.Cancel)) assertThrows(IllegalArgumentException.class, () -> RollbackBootstrapPacket.encode(message, CLIENT_TO_SERVER));
        }
        var receipt = new RollbackBootstrapPacket.Ready(ID, CHALLENGE, offer().fingerprint());
        assertEquals(receipt, RollbackBootstrapPacket.decode(RollbackBootstrapPacket.encode(receipt, CLIENT_TO_SERVER), CLIENT_TO_SERVER));
        assertThrows(IllegalArgumentException.class, () -> RollbackBootstrapPacket.encode(receipt, SERVER_TO_CLIENT));
        for (var message : List.<RollbackBootstrapPacket.Message>of(offer(), new RollbackBootstrapPacket.Cancel(ID, CHALLENGE, OPT_OUT))) {
            var bytes = RollbackBootstrapPacket.encode(message, SERVER_TO_CLIENT);
            for (int i = 0; i < bytes.length; i++) {
                byte[] cut = Arrays.copyOf(bytes, i);
                assertThrows(IllegalArgumentException.class, () -> RollbackBootstrapPacket.decode(cut, SERVER_TO_CLIENT));
            }
            assertThrows(IllegalArgumentException.class, () -> RollbackBootstrapPacket.decode(Arrays.copyOf(bytes, bytes.length + 1), SERVER_TO_CLIENT));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackBootstrapPacket.decode(new byte[70], CLIENT_TO_SERVER));
    }

    @Test void reorderedAndDuplicateChunksProduceOneVerifiedSnapshotWhileStaleSessionsCannotContribute() {
        var assembly = new RollbackBootstrapPacket.Assembler(offer(), 10, 20, DATA.length);
        assertNull(assembly.receive(part(2), 11)); assertNull(assembly.receive(part(2), 11));
        assertNull(assembly.receive(new RollbackBootstrapPacket.Part(ID, new UUID(0, 9), 0, part(0).data()), 11));
        assertNull(assembly.receive(part(0), 12));
        assertArrayEquals(DATA, assembly.receive(part(1), 13));
        assertNull(assembly.receive(part(0), 14)); assembly.poll(100);
    }

    @Test void rewritesBadLengthsWrongFingerprintsTimeoutsAndLocalBudgetsRejectAssembly() {
        assertThrows(IllegalArgumentException.class, () -> new RollbackBootstrapPacket.Assembler(offer(), 10, 20, DATA.length - 1));
        var rewritten = new RollbackBootstrapPacket.Assembler(offer(), 10, 20, DATA.length);
        rewritten.receive(part(0), 10); byte[] changed = part(0).data(); changed[0] = 1;
        assertThrows(IllegalArgumentException.class, () -> rewritten.receive(new RollbackBootstrapPacket.Part(ID, CHALLENGE, 0, changed), 11));
        assertThrows(IllegalStateException.class, () -> rewritten.receive(part(1), 12));
        var shortPart = new RollbackBootstrapPacket.Assembler(offer(), 10, 20, DATA.length);
        assertThrows(IllegalArgumentException.class, () -> shortPart.receive(new RollbackBootstrapPacket.Part(ID, CHALLENGE, 0, new byte[2]), 10));
        var wrongHash = new RollbackBootstrapPacket.Assembler(new RollbackBootstrapPacket.Offer(ID, CHALLENGE, 3, offer().definitions(), "00".repeat(32), DATA.length), 10, 20, DATA.length);
        wrongHash.receive(part(0), 10); wrongHash.receive(part(1), 10);
        assertThrows(IllegalArgumentException.class, () -> wrongHash.receive(part(2), 10));
        var expired = new RollbackBootstrapPacket.Assembler(offer(), 10, 20, DATA.length);
        assertThrows(IllegalStateException.class, () -> expired.poll(31));
    }

    @Test void transferIsBoundedFairAndCannotHandoffBeforeBothAuthenticatedImportReceipts() {
        var f = new Fixture(); var transfer = f.begin();
        assertTrue(f.sent.isEmpty()); assertFalse(transfer.poll(10)); assertEquals(2, f.sent.size());
        assertFalse(transfer.poll(10)); assertEquals(2, f.sent.size());
        assertInstanceOf(RollbackBootstrapPacket.Offer.class, f.sent.get(0).message());
        assertNotSame(f.sent.get(0).peer().connection(), f.sent.get(1).peer().connection());
        for (int tick = 11; tick <= 13; tick++) transfer.poll(tick);
        assertThrows(IllegalStateException.class, () -> transfer.handoff(13));
        f.endpoint.receive(new Object(), f.ready(), 13); assertFalse(transfer.poll(13));
        f.endpoint.receive(f.a, f.ready(), 13); assertFalse(transfer.poll(13));
        f.endpoint.receive(f.a, f.ready(), 13); assertFalse(transfer.poll(13));
        f.endpoint.receive(f.b, f.ready(), 13); assertTrue(transfer.poll(13));
        var prepared = transfer.handoff(13); assertEquals(List.of(A, B), prepared.stream().map(RollbackStartNegotiation.PreparedPeer::player).toList());
        assertSame(f.a, prepared.get(0).connection()); assertTrue(prepared.stream().allMatch(p -> p.contentHash().equals(offer().fingerprint())));
        assertThrows(IllegalStateException.class, () -> transfer.handoff(13));
        transfer.close(STATE_CHANGED); transfer.close(STATE_CHANGED);
        assertEquals(2, f.sent.stream().filter(d -> d.message() instanceof RollbackBootstrapPacket.Cancel).count());
    }

    @Test void earlyOrDifferentReceiptsAndChangedConnectionsPreventWholeDuelHandoff() {
        var early = new Fixture(); var first = early.begin(); early.endpoint.receive(early.a, early.ready(), 10);
        assertThrows(IllegalStateException.class, () -> first.poll(10)); first.close(INCOMPATIBLE);
        var wrong = new Fixture(); var second = wrong.begin();
        for (int tick = 10; tick <= 13; tick++) second.poll(tick);
        wrong.endpoint.receive(wrong.a, RollbackBootstrapPacket.encode(new RollbackBootstrapPacket.Ready(ID, CHALLENGE, "00".repeat(32)), CLIENT_TO_SERVER), 13);
        assertThrows(IllegalStateException.class, () -> second.handoff(13)); second.close(INCOMPATIBLE);
        var changed = new Fixture(); var third = changed.begin(); third.poll(10); changed.connected.remove(changed.b);
        assertThrows(IllegalStateException.class, () -> third.poll(11)); third.close(DISCONNECTED);
        assertEquals(1, changed.sent.stream().filter(d -> d.message() instanceof RollbackBootstrapPacket.Cancel).count());
    }

    @Test void timeoutOptOutPartialDeliveryAndMalformedRepliesKeepCleanupAvailable() {
        var expired = new Fixture(); var first = expired.begin(); assertThrows(IllegalStateException.class, () -> first.poll(31)); first.close(TIMEOUT);
        var optedOut = new Fixture(); var second = optedOut.begin(); second.poll(10); optedOut.ready.set(false);
        assertThrows(IllegalStateException.class, () -> second.poll(11)); second.close(OPT_OUT);
        var partial = new Fixture(); var third = partial.begin(); partial.failSend = true;
        assertThrows(IllegalStateException.class, () -> third.poll(10)); partial.failSend = false; third.close(SERVER_FAILURE);
        assertEquals(2, partial.sent.stream().filter(d -> d.message() instanceof RollbackBootstrapPacket.Cancel).count());
        var malformed = new Fixture(); var fourth = malformed.begin(); malformed.endpoint.receive(malformed.a, new byte[]{1}, 10);
        assertThrows(IllegalStateException.class, () -> fourth.poll(10)); fourth.close(INCOMPATIBLE);
        assertNotNull(malformed.begin()); // A failed transfer released its reservations only through cleanup.
    }

    private record Delivery(RollbackBootstrapServerEndpoint.Peer peer, RollbackBootstrapPacket.Message message) { }
    private static final class Fixture {
        final Object a = new Object(), b = new Object();
        final Set<Object> connected = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Delivery> sent = new ArrayList<>();
        final AtomicBoolean ready = new AtomicBoolean(true);
        boolean failSend;
        final RollbackBootstrapServerEndpoint endpoint = new RollbackBootstrapServerEndpoint(new RollbackBootstrapServerEndpoint.Transport() {
            @Override public boolean current(RollbackBootstrapServerEndpoint.Peer peer) { return connected.contains(peer.connection()); }
            @Override public void send(RollbackBootstrapServerEndpoint.Peer peer, RollbackBootstrapPacket.Message message) {
                if (failSend && peer.connection() == b) throw new IllegalStateException("Send failed");
                sent.add(new Delivery(peer, RollbackBootstrapPacket.decode(RollbackBootstrapPacket.encode(message, SERVER_TO_CLIENT), SERVER_TO_CLIENT)));
            }
        });
        Fixture() { connected.add(a); connected.add(b); }
        RollbackBootstrapServerEndpoint.Registration begin() {
            return endpoint.begin(offer(), DATA, List.of(new RollbackBootstrapServerEndpoint.Peer(A, a), new RollbackBootstrapServerEndpoint.Peer(B, b)),
                    10, new RollbackBootstrapServerEndpoint.Limits(20, 2), ready::get);
        }
        byte[] ready() { return RollbackBootstrapPacket.encode(new RollbackBootstrapPacket.Ready(ID, CHALLENGE, offer().fingerprint()), CLIENT_TO_SERVER); }
    }
}
