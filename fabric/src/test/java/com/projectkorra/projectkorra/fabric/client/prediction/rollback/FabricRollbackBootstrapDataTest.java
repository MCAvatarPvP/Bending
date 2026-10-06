package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackBootstrapDataTest {
    private static final RollbackTerrainCodec.Limits LIMITS = new RollbackTerrainCodec.Limits(4_096, 32, 1_048_576, 1_048_576, 65_536);
    @BeforeAll static void bootstrap() { SharedConstants.createGameVersion(); Bootstrap.initialize(); }

    @Test void importsTheIdenticalPaperDuelSeedWithoutSubstitutingClientRulesOrTerrain() throws Exception {
        byte[] bytes;
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/duel-bootstrap.base64"))) {
            bytes = Base64.getMimeDecoder().decode(stream.readAllBytes());
        }
        var seed = RollbackBootstrapData.decode(bytes, "12".repeat(32), new FabricRollbackTerrainTransfer(), LIMITS);
        assertArrayEquals(bytes, seed.encode(new FabricRollbackTerrainTransfer(), LIMITS));
        assertEquals(new UUID(0, 902), seed.session()); assertEquals(new UUID(0, 904), seed.match()); assertEquals(3, seed.round());
        assertEquals(1_700_000_000_000L, seed.epochMillis()); assertEquals(5_000_000_000L, seed.epochNanos());
        assertEquals(seed.world().settings().gameTime(), seed.roster().worldTime());
        assertEquals(seed.sides().keySet(), seed.roster().players().keySet());
        assertEquals(seed.sides().keySet(), seed.access().players().keySet());
        var first = seed.access().players().get(seed.sides().keySet().iterator().next());
        assertTrue(first.hasPermission("BENDING.FEATURE.ALLOWED"));
        assertFalse(first.hasPermission("bending.feature.denied")); assertFalse(first.hasPermission("other.effective"));
        assertEquals(142, first.profile().ping()); assertTrue(first.profile().operator());
        assertEquals(1, first.hidden().size());
        assertThrows(IllegalStateException.class, () -> first.hasPermission("bending.unknown"));
        assertEquals(new RollbackWorld.Identity("neptune-duel-copy", RollbackWorld.Dimension.NORMAL, -64, 320), seed.world().identity());
        assertEquals(6_000, seed.world().conditions().fullTime()); assertEquals("HARD", seed.world().conditions().difficulty());
        assertEquals(new RollbackWorldSettings.Flag(false), seed.world().settings().rules().get("minecraft:fire_damage"));
        assertEquals(new RollbackEnvironmentData.Weather(.8f, .4f), seed.world().environment().weather());
        assertEquals(1_000, seed.world().border().size());
        assertTrue(seed.materials().isSolid(com.projectkorra.projectkorra.platform.mc.Material.STONE));
        assertFalse(seed.materials().isSolid(com.projectkorra.projectkorra.platform.mc.Material.AIR));
        assertEquals(net.minecraft.block.Blocks.STONE_SLAB, FabricRollbackGeometry.decode(seed.world().terrain().cell(new RollbackBlockStore.Position(0, 0, 0)).data()).getBlock());
        var graph = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(), List.of(), List.of()),
                new RollbackGraphCodec.Limits(100, 100, 8_192, 1_024));
        assertEquals(List.of("graph transport reference", List.copyOf(seed.sides().keySet())), graph.decode(seed.bending()));
    }
}
