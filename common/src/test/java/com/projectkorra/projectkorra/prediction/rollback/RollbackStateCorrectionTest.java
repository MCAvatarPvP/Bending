package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackStateCorrectionTest {
    private static final UUID SESSION = new UUID(1, 2);
    private static final String DEFINITIONS = "a".repeat(64);
    private static RollbackStateCorrection correction(long publication, int bytes) {
        byte[] payload = new byte[bytes]; new Random(42).nextBytes(payload);
        return new RollbackStateCorrection(SESSION, publication, 3, 12, DEFINITIONS, payload);
    }
    private static RollbackStateCorrectionChunk.Assembler assembler(int maximum) {
        return new RollbackStateCorrectionChunk.Assembler(SESSION, DEFINITIONS, maximum, 100, 10);
    }
    @Test void envelopePreservesIdentityAndDetachedPayloadAndRejectsMalformedLengths() {
        var value = correction(7, 3); byte[] bytes = value.encode();
        assertEquals(83, bytes.length); assertEquals(value, RollbackStateCorrection.decode(bytes, 100));
        value.payload()[0]++; assertEquals(value, RollbackStateCorrection.decode(bytes, 100));
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThrows(IllegalArgumentException.class, () -> RollbackStateCorrection.decode(truncated, 100));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackStateCorrection.decode(Arrays.copyOf(bytes, 84), 100));
        assertThrows(IllegalArgumentException.class, () -> RollbackStateCorrection.decode(bytes, 82));
        for (int offset : List.of(0, 76)) {
            byte[] invalid = bytes.clone(); ByteBuffer.wrap(invalid).putInt(offset, -1);
            assertThrows(IllegalArgumentException.class, () -> RollbackStateCorrection.decode(invalid, 100));
        }
        for (int offset : List.of(20, 28, 36)) {
            byte[] invalid = bytes.clone(); ByteBuffer.wrap(invalid).putLong(offset, -1);
            assertThrows(IllegalArgumentException.class, () -> RollbackStateCorrection.decode(invalid, 100));
        }
    }
    @Test void outOfOrderChunksAssembleOnceAndPublicationGapsArePermitted() {
        var correction = correction(7, 30000); var parts = RollbackStateCorrectionChunk.split(correction);
        var assembler = assembler(40000);
        assertEquals(2, parts.size());
        for (var part : parts) assertEquals(part, RollbackStateCorrectionChunk.decode(part.encode()));
        assertNull(assembler.receive(parts.getLast(), 100)); assertNull(assembler.receive(parts.getLast(), 101));
        assertEquals(correction, assembler.receive(parts.getFirst(), 102));
        assertNull(assembler.receive(parts.getFirst(), 103));
        var newer = correction(10, 4);
        assertEquals(newer, assembler.receive(RollbackStateCorrectionChunk.split(newer).getFirst(), 104));
    }
    @Test void rewrittenInterleavedForeignAndOverBudgetTransfersCannotProduceAState() {
        var parts = RollbackStateCorrectionChunk.split(correction(7, 30000));
        var assembler = assembler(40000);
        assertNull(assembler.receive(new RollbackStateCorrectionChunk(new UUID(4, 5), 7, 0, 2, parts.getFirst().data()), 100));
        assertNull(assembler.receive(parts.getFirst(), 100));
        byte[] changed = parts.getFirst().data(); changed[0]++;
        assertThrows(IllegalArgumentException.class, () -> assembler.receive(new RollbackStateCorrectionChunk(SESSION, 7, 0, 2, changed), 101));
        assertThrows(IllegalStateException.class, () -> assembler.receive(parts.getLast(), 101));
        var interleaved = assembler(40000); interleaved.receive(parts.getFirst(), 100);
        assertThrows(IllegalArgumentException.class, () -> interleaved.receive(RollbackStateCorrectionChunk.split(correction(8, 3)).getFirst(), 101));
        var limited = assembler(100);
        assertThrows(IllegalArgumentException.class, () -> limited.receive(parts.getFirst(), 100));
        var timeout = assembler(40000); timeout.receive(parts.getFirst(), 100);
        assertThrows(IllegalStateException.class, () -> timeout.poll(111));
        var wrongSchema = new RollbackStateCorrection(SESSION, 7, 3, 12, "b".repeat(64), new byte[]{1});
        assertThrows(IllegalArgumentException.class, () -> assembler(100).receive(RollbackStateCorrectionChunk.split(wrongSchema).getFirst(), 100));
        var single = RollbackStateCorrectionChunk.split(correction(7, 1)).getFirst();
        assertThrows(IllegalArgumentException.class, () -> assembler(100).receive(new RollbackStateCorrectionChunk(SESSION, 8, 0, 1, single.data()), 100));
    }
}
