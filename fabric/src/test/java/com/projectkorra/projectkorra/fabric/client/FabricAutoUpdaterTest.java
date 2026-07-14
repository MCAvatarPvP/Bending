package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricAutoUpdaterTest {
    @Test
    void semanticVersionsAreComparedNumerically() {
        assertTrue(FabricAutoUpdater.compareVersions("1.10.10", "1.10.2") > 0);
        assertTrue(FabricAutoUpdater.compareVersions("v2.0.0", "1.99.99") > 0);
        assertEquals(0, FabricAutoUpdater.compareVersions("v1.10.2", "1.10.2"));
    }

    @Test
    void stableReleaseSortsAfterPrerelease() {
        assertTrue(FabricAutoUpdater.compareVersions("1.10.3", "1.10.3-beta.2") > 0);
        assertTrue(FabricAutoUpdater.compareVersions("1.10.3-beta.10", "1.10.3-beta.2") > 0);
    }
}
