package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.rollback.RollbackWorldSettings;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import net.minecraft.entity.Entity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.attribute.EnvironmentAttributeAccess;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.rule.GameRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackWorldServicesTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }
    static RollbackWorldSettings settings() throws Exception {
        try (var source = Objects.requireNonNull(FabricRollbackWorldServicesTest.class.getResourceAsStream("/rollback/world-settings.base64"))) {
            return RollbackWorldSettings.decode(Base64.getMimeDecoder().decode(source.readAllBytes()));
        }
    }
    static FabricRollbackWorldServices services(RollbackWorldSettings settings, Callbacks callbacks) {
        return services(settings, callbacks.logical, callbacks);
    }
    static FabricRollbackWorldServices services(RollbackWorldSettings settings, com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld logical, Callbacks callbacks) {
        assertSame(callbacks.logical, logical);
        return new FabricRollbackWorldServices(settings, logical, callbacks.fixture.registries(), FeatureFlags.DEFAULT_ENABLED_FEATURES, callbacks, callbacks);
    }
    @Test void daylightAndTickIdentityRewindAndHonorTheCapturedTimeRule() throws Exception {
            for (boolean advances : new boolean[]{true, false}) {
                var callbacks = new Callbacks(); var seed = settings();
                var rules = new TreeMap<>(seed.rules());
                assertTrue(rules.containsKey("minecraft:advance_time"));
                rules.put("minecraft:advance_time", new RollbackWorldSettings.Flag(advances));
                var settings = new RollbackWorldSettings(seed.gameTime(), seed.randomSeed(), seed.soundSeed(), seed.seaLevel(), seed.difficulty(), seed.policy(), rules);
                var services = services(settings, callbacks);
                var before = callbacks.logical.conditions();
                callbacks.logical.conditions(new com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld.Conditions(
                        23999, 23999, before.difficulty(), before.storm(), before.loadedChunks()));
                var snapshot = new RollbackStateGraph(value -> false, field -> true, 100_000).capture(List.of(services), List.of());
                services.advanceTick(1);
                assertEquals(21, services.time());
                assertEquals(advances ? 24000 : 23999, callbacks.logical.getFullTime());
                assertEquals(advances ? 0 : 23999, callbacks.logical.getTime());
                var border = callbacks.border.data();
                services.advanceTick(1);
                assertEquals(21, services.time()); assertEquals(border, callbacks.border.data());
                assertThrows(IllegalStateException.class, () -> services.advanceTick(3));
                services.advanceTick(2);
                assertThrows(IllegalStateException.class, () -> services.advanceTick(1));
                snapshot.restore();
                assertEquals(23999, callbacks.logical.getFullTime());
                services.advanceTick(1);
                assertEquals(21, services.time()); assertEquals(border, callbacks.border.data());
                assertEquals(advances ? 24000 : 23999, callbacks.logical.getFullTime());
            }
    }
    @Test void paperRulesSeedPrivateNativeQueriesAndReplayTheSameRandomStreams() throws Exception {
        var callbacks = new Callbacks(); var data = settings(); var services = services(data, callbacks);
        assertFalse(services.gameRule(GameRules.FIRE_DAMAGE)); assertEquals(9, services.gameRule(GameRules.MAX_ENTITY_CRAMMING));
        assertEquals(data, services.settings()); assertEquals(20, services.time()); assertEquals(63, services.seaLevel());
        var snapshot = new RollbackStateGraph(value -> false, field -> true, 100_000).capture(List.of(services), List.of());
        services.advanceTick(); var actual = List.of(services.random().nextLong(), services.random().nextDouble(), services.random().nextGaussian(), services.nextSoundSeed());
        try (var source = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/world-random.txt"))) {
            assertEquals(new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim(), actual.toString());
        }
        services.output(new FabricRollbackWorldAccess.StatusOutput(new UUID(0, 1), (byte) 2)); assertEquals(1, callbacks.fixture.outputs.size());
        snapshot.restore(); assertEquals(20, services.time()); assertTrue(callbacks.fixture.outputs.isEmpty());
        assertEquals(actual, List.of(services.random().nextLong(), services.random().nextDouble(), services.random().nextGaussian(), services.nextSoundSeed()));
    }
    @Test void missingUnknownAndWrongTypedRulesForeignCheckpointsAndThreadsReject() throws Exception {
        var data = settings(); var callbacks = new Callbacks(); var services = services(data, callbacks);
        for (int mode = 0; mode < 3; mode++) {
            var rules = new TreeMap<>(data.rules());
            if (mode == 0) rules.remove("minecraft:fire_damage");
            if (mode == 1) rules.put("test:unknown", new RollbackWorldSettings.Flag(true));
            if (mode == 2) rules.put("minecraft:fire_damage", new RollbackWorldSettings.IntegerRule(1));
            var invalid = new RollbackWorldSettings(data.gameTime(), data.randomSeed(), data.soundSeed(), data.seaLevel(), data.difficulty(), data.policy(), rules);
            assertThrows(IllegalArgumentException.class, () -> services(invalid, callbacks));
        }
        assertThrows(IllegalArgumentException.class, () -> services.restoreRollbackState(services(data, new Callbacks()).captureRollbackState()));
        var failure = assertThrows(CompletionException.class, () -> CompletableFuture.supplyAsync(services::time).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }
    static final class Callbacks implements FabricRollbackWorldServices.Spatial<Void>, FabricRollbackWorldServices.Events<Void> {
        final FabricRollbackWorldAccessTest.Queries fixture = new FabricRollbackWorldAccessTest.Queries();
        final com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld logical;
        final FabricRollbackEnvironment environment;
        final FabricRollbackBorder border;
        Callbacks() { this(FabricRollbackWorldQueriesTest.world()); }
        Callbacks(com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld logical) {
            this.logical = logical;
            environment = new FabricRollbackEnvironment(logical, fixture.registries(), FabricRollbackEnvironmentTest.seed());
            var nativeBorder = fixture.border;
            border = new FabricRollbackBorder(new com.projectkorra.projectkorra.prediction.rollback.RollbackBorderData(nativeBorder.getCenterX(), nativeBorder.getCenterZ(),
                    nativeBorder.getMaxRadius(), nativeBorder.getDamagePerBlock(), nativeBorder.getSafeZone(), nativeBorder.getWarningBlocks(), nativeBorder.getWarningTime(), nativeBorder.getSize(), null));
        }
        @Override public void advanceTick(long tick) { border.advanceTick(tick); }
        @Override public WorldBorder border() { return border; }
        @Override public Scoreboard scoreboard() { return fixture.scoreboard; }
        @Override public EnvironmentAttributeAccess environmentAttributes() { return environment; }
        @Override public boolean skyVisible(RollbackBlockStore terrain, BlockPos position) { terrain.cell(new RollbackBlockStore.Position(position.getX(), position.getY(), position.getZ())); return fixture.skyVisible; }
        @Override public void gameEvent(RegistryEntry<GameEvent> event, Vec3d position, GameEvent.Emitter emitter) { fixture.gameEvent(event, position, emitter); }
        @Override public void waypoint(FabricRollbackWorldAccess.WaypointAction action, Entity entity) { fixture.waypoint(action, entity); }
        @Override public void output(FabricRollbackWorldAccess.Output output) { fixture.output(output); }
        @Override public boolean flightAllowed(java.util.UUID player, boolean flying, boolean cancelled) { return fixture.flightAllowed(player, flying, cancelled); }
        @Override public boolean glideAllowed(java.util.UUID player, boolean gliding, boolean cancelled) { return fixture.glideAllowed(player, gliding, cancelled); }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void state) { }
        @Override public List<?> rollbackReferences() { return List.of(fixture, environment, border); }
    }
}
