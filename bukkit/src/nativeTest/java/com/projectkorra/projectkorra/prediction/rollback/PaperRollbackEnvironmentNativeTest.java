package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.*;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackEnvironmentNativeTest {
    private static final Vec3 POSITION = new Vec3(.5, .5, .5);
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }
    static RegistryAccess.Frozen registries() { return (RegistryAccess.Frozen) new PaperRollbackDamageNativeTest.Combat().registryAccess(); }
    static RollbackEnvironmentData seed() { return new RollbackEnvironmentData("minecraft:overworld", true, new RollbackEnvironmentData.Weather(0, 0)); }

    @Test void nativeDefaultLayersSupplyTheCrossLoaderEnvironmentReference() throws Exception {
        onTickThread(() -> {
            var logical = PaperRollbackWorldQueriesNativeTest.world(); var registries = registries(); var rows = new StringBuilder();
            for (String key : List.of("overworld", "the_nether", "the_end")) for (long day : new long[]{0, 18_000}) for (int wet = 0; wet < 2; wet++) {
                var conditions = logical.conditions(); logical.conditions(new RollbackWorld.Conditions(day, day, conditions.difficulty(), wet != 0, conditions.loadedChunks()));
                var seed = new RollbackEnvironmentData("minecraft:" + key, key.equals("overworld"), new RollbackEnvironmentData.Weather(wet == 0 ? 0 : .6F, wet == 0 ? 0 : .2F));
                var source = source(logical, registries, seed); var captured = PaperRollbackEnvironment.capture(source); assertEquals(seed, captured);
                var actual = new PaperRollbackEnvironment(logical, registries, captured);
                var expected = EnvironmentAttributeSystem.builder().addDefaultLayers(source).build();
                var interpolation = actual.interpolation(Map.of("minecraft:desert", .25, "minecraft:soul_sand_valley", .75));
                var referenceInterpolation = new SpatialAttributeInterpolator();
                new TreeMap<>(Map.of("minecraft:desert", .25, "minecraft:soul_sand_valley", .75)).forEach((biome, weight) ->
                        referenceInterpolation.accumulate(weight, registries.lookupOrThrow(Registries.BIOME).get(Identifier.parse(biome)).orElseThrow().value().getAttributes()));
                for (var attribute : BuiltInRegistries.ENVIRONMENT_ATTRIBUTE) for (int interpolated = 0; interpolated < 2; interpolated++) {
                    Object value = actual.getValue(attribute, POSITION, interpolated == 0 ? null : interpolation);
                    assertEquals(expected.getValue(attribute, POSITION, interpolated == 0 ? null : referenceInterpolation), value, attribute::toString);
                    String scalar = scalar(value);
                    if (scalar != null) rows.append(key).append(' ').append(day).append(' ').append(wet).append(' ').append(interpolated).append(' ')
                            .append(BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.getKey(attribute)).append(' ').append(scalar).append('\n');
                }
            }
            var sorted = rows.toString().lines().sorted().collect(java.util.stream.Collectors.joining("\n", "", "\n"));
            try (var input = getClass().getResourceAsStream("/rollback/environment-values.txt")) {
                assertNotNull(input, "Current Paper environment values:\n" + sorted);
                assertEquals(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"), sorted, "Current Paper environment values:\n" + sorted);
            }
            var encoded = Base64.getEncoder().encodeToString(seed().encode());
            try (var input = getClass().getResourceAsStream("/rollback/environment.base64")) {
                assertNotNull(input, "Current Paper environment seed: " + encoded);
                assertArrayEquals(Base64.getMimeDecoder().decode(input.readAllBytes()), seed().encode());
            }
            return null;
        });
    }
    @Test void timeWeatherNoiseBiomeAndNativeCachesRestoreTogether() throws Exception {
        onTickThread(() -> {
            var logical = PaperRollbackWorldQueriesNativeTest.world(); var env = new PaperRollbackEnvironment(logical, registries(), seed());
            var before = env.getValue(EnvironmentAttributes.FOG_COLOR, POSITION, null);
            float light = env.getValue(EnvironmentAttributes.SKY_LIGHT_LEVEL, POSITION, null);
            var saved = new RollbackStateGraph(value -> false, field -> true, 100_000).capture(List.of(env), List.of());
            var conditions = logical.conditions(); logical.conditions(new RollbackWorld.Conditions(18_000, 18_000, conditions.difficulty(), true, conditions.loadedChunks()));
            env.weather(new RollbackEnvironmentData.Weather(.6F, .2F));
            assertNotEquals(light, env.getValue(EnvironmentAttributes.SKY_LIGHT_LEVEL, POSITION, null));
            var position = new RollbackBlockStore.Position(0, 0, 0); var cell = logical.terrain().cell(position);
            logical.terrain().replace(position, new RollbackBlockStore.Cell(cell.data(), null, cell.biome(), cell.biomeKey(), "minecraft:soul_sand_valley", cell.light(), cell.temperature(), cell.humidity()), false);
            assertNotEquals(before, env.getValue(EnvironmentAttributes.FOG_COLOR, POSITION, null));
            saved.restore(); assertEquals(before, env.getValue(EnvironmentAttributes.FOG_COLOR, POSITION, null));
            assertEquals(light, env.getValue(EnvironmentAttributes.SKY_LIGHT_LEVEL, POSITION, null)); assertEquals(seed().weather(), env.weather());
            return null;
        });
    }
    @Test void foreignWeightsRegionsThreadsAndCheckpointsReject() throws Exception {
        onTickThread(() -> {
            var logical = PaperRollbackWorldQueriesNativeTest.world(); var env = new PaperRollbackEnvironment(logical, registries(), seed());
            var other = new PaperRollbackEnvironment(logical, registries(), seed());
            assertThrows(IllegalArgumentException.class, () -> env.getValue(EnvironmentAttributes.FOG_COLOR, POSITION, new SpatialAttributeInterpolator()));
            assertThrows(IllegalArgumentException.class, () -> env.getValue(EnvironmentAttributes.FOG_COLOR, POSITION, other.interpolation(Map.of())));
            assertThrows(IllegalArgumentException.class, () -> env.restoreRollbackState(other.captureRollbackState()));
            assertThrows(IllegalArgumentException.class, () -> env.interpolation(Map.of("minecraft:desert", Double.NaN)));
            assertThrows(IllegalArgumentException.class, () -> env.interpolation(Map.of("minecraft:desert", 0.0, "minecraft:soul_sand_valley", 0.0)));
            assertThrows(IllegalArgumentException.class, () -> env.interpolation(Map.of("missing:biome", 1.0)));
            assertThrows(IllegalStateException.class, () -> env.getValue(EnvironmentAttributes.FOG_COLOR, new Vec3(6, 0, 0), null));
            assertThrows(IllegalArgumentException.class, () -> env.getValue(EnvironmentAttributes.FOG_COLOR, new Vec3(Double.NaN, 0, 0), null));
            assertThrows(UnsupportedOperationException.class, () -> env.interpolation(Map.of()).clear());
            var failure = assertThrows(CompletionException.class, () -> CompletableFuture.supplyAsync(env::weather).join());
            assertInstanceOf(IllegalStateException.class, failure.getCause()); return null;
        });
    }
    private static String scalar(Object value) {
        if (value instanceof Boolean || value instanceof Integer) return value.toString();
        if (value instanceof Float number) return Float.toHexString(number);
        if (value instanceof Double number) return Double.toHexString(number);
        return null;
    }
    private static ServerLevel source(RollbackWorld logical, RegistryAccess.Frozen registries, RollbackEnvironmentData seed) {
        var dimension = registries.lookupOrThrow(Registries.DIMENSION_TYPE).get(Identifier.parse(seed.dimensionType())).orElseThrow();
        var biomes = new BiomeManager((x, y, z) -> registries.lookupOrThrow(Registries.BIOME).get(Identifier.parse(
                logical.terrain().cell(new RollbackBlockStore.Position(x * 4, y * 4, z * 4)).noiseBiomeKey())).orElseThrow(), 0);
        return RollbackNativeQueryShell.create(ServerLevel.class).constant(ServerLevel::registryAccess, registries)
                .constant(ServerLevel::getBiomeManager, biomes).constant(ServerLevel::dimensionTypeRegistration, dimension)
                .constant(ServerLevel::dimensionType, dimension.value()).constant(ServerLevel::canHaveWeather, seed.weatherEnabled())
                .query(ServerLevel::getDayTime, 0L, args -> logical.getFullTime())
                .query(value -> value.getRainLevel(1), 0F, args -> seed.weather().rain())
                .query(value -> value.getThunderLevel(1), 0F, args -> seed.weather().thunder()).instance();
    }
}
