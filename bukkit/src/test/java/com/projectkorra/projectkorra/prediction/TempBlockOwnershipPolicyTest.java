package com.projectkorra.projectkorra.prediction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TempBlockOwnershipPolicyTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Test
    void ownerNeverSeesAnyOfTheirServerLayers() {
        assertEquals(-1, TempBlockOwnershipPolicy.visibleLayerIndex(List.of(owner), owner));
        assertEquals(-1, TempBlockOwnershipPolicy.visibleLayerIndex(List.of(owner, owner), owner));
        assertTrue(TempBlockOwnershipPolicy.topLayerOwnedBy(List.of(other, owner), owner));
        assertFalse(TempBlockOwnershipPolicy.clientDisplaysLayer(true, false));
        assertTrue(TempBlockOwnershipPolicy.clientDisplaysLayer(true, true));
    }

    @Test
    void overlapExposesHighestLayerNotOwnedByViewer() {
        assertEquals(1, TempBlockOwnershipPolicy.visibleLayerIndex(List.of(owner, other, owner), owner));
        assertEquals(2, TempBlockOwnershipPolicy.visibleLayerIndex(List.of(owner, other, owner), other));
        assertFalse(TempBlockOwnershipPolicy.topLayerOwnedBy(List.of(owner, other), owner));
    }

    @Test
    void unownedViewerSeesAuthoritativeTopLayer() {
        assertEquals(1, TempBlockOwnershipPolicy.visibleLayerIndex(List.of(owner, other), UUID.randomUUID()));
        assertEquals(1, TempBlockOwnershipPolicy.visibleLayerIndex(List.of(owner, other), null));
    }
}
