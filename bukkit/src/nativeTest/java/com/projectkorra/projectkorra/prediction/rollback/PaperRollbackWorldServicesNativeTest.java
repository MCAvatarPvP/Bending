package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackWorldServicesNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }
    static RollbackWorldSettings settings() {
        var rules = new GameRules(FeatureFlags.DEFAULT_FLAGS);
        rules.set(GameRules.FIRE_DAMAGE, false, null); rules.set(GameRules.MAX_ENTITY_CRAMMING, 9, null);
        var policy = new RollbackWorldSettings.Policy(RollbackWorldSettings.Dimension.NORMAL, true, false, true, false, true, 5,
                .15F, .3F, 7, 3, false, true, 3.5F, -32.5, OptionalInt.of(125));
        return PaperRollbackWorldSettings.capture(rules, 20, 57, 53, 63, RollbackWorldSettings.Difficulty.HARD, policy);
    }
    @Test void daylightAndTickIdentityRewindAndHonorTheCapturedTimeRule() throws Exception {
        onTickThread(() -> {
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
            return null;
        });
    }
    @Test void capturedNativeRulesMatchThePortableReference() throws Exception {
        onTickThread(() -> {
            var data = settings(); var encoded = Base64.getEncoder().encodeToString(data.encode());
            try (var source = getClass().getResourceAsStream("/rollback/world-settings.base64")) {
                assertNotNull(source, "Current Paper world settings fixture: " + encoded);
                assertArrayEquals(Base64.getMimeDecoder().decode(source.readAllBytes()), data.encode(), "Current Paper world settings fixture: " + encoded);
            }
            assertEquals(data, RollbackWorldSettings.decode(data.encode())); return null;
        });
    }
    @Test void nativeServicesUseCapturedRulesAndRestoreClockRandomnessAndOutputs() throws Exception {
        onTickThread(() -> {
            var callbacks = new Callbacks(); var services = services(settings(), callbacks); var other = services(settings(), new Callbacks());
            assertEquals(false, services.rule(GameRules.FIRE_DAMAGE)); assertEquals(9, services.rule(GameRules.MAX_ENTITY_CRAMMING));
            assertEquals(net.minecraft.world.Difficulty.HARD, services.difficulty()); assertEquals(.15F, services.jumpExhaustion(false));
            assertEquals(125, services.worldPolicy().netherCeilingHeight().orElseThrow()); assertEquals(3, services.containerUpdateRate());
            var snapshot = new RollbackStateGraph(value -> false, field -> true, 100_000).capture(List.of(services), List.of());
            services.advanceTick(); var actual = List.of(services.random().nextLong(), services.random().nextDouble(), services.random().nextGaussian(), services.nextSoundSeed());
            try (var source = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/world-random.txt"))) {
                assertEquals(new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim(), actual.toString());
            }
            services.output(new PaperRollbackCombatAccess.StatusOutput(new UUID(0, 1), (byte) 2)); assertEquals(1, callbacks.combat.outputs.size());
            snapshot.restore(); assertEquals(20, services.time()); assertTrue(callbacks.combat.outputs.isEmpty());
            assertEquals(actual, List.of(services.random().nextLong(), services.random().nextDouble(), services.random().nextGaussian(), services.nextSoundSeed()));
            assertThrows(IllegalArgumentException.class, () -> other.restoreRollbackState(services.captureRollbackState()));
            var failure = assertThrows(CompletionException.class, () -> CompletableFuture.supplyAsync(services::time).join());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            var invalid = new TreeMap<>(settings().rules()); invalid.remove("minecraft:fire_damage");
            var seed = settings();
            assertThrows(IllegalArgumentException.class, () -> services(new RollbackWorldSettings(seed.gameTime(), seed.randomSeed(), seed.soundSeed(), seed.seaLevel(), seed.difficulty(), seed.policy(), invalid), callbacks));
            return null;
        });
    }
    static PaperRollbackWorldServices services(RollbackWorldSettings settings, Callbacks callbacks) {
        return services(settings, callbacks.logical, callbacks);
    }
    static PaperRollbackWorldServices services(RollbackWorldSettings settings, com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld logical, Callbacks callbacks) {
        assertSame(callbacks.logical, logical);
        return new PaperRollbackWorldServices(settings, logical, (RegistryAccess.Frozen) callbacks.combat.registryAccess(), FeatureFlags.DEFAULT_FLAGS, callbacks, callbacks);
    }
    static final class Callbacks implements PaperRollbackWorldServices.Spatial<Void>, PaperRollbackWorldServices.Events<Void> {
        final PaperRollbackDamageNativeTest.Combat combat = new PaperRollbackDamageNativeTest.Combat();
        final PaperRollbackWorldAccessNativeTest.Queries spatial = new PaperRollbackWorldAccessNativeTest.Queries();
        final com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld logical;
        final PaperRollbackEnvironment environment;
        final PaperRollbackBorder border;
        Callbacks() { this(PaperRollbackWorldQueriesNativeTest.world()); }
        Callbacks(com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld logical) {
            this.logical = logical;
            environment = new PaperRollbackEnvironment(logical, (RegistryAccess.Frozen) combat.registryAccess(), PaperRollbackEnvironmentNativeTest.seed());
            border = new PaperRollbackBorder(PaperRollbackBorder.capture(spatial.border));
        }
        @Override public void advanceTick(long tick) { border.advanceTick(tick); }
        @Override public WorldBorder border() { return border; }
        @Override public EnvironmentAttributeReader environmentAttributes() { return environment; }
        @Override public boolean skyVisible(RollbackBlockStore terrain, BlockPos position) { terrain.cell(new RollbackBlockStore.Position(position.getX(), position.getY(), position.getZ())); return true; }
        @Override public void event(org.bukkit.event.Event event) { combat.event(event); }
        @Override public void gameEvent(Object event, Object position, Object context) { combat.gameEvent(event, position, context); }
        @Override public void output(PaperRollbackCombatAccess.Output output) { combat.output(output); }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void state) { }
        @Override public List<?> rollbackReferences() { return List.of(combat, spatial, environment, border); }
    }
}
