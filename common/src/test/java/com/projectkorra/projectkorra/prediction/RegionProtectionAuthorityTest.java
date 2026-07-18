package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionProtectionAuthorityTest {
    @Test
    void boxesUseInclusiveBlockCoordinatesAndExactWorldIdentity() {
        RegionProtectionAuthority.Snapshot snapshot = new RegionProtectionAuthority.Snapshot(
                "minecraft:neptune_arenas", List.of("", "earthblast"),
                List.of(new RegionProtectionAuthority.Box(1L, -4, 60, 8, 4, 90, 12)));
        World neptune = new World() { @Override public String getName() { return "minecraft:neptune_arenas"; } };
        World overworld = new World() { @Override public String getName() { return "minecraft:overworld"; } };

        assertTrue(snapshot.isProtected(new Location(neptune, -4, 60, 8), null));
        assertTrue(snapshot.isProtected(new Location(neptune, 4.99, 90.99, 12.99), null));
        assertFalse(snapshot.isProtected(new Location(neptune, 5, 90, 12), null));
        assertFalse(snapshot.isProtected(new Location(overworld, 0, 70, 10), null));
    }

    @Test
    void duplicateAabbsMergeAbilityMasks() {
        RegionProtectionAuthority.Snapshot snapshot = new RegionProtectionAuthority.Snapshot(
                "world", List.of("", "airblast"), List.of(
                new RegionProtectionAuthority.Box(1L, 0, 0, 0, 1, 1, 1),
                new RegionProtectionAuthority.Box(2L, 0, 0, 0, 1, 1, 1)));

        assertTrue((snapshot.boxes().getFirst().abilityMask() & 3L) == 3L);
    }
}
