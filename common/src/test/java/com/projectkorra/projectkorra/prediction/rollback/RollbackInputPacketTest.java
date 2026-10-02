package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackInputPacketTest {
    private static final UUID SESSION = new UUID(1, 2), PLAYER = new UUID(3, 4);
    private static final RollbackMovementInput MOVE = new RollbackMovementInput(1, 0, true, 90, -30);
    private static RollbackInputPacket.Edge edge(long sequence) { return new RollbackInputPacket.Edge(sequence, RollbackInputActions.Kind.SWING, -1, 450, 0); }
    private static RollbackInputPacket packet(List<RollbackInputPacket.Edge> edges) { return new RollbackInputPacket(SESSION, 101, MOVE, true, edges); }

    @Test void sharedCodecRoundTripsAllActionsAndMaximumPayloadAndDetachesTheList() {
        var source = new ArrayList<RollbackInputPacket.Edge>();
        for (var kind : RollbackInputActions.Kind.values()) source.add(new RollbackInputPacket.Edge(source.size() + 1, kind,
                kind == RollbackInputActions.Kind.SLOT_CHANGE ? 8 : -1, -450, 45));
        var packet = packet(source); source.clear();
        assertEquals(RollbackInputActions.Kind.values().length, packet.actions().size());
        assertEquals(packet, RollbackInputPacket.decode(packet.encode()));
        var maximum = packet(java.util.stream.LongStream.rangeClosed(1, RollbackPlayerInput.MAXIMUM_ACTIONS).mapToObj(RollbackInputPacketTest::edge).toList());
        assertEquals(RollbackInputPacket.MAXIMUM_BYTES, maximum.encode().length);
        assertEquals(maximum, RollbackInputPacket.decode(maximum.encode()));
        assertEquals(47, packet(List.of()).encode().length);
        assertThrows(UnsupportedOperationException.class, () -> packet.actions().clear());
    }

    @Test void rejectsTruncationTrailingDataUnknownVersionsAndInvalidWireValues() {
        byte[] valid = packet(List.of(edge(1))).encode();
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertThrows(IllegalArgumentException.class, () -> RollbackInputPacket.decode(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackInputPacket.decode(Arrays.copyOf(valid, valid.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> RollbackInputPacket.decode(new byte[RollbackInputPacket.MAXIMUM_BYTES + 1]));
        for (var mutation : List.of(new int[]{3, 1}, new int[]{3, 2}, new int[]{3, RollbackInputPacket.VERSION + 1}, new int[]{36, 2}, new int[]{45, 2}, new int[]{46, 65}, new int[]{55, 127}, new int[]{56, 8})) {
            byte[] invalid = valid.clone(); invalid[mutation[0]] = (byte) mutation[1];
            assertThrows(IllegalArgumentException.class, () -> RollbackInputPacket.decode(invalid));
        }
        byte[] nan = valid.clone(); ByteBuffer.wrap(nan).putFloat(28, Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> RollbackInputPacket.decode(nan));
        byte[] negativeTime = valid.clone(); ByteBuffer.wrap(negativeTime).putLong(20, -1);
        assertThrows(IllegalArgumentException.class, () -> RollbackInputPacket.decode(negativeTime));
    }

    @Test void serverSeedIsStableAcrossDeliveryOrderAndScopedToSessionAndPlayer() {
        var one = packet(List.of(edge(1))); var two = packet(List.of(edge(2)));
        var expected = one.playerInput(PLAYER, 99); two.playerInput(PLAYER, 99);
        assertEquals(expected, RollbackInputPacket.decode(one.encode()).playerInput(PLAYER, 99));
        long seed = expected.actions().getFirst().action().seed();
        assertTrue(seed > 0);
        assertNotEquals(seed, one.playerInput(new UUID(3, 5), 99).actions().getFirst().action().seed());
        assertNotEquals(seed, one.playerInput(PLAYER, 100).actions().getFirst().action().seed());
        assertNotEquals(seed, new RollbackInputPacket(new UUID(1, 3), 101, MOVE, true, one.actions())
                .playerInput(PLAYER, 99).actions().getFirst().action().seed());
    }
}
