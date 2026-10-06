package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

/** Combines the real native capture fixtures; Fabric reads the exact resulting bootstrap bytes. */
class PaperRollbackBootstrapDataNativeTest {
    private static final RollbackTerrainCodec.Limits LIMITS = new RollbackTerrainCodec.Limits(4_096, 32, 1_048_576, 1_048_576, 65_536);
    private static final String DEFINITIONS = "12".repeat(32);
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void nativeTerrainRosterRulesAndClocksTravelAsOneCanonicalBootstrap() throws Exception {
        onTickThread(() -> {
            var captured = PaperRollbackTerrainTransfer.decode(fixture("terrain"), LIMITS);
            var rules = RollbackWorldSettings.decode(fixture("world-settings"));
            var roster = RollbackRosterData.decode(fixture("player-roster"));
            assertEquals(rules.gameTime(), roster.worldTime());
            // Complete the reference's eight captured cells with full native build-height columns.
            var builder = new RollbackTerrainSeed.Builder(new RollbackBlockStore.Bounds(-1, -64, -1, 3, 320, 1), 1_048_576);
            var air = Material.AIR.createBlockData(); air.setExactState("minecraft:air");
            var empty = new RollbackBlockStore.Cell(air, null, Biome.DESERT, "minecraft:plains", (byte) 15, .8, .4);
            for (int y = -64; y < 320; y++) for (int z = -1; z < 1; z++) for (int x = -1; x < 3; x++) {
                builder.append(y == 0 ? captured.cell(new RollbackBlockStore.Position(x, y, z)) : empty);
            }
            var border = new RollbackBorderData(0, 0, 29_999_984, .2, 5, 15, 5, 1_000, null);
            var environment = new RollbackEnvironmentData("minecraft:overworld", true, new RollbackEnvironmentData.Weather(.8f, .4f));
            var world = new RollbackWorldSeed(new UUID(0, 901), "neptune-duel-copy", -64, 320, 6_000, true, rules, border, environment, builder.finish());
            var catalog = new RollbackGraphCodec.Catalog(List.of(), List.of(), List.of());
            var codec = new RollbackGraphCodec(catalog, new RollbackGraphCodec.Limits(100, 100, 8_192, 1_024));
            var sides = new TreeMap<UUID, UUID>(); roster.players().keySet().forEach(id -> sides.put(id, id));
            var data = new RollbackBootstrapData(new UUID(0, 902), new UUID(0, 903), new UUID(0, 904), 3,
                    1_700_000_000_000L, 5_000_000_000L, DEFINITIONS, sides, world, roster,
                    RollbackConfiguration.captureData(Map.of()), PaperRollbackPlayerAccessNativeTest.fixture(roster), RollbackMaterials.capture(material -> com.projectkorra.projectkorra.platform.bukkit.BukkitMC.material(material).isSolid()), new RollbackTags(Map.of()), new RollbackServer.Metadata("Paper fixture", "1.21.11", "captured", true, 12), codec.encode(List.of("graph transport reference", List.copyOf(sides.keySet()))));
            byte[] bytes = data.encode(new PaperRollbackTerrainTransfer(), LIMITS);
            var copy = RollbackBootstrapData.decode(bytes, DEFINITIONS, new PaperRollbackTerrainTransfer(), LIMITS);
            assertArrayEquals(bytes, copy.encode(new PaperRollbackTerrainTransfer(), LIMITS));
            assertArrayEquals(roster.encode(), copy.roster().encode());
            assertEquals(data.access(), copy.access());
            assertEquals("minecraft:fire[age=7,east=false,north=true,south=false,up=false,west=false]",
                    copy.world().terrain().cell(new RollbackBlockStore.Position(2, 0, 0)).data().getExactState());
            try (var reference = getClass().getResourceAsStream("/rollback/duel-bootstrap.base64")) {
                assertNotNull(reference, "BOOTSTRAP_REFERENCE=" + Base64.getEncoder().encodeToString(bytes));
                assertArrayEquals(Base64.getMimeDecoder().decode(reference.readAllBytes()), bytes, () -> "BOOTSTRAP_REFERENCE=" + Base64.getEncoder().encodeToString(bytes));
            }
            return null;
        });
    }

    private byte[] fixture(String name) throws Exception {
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/" + name + ".base64"))) {
            return Base64.getMimeDecoder().decode(stream.readAllBytes());
        }
    }
}
