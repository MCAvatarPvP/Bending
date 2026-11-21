package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackEnvironmentData;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.WeightedAttributeList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackEnvironmentTest {
    private static final Vec3d POSITION = new Vec3d(.5, .5, .5);
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }
    static RollbackEnvironmentData seed() {
        try (var input = Objects.requireNonNull(FabricRollbackEnvironmentTest.class.getResourceAsStream("/rollback/environment.base64"))) {
            return RollbackEnvironmentData.decode(Base64.getMimeDecoder().decode(input.readAllBytes()));
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    @Test void nativeLayersMatchTheCapturedPaperValues() throws Exception {
        var logical = FabricRollbackWorldQueriesTest.world(); var registries = FabricRollbackTestRegistry.simulationRegistries();
        var rows = new StringBuilder();
        assertEquals("minecraft:overworld", seed().dimensionType());
        for (String key : List.of("overworld", "the_nether", "the_end")) for (long day : new long[]{0, 18_000}) for (int wet = 0; wet < 2; wet++) {
            var conditions = logical.conditions(); logical.conditions(new RollbackWorld.Conditions(day, day, conditions.difficulty(), wet != 0, conditions.loadedChunks()));
            var data = new RollbackEnvironmentData("minecraft:" + key, key.equals("overworld"), new RollbackEnvironmentData.Weather(wet == 0 ? 0 : .6F, wet == 0 ? 0 : .2F));
            var actual = new FabricRollbackEnvironment(logical, registries, data);
            var interpolation = actual.interpolation(Map.of("minecraft:desert", .25, "minecraft:soul_sand_valley", .75));
            for (var attribute : Registries.ENVIRONMENTAL_ATTRIBUTE) for (int interpolated = 0; interpolated < 2; interpolated++) {
                Object value = actual.getAttributeValue(attribute, POSITION, interpolated == 0 ? null : interpolation);
                String scalar = scalar(value);
                if (scalar != null) rows.append(key).append(' ').append(day).append(' ').append(wet).append(' ').append(interpolated).append(' ')
                        .append(Registries.ENVIRONMENTAL_ATTRIBUTE.getId(attribute)).append(' ').append(scalar).append('\n');
            }
        }
        var sorted = rows.toString().lines().sorted().collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/environment-values.txt"))) {
            assertEquals(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"), sorted);
        }
    }
    @Test void timeWeatherNoiseBiomeAndNativeCachesRestoreTogether() {
        var logical = FabricRollbackWorldQueriesTest.world(); var env = new FabricRollbackEnvironment(logical, FabricRollbackTestRegistry.simulationRegistries(), seed());
        var fog = attribute("visual/fog_color"); var sky = attribute("gameplay/sky_light_level");
        var before = env.getAttributeValue(fog, POSITION, null); var light = env.getAttributeValue(sky, POSITION, null);
        var saved = new RollbackStateGraph(value -> false, field -> true, 100_000).capture(List.of(env), List.of());
        var conditions = logical.conditions(); logical.conditions(new RollbackWorld.Conditions(18_000, 18_000, conditions.difficulty(), true, conditions.loadedChunks()));
        env.weather(new RollbackEnvironmentData.Weather(.6F, .2F));
        assertNotEquals(light, env.getAttributeValue(sky, POSITION, null));
        var position = new RollbackBlockStore.Position(0, 0, 0); var cell = logical.terrain().cell(position);
        logical.terrain().replace(position, new RollbackBlockStore.Cell(cell.data(), null, cell.biome(), cell.biomeKey(), "minecraft:soul_sand_valley", cell.light(), cell.temperature(), cell.humidity()), false);
        assertNotEquals(before, env.getAttributeValue(fog, POSITION, null));
        saved.restore(); assertEquals(before, env.getAttributeValue(fog, POSITION, null));
        assertEquals(light, env.getAttributeValue(sky, POSITION, null)); assertEquals(seed().weather(), env.weather());
    }
    @Test void foreignWeightsRegionsThreadsAndCheckpointsReject() {
        var logical = FabricRollbackWorldQueriesTest.world(); var registries = FabricRollbackTestRegistry.simulationRegistries();
        var env = new FabricRollbackEnvironment(logical, registries, seed()); var other = new FabricRollbackEnvironment(logical, registries, seed());
        var fog = attribute("visual/fog_color");
        assertThrows(IllegalArgumentException.class, () -> env.getAttributeValue(fog, POSITION, new WeightedAttributeList()));
        assertThrows(IllegalArgumentException.class, () -> env.getAttributeValue(fog, POSITION, other.interpolation(Map.of())));
        assertThrows(IllegalArgumentException.class, () -> env.restoreRollbackState(other.captureRollbackState()));
        assertThrows(IllegalArgumentException.class, () -> env.interpolation(Map.of("minecraft:desert", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> env.interpolation(Map.of("minecraft:desert", 0.0, "minecraft:soul_sand_valley", 0.0)));
        assertThrows(IllegalArgumentException.class, () -> env.interpolation(Map.of("missing:biome", 1.0)));
        assertThrows(IllegalStateException.class, () -> env.getAttributeValue(fog, new Vec3d(6, 0, 0), null));
        assertThrows(IllegalArgumentException.class, () -> env.getAttributeValue(fog, new Vec3d(Double.NaN, 0, 0), null));
        assertThrows(UnsupportedOperationException.class, () -> env.interpolation(Map.of()).clear());
        var failure = assertThrows(CompletionException.class, () -> CompletableFuture.supplyAsync(env::weather).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }
    private static EnvironmentAttribute<?> attribute(String path) { return Objects.requireNonNull(Registries.ENVIRONMENTAL_ATTRIBUTE.get(Identifier.of("minecraft", path))); }
    private static String scalar(Object value) {
        if (value instanceof Boolean || value instanceof Integer) return value.toString();
        if (value instanceof Float number) return Float.toHexString(number);
        if (value instanceof Double number) return Double.toHexString(number);
        return null;
    }
}
