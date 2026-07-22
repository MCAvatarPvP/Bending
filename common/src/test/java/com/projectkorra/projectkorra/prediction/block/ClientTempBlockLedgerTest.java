package com.projectkorra.projectkorra.prediction.block;

import com.projectkorra.projectkorra.prediction.block.ClientTempBlockLedger;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;

import org.junit.jupiter.api.Test;

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
}
