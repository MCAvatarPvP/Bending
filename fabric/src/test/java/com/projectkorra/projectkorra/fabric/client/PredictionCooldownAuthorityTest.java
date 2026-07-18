package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictionCooldownAuthorityTest {
    @Test
    void staleStateCannotClearUnconfirmedLocalCooldown() {
        PredictionCooldownAuthority authority = new PredictionCooldownAuthority();
        authority.onLocalAdded("AirBlast", 2_000L);

        assertTrue(authority.reconcile(Set.of(), Set.of("AirBlast")).isEmpty());
    }

    @Test
    void authoritativeTransitionReleasesConfirmedLocalCooldown() {
        PredictionCooldownAuthority authority = new PredictionCooldownAuthority();
        authority.onLocalAdded("AirBlast", 2_000L);

        assertTrue(authority.reconcile(Set.of("AirBlast"), Set.of("AirBlast")).isEmpty());
        assertEquals(Set.of("AirBlast"), authority.reconcile(Set.of(), Set.of("AirBlast")));
    }

    @Test
    void newLocalCooldownMustBeConfirmedAgain() {
        PredictionCooldownAuthority authority = new PredictionCooldownAuthority();
        authority.onLocalAdded("AirBlast", 2_000L);
        authority.reconcile(Set.of("AirBlast"), Set.of("AirBlast"));
        authority.onLocalAdded("AirBlast", 3_000L);

        assertTrue(authority.reconcile(Set.of(), Set.of("AirBlast")).isEmpty());
    }

    @Test
    void delayedRemovalCannotClearANewerLocalGeneration() {
        PredictionCooldownAuthority authority = new PredictionCooldownAuthority();
        authority.onLocalAdded("AirBlast", 3_000L);

        assertTrue(authority.isLocallyPredicted("AirBlast"));
        authority.onLocalRemoved("AirBlast");
        assertFalse(authority.isLocallyPredicted("AirBlast"));
    }
}
