package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.firebending.FireBlast;
import com.projectkorra.projectkorra.hooks.RegionProtectionHook;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.AbilityRemovalSync;
import com.projectkorra.projectkorra.prediction.authority.PredictionServices;
import com.projectkorra.projectkorra.prediction.movement.VelocitySync;
import com.projectkorra.projectkorra.prediction.rollback.*;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackFireBlastTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void casterRendersFireBlastDamageAndKnockbackBeforeConfirmationAndRewindsTheHit() throws Exception {
        var data = new FabricRollbackNativeSceneTest().bootstrapData();
        var duel = FabricRollbackNativeScene.importDuel(data, FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var roster = duel.scene().roster();
        var caster = duel.views().players().get(FabricRollbackPlayerRendererTest.A);
        var victim = duel.views().players().get(FabricRollbackPlayerRendererTest.B);
        var target = roster.players().get(victim.getUniqueId());
        var body = target.ownedPlayer();
        duel.actors().nativeEntity(caster).setPosition(-3.5, 4, .5);
        body.setPosition(.5, 4, .5);
        body.resetPosition();
        body.setVelocity(Vec3d.ZERO);
        body.setNoGravity(true);
        body.hurtTime = body.timeUntilRegen = 0;
        body.setHealth(20);
        var origin = body.getEntityPos();
        var ordinary = FabricRollbackPlayerRendererTest.roster();
        var tracked = ordinary.players().get(victim.getUniqueId()).ownedPlayer();
        var trackedPosition = tracked.getEntityPos();

        var bus = new RollbackEventBus(256, 16);
        var platform = (ProjectKorraPlatform) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "events" -> bus;
                    case "materials" -> data.materials();
                    case "tags" -> data.tags();
                    default -> throw new AssertionError(method);
                });
        var previous = ConfigManager.defaultConfig;
        try {
            var config = new TestConfig(new RollbackConfiguration.Settings("fireblast-test",
                    Map.of("Properties.DamageMultiplier", 1D), Map.of(), true));
            ConfigManager.defaultConfig = config;
            var sources = Map.<String, Config>of("core", config);
            var hooks = PredictionServices.builder().disable(RegionProtectionHook.class)
                    .disable(AbilityRemovalSync.Listener.class).disable(VelocitySync.Listener.class).build();
            var globals = new RollbackAbilityGlobals();
            var registries = new RollbackPrivateRegistries();
            Set<Object> playerViews = Collections.newSetFromMap(new IdentityHashMap<>());
            playerViews.add(caster);
            playerViews.add(victim);
            var graph = new RollbackStateGraph(value -> playerViews.contains(value)
                    || Proxy.isProxyClass(value.getClass()), field -> true, 500_000);
            var domain = RollbackDomain.create(graph, List.of(), List.of(registries), platform, duel.items(), hooks,
                    RollbackConfiguration.prepare(RollbackConfiguration.captureData(sources), sources), globals::install);
            domain.call(() -> {
                var blast = contact(caster, victim, registries);
                var execution = new FabricRollbackExecution<>(duel.views().players().values(), new FabricRollbackPhases(duel), duel.output());
                var sim = new RollbackSimulation<RollbackStateGraph.Snapshot, RollbackPlayerInput, Object>() {
                    public RollbackStateGraph.Snapshot snapshot() { return graph.capture(List.of(duel.scene(), duel.round(), globals), List.of()); }
                    public void restore(RollbackStateGraph.Snapshot state) { state.restore(); }
                    public RollbackPlayerInput predict(UUID id, RollbackPlayerInput last) { return last.predict(); }
                    public void step(long tick, Map<UUID, RollbackPlayerInput> inputs, RollbackStep<Object> output) {
                        duel.round().beginTick(tick);
                        execution.begin(output);
                        try {
                            if (tick == 1 && inputs.get(caster.getUniqueId()).sprinting()) blast.run();
                            target.tick();
                        } finally { execution.end(); }
                    }
                };
                var idle = new RollbackPlayerInput(new RollbackMovementInput(0, 0, false, 0, 0), false, List.of());
                var engine = RollbackEngine.replica(sim, Map.of(caster.getUniqueId(), idle, victim.getUniqueId(), idle), RollbackDuelRuntime.ENGINE, 0, 0);
                var sink = new RollbackClientRuntime.Output<RollbackStateGraph.Snapshot, Object>() {
                    public void update(RollbackEngine.Update<RollbackStateGraph.Snapshot, RollbackPlayerInput, Object> update) { }
                    public void stop(RollbackStartServerEndpoint.Failure failure) { }
                };
                try (var owner = new FabricRollbackPlayerRenderer(data.session(), ordinary.world().world(), data.sides().keySet(),
                        () -> true, () -> Vec3d.ZERO, ignored -> 0); var extraction = FabricRollbackPlayerRenderer.extracting()) {
                    var presentation = new FabricRollbackPresentation<>(roster, caster.getUniqueId(), engine, sink, owner);
                    var model = new ObjenesisStd().newInstance(FabricRollbackPlayerRendererTest.ProbeRenderer.class);
                    engine.submit(caster.getUniqueId(), 1, new RollbackPlayerInput(idle.movement(), true, List.of()));
                    for (int tick = 1; tick <= 2; tick++) {
                        presentation.update(engine.advance());
                        var state = model.getAndUpdateRenderState(tracked, 1);
                        assertTrue(state.hurt);
                        assertTrue(state.x > origin.x + .5 * tick);
                        assertEquals(body.getX(), state.x);
                        assertTrue(body.getHealth() < 20);
                    }
                    engine.correct(caster.getUniqueId(), 1, idle);
                    presentation.update(engine.reconcile());
                    var corrected = model.getAndUpdateRenderState(tracked, 1);
                    assertFalse(corrected.hurt);
                    assertEquals(origin.x, corrected.x);
                    assertEquals(20, body.getHealth());
                    assertEquals(trackedPosition, tracked.getEntityPos());
                }
                return null;
            });
        } finally { ConfigManager.defaultConfig = previous; }
    }

    /** Enter the ability at its contact boundary, with the loaded ability's private attribute registry. */
    @SuppressWarnings("unchecked")
    private static Runnable contact(Entity caster, Entity victim, RollbackPrivateRegistries registries) {
        try {
            var blast = new ObjenesisStd().newInstance(FireBlast.class);
            var owner = CoreAbility.class.getDeclaredField("player");
            owner.setAccessible(true);
            owner.set(blast, caster);
            blast.setDirection(new Vector(1, 0, 0));
            blast.setPushFactor(1);
            blast.setDamage(4);
            var attributes = CoreAbility.PrivateRegistries.class.getDeclaredField("attributes");
            attributes.setAccessible(true);
            ((Map<Class<?>, Object>) attributes.get(registries.abilities())).put(FireBlast.class, Map.of());
            var hit = FireBlast.class.getDeclaredMethod("affect", Entity.class);
            hit.setAccessible(true);
            return () -> {
                try { hit.invoke(blast, victim); }
                catch (InvocationTargetException e) { throw new AssertionError(e.getCause()); }
                catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            };
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static final class TestConfig extends Config {
        TestConfig(RollbackConfiguration.Settings settings) { super(settings); }
        @Override public Config createRollbackView(RollbackConfiguration.Settings settings) { return new TestConfig(settings); }
    }
}
