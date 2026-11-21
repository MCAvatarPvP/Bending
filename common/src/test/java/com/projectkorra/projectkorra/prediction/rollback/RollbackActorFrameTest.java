package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.Pose;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RollbackActorFrameTest {
    private static final UUID FIRST = new UUID(1, 2), SECOND = new UUID(3, 4);
    private static final Pose POSE = new Pose(1, 2, 3, 4, 5);
    private static final RollbackRandom.State RNG = new RollbackRandom.State(123, false, 0);
    private static RollbackActorFrame frame(Map<UUID, Integer> victims) {
        return new RollbackActorFrame(72, POSE, true, false, false, false, true, RNG, new byte[]{12}, victims);
    }

    @Test void emptyHistoryKeepsTheLegacyFormat() {
        byte[] bytes = new RollbackActorFrame(72, POSE, true, false, false, false, true, RNG, new byte[]{12}).encode();
        assertEquals(1, ByteBuffer.wrap(bytes).getInt());
        assertArrayEquals(frame(Map.of()).encode(), bytes);
        assertArrayEquals(bytes, RollbackActorFrame.decode(bytes).encode());
        assertTrue(RollbackActorFrame.decode(bytes).cloudVictims().isEmpty());
    }

    @Test void cloudDeadlinesAreDetachedAndEncodeInStableOrder() {
        var input = new LinkedHashMap<UUID, Integer>();
        input.put(SECOND, 83);
        input.put(FIRST, 70);
        var frame = frame(input);
        input.clear();
        assertThrows(UnsupportedOperationException.class, () -> frame.cloudVictims().clear());
        var bytes = frame.encode();
        assertEquals(2, ByteBuffer.wrap(bytes).getInt());
        var decoded = RollbackActorFrame.decode(bytes);
        assertEquals(Map.of(FIRST, 70, SECOND, 83), decoded.cloudVictims());
        assertEquals(72, decoded.age());
        assertArrayEquals(bytes, frame(Map.of(FIRST, 70, SECOND, 83)).encode());
        assertArrayEquals(bytes, decoded.encode());
    }

    @Test void rejectsDuplicateTruncatedAndOversizedHistory() {
        byte[] bytes = frame(Map.of(FIRST, 75, SECOND, 80)).encode();
        assertThrows(IllegalArgumentException.class, () -> RollbackActorFrame.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        int row = Long.BYTES * 2 + Integer.BYTES;
        byte[] duplicate = bytes.clone();
        System.arraycopy(bytes, bytes.length - 2 * row, duplicate, bytes.length - row, Long.BYTES * 2);
        assertThrows(IllegalArgumentException.class, () -> RollbackActorFrame.decode(duplicate));
        ByteBuffer.wrap(bytes).putInt(bytes.length - 2 * row - Integer.BYTES, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> RollbackActorFrame.decode(bytes));
    }
}
