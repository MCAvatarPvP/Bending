package com.projectkorra.projectkorra.prediction.block;

import com.projectkorra.projectkorra.prediction.block.ClientTempBlockLedger;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTempBlockLedgerTest {
    private final UUID viewer = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Test
    void ownedLifecycleHidesEveryPhysicalStateWithoutChangingViewerState() {
        ClientTempBlockLedger<String, String> ledger = new ClientTempBlockLedger<>();

        assertTrue(ledger.apply("0,64,0", TempBlockSync.Operation.CREATE,
                7L, 1L, viewer, "ice", "water"));
        assertTrue(ledger.hidesServerWorld("0,64,0", viewer));
        assertEquals("water", ledger.viewerState("0,64,0").orElseThrow());

        // A fluid-level/neighbor packet does not participate in matching. The
        // coordinate remains hidden for the full owned layer lifecycle.
        assertTrue(ledger.hidesServerWorld("0,64,0", viewer));
        assertFalse(ledger.hidesServerWorld("0,64,0", other));
    }

    @Test
    void preMutationPublicationMakesPostMutationDuplicateIdempotent() {
        ClientTempBlockLedger<String, String> ledger = new ClientTempBlockLedger<>();
        assertTrue(ledger.apply("p", TempBlockSync.Operation.CREATE,
                11L, 90L, viewer, "earth", "air"));
        assertFalse(ledger.apply("p", TempBlockSync.Operation.CREATE,
                11L, 90L, viewer, "earth", "air"));
        assertEquals(1, ledger.coordinateCount());

        assertTrue(ledger.apply("p", TempBlockSync.Operation.REVERT,
                11L, 91L, viewer, "air", "air"));
        assertFalse(ledger.apply("p", TempBlockSync.Operation.REVERT,
                11L, 91L, viewer, "air", "air"));
        assertEquals(0, ledger.coordinateCount());
    }

    @Test
    void overlappingOtherLayerDoesNotEndOwnedSuppression() {
        ClientTempBlockLedger<String, String> ledger = new ClientTempBlockLedger<>();
        ledger.apply("p", TempBlockSync.Operation.CREATE, 1L, 1L, viewer, "ice", "water");
        ledger.apply("p", TempBlockSync.Operation.CREATE, 2L, 2L, other, "stone", "stone");

        assertTrue(ledger.hidesServerWorld("p", viewer));
        assertEquals("stone", ledger.overlayState("p", viewer).orElseThrow(),
                "a newer remote layer must render over the local prediction");
        ledger.apply("p", TempBlockSync.Operation.REVERT, 2L, 3L, other, "ice", "water");
        assertTrue(ledger.hidesServerWorld("p", viewer));
        assertTrue(ledger.overlayState("p", viewer).isEmpty());
        ledger.apply("p", TempBlockSync.Operation.REVERT, 1L, 4L, viewer, "water", "water");
        assertFalse(ledger.hidesServerWorld("p", viewer));
    }

    @Test
    void serverOnlyTopLayerOverlaysAndThenRevealsThePredictedLayer() {
        ClientTempBlockLedger<String, String> ledger = new ClientTempBlockLedger<>();
        ledger.apply("p", TempBlockSync.Operation.CREATE, 8L, 1L, viewer, "ice", "water");
        ledger.apply("p", TempBlockSync.Operation.CREATE, 9L, 2L, null, "snow", "snow");

        assertEquals("snow", ledger.overlayState("p", viewer).orElseThrow());
        ledger.apply("p", TempBlockSync.Operation.REVERT, 9L, 3L, null, "ice", "water");
        assertTrue(ledger.overlayState("p", viewer).isEmpty());
        assertTrue(ledger.hidesServerWorld("p", viewer));
    }

    @Test
    void staleCreateCannotResurrectARevertedLayer() {
        ClientTempBlockLedger<String, String> ledger = new ClientTempBlockLedger<>();
        ledger.apply("p", TempBlockSync.Operation.CREATE, 3L, 10L, viewer, "ice", "water");
        ledger.apply("p", TempBlockSync.Operation.REVERT, 3L, 11L, viewer, "water", "water");

        // Ordered transports should not reorder these, but duplicate/stale
        // delivery is still harmless after the coordinate has closed.
        assertFalse(ledger.apply("p", TempBlockSync.Operation.CREATE,
                3L, 10L, viewer, "ice", "water"));
        assertFalse(ledger.hidesServerWorld("p", viewer));
    }

    @Test
    void explicitDiscardClosesTheLayerAndTombstonesStaleTraffic() {
        ClientTempBlockLedger<String, String> ledger = new ClientTempBlockLedger<>();
        ledger.apply("p", TempBlockSync.Operation.CREATE, 4L, 20L, viewer, "ice", "water");

        assertTrue(ledger.apply("p", TempBlockSync.Operation.DISCARD,
                4L, 21L, viewer, "stone", "stone"));
        assertFalse(ledger.hidesServerWorld("p", viewer));
        assertFalse(ledger.apply("p", TempBlockSync.Operation.CREATE,
                4L, 20L, viewer, "ice", "water"));
    }

    @Test
    void confirmsOwnershipByActionAndCoordinateInsteadOfProcessLocalLayerId() {
        ClientTempBlockLedger<String, String> ledger = new ClientTempBlockLedger<>();
        ledger.apply("p", TempBlockSync.Operation.CREATE,
                42L, 9001L, 1L, viewer, "ice", "water");

        assertTrue(ledger.hasOwnedLayerForAction("p", viewer, 42L));
        assertFalse(ledger.hasOwnedLayerForAction("p", viewer, 41L));
        assertFalse(ledger.hasOwnedLayerForAction("other", viewer, 42L));
        assertFalse(ledger.hasOwnedLayerForAction("p", other, 42L));

        ledger.apply("p", TempBlockSync.Operation.REVERT,
                42L, 9001L, 2L, viewer, "water", "water");
        assertFalse(ledger.hasOwnedLayerForAction("p", viewer, 42L));
    }

    @Test
    void committedSnapshotPrunesAbsentLayersAndReportsAffectedCoordinates() {
        ClientTempBlockLedger<String, String> ledger = new ClientTempBlockLedger<>();
        ledger.apply("missing", TempBlockSync.Operation.CREATE,
                10L, 1L, viewer, "ice", "water");
        ledger.apply("retained", TempBlockSync.Operation.CREATE,
                20L, 2L, other, "stone", "air");

        Set<String> affected = ledger.pruneAbsentFromSnapshot(Set.of(20L));

        assertEquals(Set.of("missing"), affected);
        assertFalse(ledger.containsLayer("missing", 10L));
        assertTrue(ledger.containsLayer("retained", 20L));
        assertEquals(1, ledger.coordinateCount());
    }

    @Test
    void snapshotPruningRetainsPresentStackOrderAndTopState() {
        ClientTempBlockLedger<String, String> ledger = new ClientTempBlockLedger<>();
        ledger.apply("stack", TempBlockSync.Operation.CREATE,
                30L, 1L, viewer, "dirt", "air");
        ledger.apply("stack", TempBlockSync.Operation.CREATE,
                31L, 2L, other, "ice", "dirt");
        ledger.apply("revealed", TempBlockSync.Operation.CREATE,
                33L, 3L, other, "dirt", "air");
        ledger.apply("revealed", TempBlockSync.Operation.CREATE,
                34L, 4L, other, "ice", "dirt");
        ledger.apply("untouched", TempBlockSync.Operation.CREATE,
                32L, 5L, other, "stone", "air");

        Set<String> affected = ledger.pruneAbsentFromSnapshot(Set.of(31L, 32L, 33L));

        assertEquals(Set.of("stack", "revealed"), affected);
        assertFalse(ledger.containsLayer("stack", 30L));
        assertTrue(ledger.containsLayer("stack", 31L));
        assertEquals("ice", ledger.physicalState("stack").orElseThrow());
        assertFalse(ledger.containsLayer("revealed", 34L));
        assertEquals("dirt", ledger.physicalState("revealed").orElseThrow(),
                "removing an absent top layer must reveal the newest retained layer");
        assertEquals("stone", ledger.physicalState("untouched").orElseThrow());
        assertEquals(3, ledger.coordinateCount());
    }

    @Test
    void emptyCommittedSnapshotRetiresEveryActiveCoordinate() {
        ClientTempBlockLedger<String, String> ledger = new ClientTempBlockLedger<>();
        ledger.apply("first", TempBlockSync.Operation.CREATE,
                40L, 1L, viewer, "ice", "water");
        ledger.apply("second", TempBlockSync.Operation.CREATE,
                41L, 2L, other, "stone", "air");

        Set<String> affected = ledger.pruneAbsentFromSnapshot(Set.of());

        assertEquals(Set.of("first", "second"), affected);
        assertEquals(0, ledger.coordinateCount());
        assertTrue(ledger.physicalState("first").isEmpty());
        assertTrue(ledger.physicalState("second").isEmpty());
    }

    @Test
    void snapshotPruningKeepsRevisionTombstoneAgainstStaleReopen() {
        ClientTempBlockLedger<String, String> ledger = new ClientTempBlockLedger<>();
        ledger.apply("p", TempBlockSync.Operation.CREATE,
                50L, 100L, viewer, "ice", "water");

        ledger.pruneAbsentFromSnapshot(Set.of());

        assertFalse(ledger.apply("p", TempBlockSync.Operation.CREATE,
                50L, 100L, viewer, "ice", "water"));
        assertFalse(ledger.apply("p", TempBlockSync.Operation.CREATE,
                50L, 99L, viewer, "ice", "water"));
        assertFalse(ledger.containsLayer("p", 50L));
        assertEquals(0, ledger.coordinateCount());

        assertTrue(ledger.apply("p", TempBlockSync.Operation.CREATE,
                50L, 101L, viewer, "ice", "water"),
                "a genuinely newer post-snapshot lifecycle may reuse the layer id");
    }

    @Test
    void committedSnapshotMayRestoreEqualRevisionAfterViewRadiusPrune() {
        ClientTempBlockLedger<String, String> ledger = new ClientTempBlockLedger<>();
        ledger.apply("p", TempBlockSync.Operation.CREATE,
                77L, 8L, 500L, viewer, "ice", "water");
        ledger.pruneAbsentFromSnapshot(Set.of());

        assertTrue(ledger.applySnapshot("p", 77L, 8L, 500L,
                viewer, "ice", "water"));
        assertTrue(ledger.containsLayer("p", 8L));
        assertEquals("ice", ledger.physicalState("p").orElseThrow());
        assertFalse(ledger.applySnapshot("p", 77L, 8L, 499L,
                viewer, "stone", "air"));
    }

    @Test
    void committedSnapshotRebuildsStackOrderFromItsOperationOrder() {
        ClientTempBlockLedger<String, String> ledger = new ClientTempBlockLedger<>();
        ledger.apply("p", TempBlockSync.Operation.CREATE,
                2L, 20L, 1L, other, "newer", "older");

        assertTrue(ledger.applySnapshot("p", 1L, 10L, 1L,
                viewer, "older", "base"));
        assertTrue(ledger.applySnapshot("p", 2L, 20L, 1L,
                other, "newer", "older"));

        assertEquals("newer", ledger.physicalState("p").orElseThrow(),
                "snapshot order must replace partial incremental arrival order");
        assertEquals(20L, ledger.topLayerId("p").orElseThrow(),
                "semantic concealment must identify the same stack top as physical composition");
    }
}
