package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.*;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.*;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.FlightHandler;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackAbilityIdsTest {
    @Test @SuppressWarnings("unchecked") void liveAndReplayAllocationStayDisjointThroughImportRewindAndExport() throws Exception {
        var graph = new RollbackStateGraph(value -> value instanceof Player || value instanceof FlightHandler, field -> true, 10000);
        var shared = RollbackBendingState.sharedFields(); var outside = graph.capture(List.of(), shared);
        var field = Manager.class.getDeclaredField("MANAGERS"); field.setAccessible(true);
        var managers = (Map<Class<?>, Manager>) field.get(null);
        var scheduler = new RollbackLiveSchedulerTest.Backend();
        var platform = (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> {
                    if (method.getName().equals("scheduler")) return scheduler;
                    throw new AssertionError(method);
                });
        try (var scope = Platform.using(platform)) {
            managers.put(FlightHandler.class, new ObjenesisStd().newInstance(FlightHandler.class));
            Player player = new Player() { @Override public UUID getUniqueId() { return new UUID(0, 91); } };
            int baseline = new Ability(player).getId();
            var reservation = CoreAbility.reserveRollbackIds(Set.of(player.getUniqueId()), 2);
            var registry = CoreAbility.captureRollbackRegistry(Set.of(player.getUniqueId()), reservation);
            assertEquals(baseline + 3, new Ability(player).getId());
            assertThrows(IllegalArgumentException.class, () -> CoreAbility.captureRollbackRegistry(Set.of(new UUID(0, 92)), reservation));
            var domain = RollbackDomain.create(graph, shared, List.of(), platform, registry::install);
            domain.call(() -> {
                var before = domain.capture();
                assertEquals(baseline + 1, new Ability(player).getId());
                assertEquals(baseline + 2, new Ability(player).getId());
                assertThrows(IllegalStateException.class, () -> new Ability(player));
                domain.restore(before);
                assertEquals(baseline + 1, new Ability(player).getId());
                var exported = CoreAbility.exportRollbackRegistry(Set.of(player.getUniqueId()));
                assertEquals(baseline + 2, exported.nextId());
                assertEquals(baseline + 3, exported.idLimit());
                // The next allocation and bound are state, not process-global counters.
                assertEquals(baseline + 2, new Ability(player).getId());
                assertThrows(IllegalStateException.class, () -> new Ability(player));
                return null;
            });
            assertEquals(baseline + 4, new Ability(player).getId());
        } finally { outside.restore(); }
    }
    static final class Ability extends CoreAbility {
        Ability(Player player) { super(player); }
        @Override public boolean isEnabled() { return true; }
        @Override public void progress() { }
        @Override public boolean isSneakAbility() { return false; }
        @Override public boolean isHarmlessAbility() { return true; }
        @Override public boolean isIgniteAbility() { return false; }
        @Override public boolean isExplosiveAbility() { return false; }
        @Override public long getCooldown() { return 0; }
        @Override public String getName() { return "IdFixture"; }
        @Override public Element getElement() { return null; }
        @Override public Location getLocation() { return null; }
    }
}
