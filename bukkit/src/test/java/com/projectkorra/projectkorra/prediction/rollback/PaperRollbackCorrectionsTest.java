package com.projectkorra.projectkorra.prediction.rollback;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackCorrectionsTest {
    private static final UUID SESSION = new UUID(1, 2);
    private static final UUID PLAYER = new UUID(3, 4);
    private static final String DEFINITIONS = "ab".repeat(32);
    private static final RollbackPlayerInput INPUT = new RollbackPlayerInput(
            new RollbackMovementInput(0, 0, false, 0, 0), false, List.of());

    private static RollbackAuthorityUpdate authority(long publication, long finalized) {
        long head = finalized + 1;
        return new RollbackAuthorityUpdate(SESSION, publication, 5, head, finalized, head,
                List.of(Map.of(PLAYER, INPUT)), Map.of(PLAYER, List.of()));
    }

    @Test void publishesTheSameConfirmedFrontierAtTheConfiguredInterval() {
        byte[] payload = new byte[RollbackStateCorrectionChunk.DATA_BYTES + 1];
        new Random(72).nextBytes(payload);
        int budget = RollbackStateCorrection.HEADER_BYTES + payload.length;
        var settings = new PaperRollbackCorrections.Settings(DEFINITIONS, budget, 4, () -> payload);
        var confirmed = new AtomicInteger(1);
        var exports = new AtomicInteger();
        var sender = new PaperRollbackCorrections(SESSION, settings, encoder -> {
            exports.incrementAndGet();
            return new RollbackCombatRuntime.Export(confirmed.get(), confirmed.get(), 5, encoder.get());
        });
        assertTrue(sender.encode(authority(1, 0)).isEmpty());
        var parts = sender.encode(authority(2, 1));
        assertEquals(2, parts.size());
        var assembler = new RollbackStateCorrectionChunk.Assembler(SESSION, DEFINITIONS, budget, 1, 5);
        assertNull(assembler.receive(RollbackStateCorrectionChunk.decode(parts.getFirst()), 1));
        var result = assembler.receive(RollbackStateCorrectionChunk.decode(parts.getLast()), 1);
        assertEquals(new RollbackStateCorrection(SESSION, 2, 5, 1, DEFINITIONS, payload), result);
        assertTrue(sender.encode(authority(3, 1)).isEmpty());
        assertTrue(sender.encode(authority(4, 4)).isEmpty());
        assertEquals(1, exports.get());
        confirmed.set(5);
        assertEquals(2, sender.encode(authority(5, 5)).size());
        assertEquals(2, exports.get());
    }

    @Test void rejectsOversizedOrMismatchedCheckpointsBeforeSending() {
        var tooSmall = new PaperRollbackCorrections.Settings(DEFINITIONS, RollbackStateCorrection.HEADER_BYTES + 1, 1,
                () -> new byte[2]);
        var sender = new PaperRollbackCorrections(SESSION, tooSmall,
                encoder -> new RollbackCombatRuntime.Export(2, 2, 5, encoder.get()));
        assertThrows(IllegalStateException.class, () -> sender.encode(authority(1, 2)));

        var settings = new PaperRollbackCorrections.Settings(DEFINITIONS, 128, 1, () -> new byte[1]);
        var stale = new PaperRollbackCorrections(SESSION, settings,
                encoder -> new RollbackCombatRuntime.Export(2, 2, 4, encoder.get()));
        assertThrows(IllegalStateException.class, () -> stale.encode(authority(1, 2)));
        var wrongSession = new PaperRollbackCorrections(PLAYER, settings, encoder -> fail("Foreign session exported"));
        assertThrows(IllegalStateException.class, () -> wrongSession.encode(authority(1, 2)));
    }
}
