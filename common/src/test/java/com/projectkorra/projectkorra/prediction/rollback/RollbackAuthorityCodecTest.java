package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RollbackAuthorityCodecTest {
    private static final UUID SESSION = new UUID(0, 30), A = new UUID(0, 1), B = new UUID(0, 2);
    private static final RollbackPlayerInput INPUT = input();

    @Test void releaseIntentSurvivesAuthorityAndOlderProtocolIsRejected() {
        var release = new RollbackPlayerInput(INPUT.movement(), false, List.of(new RollbackPlayerInput.Edge(
                new RollbackInputActions.Action(1, 123, RollbackInputActions.Kind.RELEASE_USE_ITEM, -1), 70, -20)));
        var update = new RollbackAuthorityUpdate(SESSION, 1, 0, 1, 0, 1,
                List.of(Map.of(A, release)), Map.of(A, List.of(1L)));
        byte[] bytes = RollbackAuthorityCodec.encode(update);
        assertEquals(update, RollbackAuthorityCodec.decode(bytes));
        assertTrue(release.predict().actions().isEmpty());
        ByteBuffer.wrap(bytes).putInt(0, 5);
        assertThrows(IllegalArgumentException.class, () -> RollbackAuthorityCodec.decode(bytes));
    }

    @Test void canonicalBytesPreserveControlsActionAimSeedsAndGappedReceipts() {
        var first = new LinkedHashMap<UUID, RollbackPlayerInput>(); first.put(B, INPUT.predict()); first.put(A, INPUT);
        var frames = List.<Map<UUID, RollbackPlayerInput>>of(first, Map.of(A, INPUT.predict(), B, INPUT));
        var update = new RollbackAuthorityUpdate(SESSION, 8, 3, 10, 7, 9, frames, Map.of(A, List.of(9L, 12L), B, List.of(8L, 10L)));
        byte[] bytes = RollbackAuthorityCodec.encode(update);
        var decoded = RollbackAuthorityCodec.decode(bytes);
        assertEquals(update, decoded);
        assertArrayEquals(bytes, RollbackAuthorityCodec.encode(decoded));
        assertThrows(UnsupportedOperationException.class, () -> decoded.frames().getFirst().clear());
        assertThrows(UnsupportedOperationException.class, () -> decoded.receivedTicks().get(A).add(11L));
    }

    @Test void malformedWireCannotProducePartialOrInvalidInputs() {
        // Single player, no receipts: 63-byte header + 18-byte roster entry before movement.
        byte[] valid = RollbackAuthorityCodec.encode(new RollbackAuthorityUpdate(SESSION, 1, 0, 1, 0, 1,
                List.of(Map.of(A, INPUT)), Map.of(A, List.of())));
        for (int size = 0; size < valid.length; size++) {
            byte[] truncated = Arrays.copyOf(valid, size);
            assertThrows(IllegalArgumentException.class, () -> RollbackAuthorityCodec.decode(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackAuthorityCodec.decode(Arrays.copyOf(valid, valid.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> RollbackAuthorityCodec.decode(new byte[RollbackAuthorityCodec.MAXIMUM_BYTES + 1]));
        for (int offset : List.of(89, 98)) { // Jump and sprint flags are strict booleans.
            byte[] changed = valid.clone(); changed[offset] = 2;
            assertThrows(IllegalArgumentException.class, () -> RollbackAuthorityCodec.decode(changed));
        }
        for (int offset : List.of(81, 85, 90, 94, 119, 123)) {
            byte[] changed = valid.clone(); ByteBuffer.wrap(changed).putFloat(offset, Float.NaN);
            assertThrows(IllegalArgumentException.class, () -> RollbackAuthorityCodec.decode(changed));
        }
        for (int offset : List.of(60, 99, 116, 118)) { // Roster, action count, first action kind.
            byte[] changed = valid.clone(); changed[offset] = (byte) 255;
            assertThrows(IllegalArgumentException.class, () -> RollbackAuthorityCodec.decode(changed));
        }
        byte[] version = valid.clone(); ByteBuffer.wrap(version).putInt(0, 1);
        assertThrows(IllegalArgumentException.class, () -> RollbackAuthorityCodec.decode(version));
        ByteBuffer.wrap(version).putInt(0, 2);
        assertThrows(IllegalArgumentException.class, () -> RollbackAuthorityCodec.decode(version));
        byte[] seed = valid.clone(); ByteBuffer.wrap(seed).putLong(108, 0);
        assertThrows(IllegalArgumentException.class, () -> RollbackAuthorityCodec.decode(seed));
    }

    @Test void oversizedUpdatesAndChunksAreRejectedBeforeAssembly() {
        var roster = new TreeMap<UUID, RollbackPlayerInput>();
        var receipts = new TreeMap<UUID, List<Long>>();
        for (int index = 0; index < 128; index++) {
            var player = new UUID(0, index); roster.put(player, INPUT); receipts.put(player, List.of());
        }
        var update = new RollbackAuthorityUpdate(SESSION, 1, 0, 201, 1, 1, Collections.nCopies(201, roster), receipts);
        assertThrows(IllegalArgumentException.class, () -> RollbackAuthorityCodec.encode(update));
        assertThrows(IllegalArgumentException.class, () -> new RollbackAuthorityChunk(SESSION, 1, 0, 2, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> new RollbackAuthorityChunk(SESSION, 1, 42, 43, new byte[24_576]));
        assertThrows(IllegalArgumentException.class, () -> RollbackAuthorityChunk.decode(new byte[RollbackAuthorityChunk.MAXIMUM_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> RollbackAuthorityChunk.decode(new byte[31]));
        byte[] data = { 1, 2, 3 };
        var owned = new RollbackAuthorityChunk(SESSION, 1, 0, 1, data);
        data[0] = 9; owned.data()[1] = 9;
        assertArrayEquals(new byte[] { 1, 2, 3 }, owned.data());
    }

    @Test void reorderedAndDuplicateChunksPublishOnlyOneCompleteUpdate() {
        var update = largeUpdate(1);
        var chunks = RollbackAuthorityChunk.split(update);
        assertTrue(chunks.size() > 1);
        var assembler = new RollbackAuthorityChunk.Assembler(SESSION, 10, 5);
        for (int index = chunks.size() - 1; index > 0; index--) {
            var wire = RollbackAuthorityChunk.decode(chunks.get(index).encode());
            assertTrue(wire.encode().length <= RollbackAuthorityChunk.MAXIMUM_BYTES);
            assertNull(assembler.receive(wire, 10));
            assertNull(assembler.receive(wire, 10));
        }
        assertEquals(update, assembler.receive(RollbackAuthorityChunk.decode(chunks.getFirst().encode()), 11));
        for (var chunk : chunks) assertNull(assembler.receive(chunk, 11));
        RollbackAuthorityUpdate second = null;
        for (var chunk : RollbackAuthorityChunk.split(largeUpdate(2))) second = assembler.receive(chunk, 12);
        assertEquals(largeUpdate(2), second);
    }

    @Test void rewrittenPartOrPublicationGapStopsTheAssembler() {
        var chunks = RollbackAuthorityChunk.split(largeUpdate(1));
        var assembler = new RollbackAuthorityChunk.Assembler(SESSION, 10, 5);
        assertNull(assembler.receive(chunks.getFirst(), 10));
        byte[] rewritten = chunks.getFirst().data(); rewritten[100] ^= 1;
        var conflict = new RollbackAuthorityChunk(SESSION, 1, 0, chunks.size(), rewritten);
        assertThrows(IllegalArgumentException.class, () -> assembler.receive(conflict, 10));
        assertThrows(IllegalStateException.class, () -> assembler.receive(chunks.getLast(), 10));

        var skipped = new RollbackAuthorityChunk.Assembler(SESSION, 10, 5);
        assertThrows(IllegalArgumentException.class, () -> skipped.receive(RollbackAuthorityChunk.split(largeUpdate(2)).getFirst(), 10));
        assertThrows(IllegalStateException.class, () -> skipped.poll(11));
    }

    @Test void envelopeCannotSubstituteAnotherSessionOrPublication() {
        var update = new RollbackAuthorityUpdate(SESSION, 2, 0, 1, 0, 1, List.of(Map.of(A, INPUT)), Map.of(A, List.of()));
        byte[] bytes = RollbackAuthorityCodec.encode(update);
        var assembler = new RollbackAuthorityChunk.Assembler(SESSION, 10, 5);
        assertNull(assembler.receive(new RollbackAuthorityChunk(UUID.randomUUID(), 1, 0, 1, bytes), 10));
        assertThrows(IllegalArgumentException.class, () -> assembler.receive(new RollbackAuthorityChunk(SESSION, 1, 0, 1, bytes), 10));
        assertThrows(IllegalStateException.class, () -> assembler.poll(11));
    }

    @Test void incompleteUpdatesExpireAndAssemblyStaysOnItsOwnerThread() throws InterruptedException {
        var assembler = new RollbackAuthorityChunk.Assembler(SESSION, 10, 5);
        var wrongThread = new AtomicReference<Throwable>();
        var thread = new Thread(() -> { try { assembler.poll(10); } catch (Throwable failure) { wrongThread.set(failure); } });
        thread.start(); thread.join();
        assertInstanceOf(IllegalStateException.class, wrongThread.get());
        assembler.receive(RollbackAuthorityChunk.split(largeUpdate(1)).getFirst(), 10);
        assembler.poll(15);
        assertThrows(IllegalStateException.class, () -> assembler.poll(16));
        assertThrows(IllegalStateException.class, () -> assembler.poll(17));
        var reversed = new RollbackAuthorityChunk.Assembler(SESSION, 10, 5);
        assertThrows(IllegalStateException.class, () -> reversed.poll(9));
    }

    private static RollbackAuthorityUpdate largeUpdate(long publication) {
        return new RollbackAuthorityUpdate(SESSION, publication, 0, 201, 1, 1,
                Collections.nCopies(201, Map.of(A, INPUT, B, INPUT)), Map.of(A, List.of(2L, 200L), B, List.of(4L, 202L)));
    }
    private static RollbackPlayerInput input() {
        var edges = new ArrayList<RollbackPlayerInput.Edge>();
        for (var kind : RollbackInputActions.Kind.values()) {
            long sequence = kind.ordinal() + 1;
            edges.add(new RollbackPlayerInput.Edge(new RollbackInputActions.Action(sequence, sequence * 19, kind,
                    kind == RollbackInputActions.Kind.SLOT_CHANGE ? 8 : -1, kind == RollbackInputActions.Kind.RIGHT_CLICK ? RollbackInputActions.Hand.OFF : RollbackInputActions.defaultHand(kind)), 90 - sequence, 40 - sequence));
        }
        return new RollbackPlayerInput(new RollbackMovementInput(-0.5f, 1, true, 87, -42), true, edges);
    }
}
