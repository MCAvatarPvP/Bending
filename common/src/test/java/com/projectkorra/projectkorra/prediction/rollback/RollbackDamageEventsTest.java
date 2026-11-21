package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RollbackDamageEventsTest {
    @TempDir Path directory;

    private static final class Shield {
        int intercepted;
        @EventHandler public void hit(EntityDamageEvent damage) {
            intercepted++;
            damage.setDamage(1);
            damage.setCancelled(true);
        }
    }

    @Test void privateBendingListenerRunsBeforeLethalDamageAndRewindsWithTheDuel() {
        var world = RollbackWorldTest.world(Map.of());
        var victim = PrivateCombatRollbackTest.player(world, 1);
        var attacker = PrivateCombatRollbackTest.player(world, 2);
        world.entities().add(victim);
        world.entities().add(attacker);
        var players = Map.of(victim.getUniqueId(), victim, attacker.getUniqueId(), attacker);
        var platform = RollbackPlatformTest.platform(directory, world, List.of(victim, attacker), new RollbackScheduler(10, 10));
        var round = new RollbackRound(new UUID(1, 3), Map.of(victim.getUniqueId(), victim.getUniqueId(),
                attacker.getUniqueId(), attacker.getUniqueId()));
        var listener = new Shield();
        var graph = new RollbackStateGraph(value -> Proxy.isProxyClass(value.getClass()), field -> true, 20_000);
        var domain = RollbackDomain.create(graph, List.of(), List.of(round), platform, () -> platform.events().registerListener(listener));
        domain.call(() -> {
            var checkpoint = domain.capture();
            round.beginTick(1);
            try (var clock = RollbackClock.at(0, 1, 50_000_000); var scope = new RollbackControlEvents(players)) {
                var hit = new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.CUSTOM, 40);
                RollbackDamageEvents.dispatch(hit, attacker, null);
                assertTrue(hit.isCancelled());
                assertEquals(1, hit.getDamage());
                assertEquals(RollbackRound.DamageResult.CANCEL,
                        round.damage(victim.getUniqueId(), attacker.getUniqueId(), 20, hit.getDamage(), false, hit.isCancelled()));
                assertTrue(round.provisionalDefeats().isEmpty());
                assertEquals(1, listener.intercepted);
            }
            domain.restore(checkpoint);
            assertEquals(0, listener.intercepted);
            assertEquals(0, round.tick());
            return null;
        });
    }
}
