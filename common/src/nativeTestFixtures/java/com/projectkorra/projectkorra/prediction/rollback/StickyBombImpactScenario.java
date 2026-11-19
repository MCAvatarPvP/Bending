package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.entity.ShulkerBullet;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.platform.mc.event.EventPriority;
import com.projectkorra.projectkorra.platform.mc.event.entity.ProjectileHitEvent;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import me.literka.ChiListener;
import me.literka.abilities.StickyBomb;
import org.objenesis.ObjenesisStd;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Run the same registered Chi listener through native projectile flight and a corrected branch on both loaders. */
public final class StickyBombImpactScenario {
    private StickyBombImpactScenario() { }

    public static void verify(RollbackPlayer caster, RollbackPlayer target, ShulkerBullet shot,
                              RollbackActorRegistry actors, RollbackStepOutput<Object> output, Runnable tick,
                              boolean wall) throws ReflectiveOperationException {
        var old = ConfigManager.defaultConfig;
        StickyBomb bomb;
        try {
            ConfigManager.defaultConfig = new Config(new RollbackConfiguration.Settings("sticky-test", Map.of(), Map.of(), true)) { };
            bomb = new ObjenesisStd().newInstance(StickyBomb.class);
        } finally { ConfigManager.defaultConfig = old; }
        var playerField = CoreAbility.class.getDeclaredField("player");
        playerField.setAccessible(true); playerField.set(bomb, caster);
        for (var field : Map.of("shulkerBullet", (Object)shot, "location", shot.getLocation(), "prevLocation", shot.getLocation()).entrySet()) {
            var f = StickyBomb.class.getDeclaredField(field.getKey());
            f.setAccessible(true); f.set(bomb, field.getValue());
        }
        shot.setMetadata("stickybomb", new FixedMetadataValue(new Object(), bomb));
        var hits = new ArrayList<ProjectileHitEvent>();
        var bus = new RollbackEventBus(512, 16);
        bus.registerListener(new ChiListener());
        bus.registerListener(new Object() {
            @EventHandler(priority = EventPriority.MONITOR) public void hit(ProjectileHitEvent event) { hits.add(event); }
        });
        var platform = (ProjectKorraPlatform)Proxy.newProxyInstance(StickyBomb.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> {
                    if (method.getName().equals("events")) return bus;
                    throw new AssertionError(method);
                });
        var external = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        external.addAll(List.of(caster, target, shot, caster.getWorld()));
        var graph = new RollbackStateGraph(external::contains, field -> true, 4096);
        var domain = RollbackDomain.create(graph, List.of(), List.of(), platform, () -> { });
        var actor = actors.get(shot.getUniqueId());
        double health = target.getHealth();
        assertSame(bomb, StickyBomb.forProjectile(shot));
        var stuck = StickyBomb.class.getDeclaredField("stuck"); stuck.setAccessible(true);
        var attached = StickyBomb.class.getDeclaredField("targetEntity"); attached.setAccessible(true);
        domain.call(() -> {
            var simulation = new RollbackSimulation<Runnable, Integer, Object>() {
                public Runnable snapshot() {
                    var ability = graph.capture(List.of(bomb), List.of());
                    var body = save(actor); var facade = save(com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.logicalBody(shot));
                    var membership = save(actors);
                    var logical = save(((com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld)caster.getWorld()).entities());
                    return () -> { body.run(); facade.run(); membership.run(); logical.run(); ability.restore(); };
                }
                public void restore(Runnable state) { state.run(); }
                public Integer predict(UUID id, Integer previous) { return previous; }
                public void step(long frame, Map<UUID,Integer> inputs, RollbackStep<Object> step) {
                    try (var binding = output.open(step)) { tick.run(); }
                }
            };
            var engine = new RollbackEngine<>(simulation, Map.of(caster.getUniqueId(), 0), RollbackDuelRuntime.ENGINE, 0);
            for (int pass = 1; pass <= 2; pass++) {
                if (pass == 1) { engine.advance(); engine.advance(); }
                else { engine.submit(caster.getUniqueId(), 1, 1); assertEquals(1, engine.reconcile().replayedFrom()); }
                assertEquals(pass, hits.size());
                var contact = hits.getLast();
                assertTrue(contact.isCancelled());
                assertSame(shot, contact.getEntity());
                if (wall) assertEquals(2, contact.getHitBlock().getZ());
                else assertSame(target, contact.getHitEntity());
                assertFalse(shot.isValid());
                assertNull(StickyBomb.forProjectile(shot));
                assertEquals(health, target.getHealth());
                try { assertTrue(stuck.getBoolean(bomb)); assertSame(wall ? null : target, attached.get(bomb)); }
                catch (IllegalAccessException failure) { throw new AssertionError(failure); }
            }
            return null;
        });
    }
    private static <S> Runnable save(RollbackStateCell<S> cell) {
        S snapshot = cell.captureRollbackState();
        return () -> cell.restoreRollbackState(snapshot);
    }
}
