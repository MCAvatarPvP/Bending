package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.OfflineBendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.hit.HitRegistrationPolicy;
import com.projectkorra.projectkorra.util.AbilityLagCompensator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RollbackHitRegistrationTest {
    @Test void sharedLagCompensatorUsesOnlyCurrentSimulationColliderAndKeepsNormalGameplayRewind() {
        World world = new World();
        Player live = new Player() {
            @Override public UUID getUniqueId() { return new UUID(0, 1); }
            @Override public int getPing() { return 100; }
            @Override public World getWorld() { return world; }
            @Override public BoundingBox getBoundingBox() { return new BoundingBox(new Vector(-0.3, 0, -0.3), new Vector(0.3, 1.8, 0.3)); }
        };
        var player = PrivateCombatRollbackTest.player(world, 1);
        assertEquals(100, player.state().profile().ping());
        assertEquals(0, player.getPing());
        var liveHits = new ArrayList<Double>();
        var privateHits = new ArrayList<Double>();
        var normal = new AbilityLagCompensator((target, snapshot) -> liveHits.add(snapshot.getLocation().getX()));
        var replay = new AbilityLagCompensator((target, snapshot) -> privateHits.add(snapshot.getLocation().getX()));
        var graph = new RollbackStateGraph(value -> value instanceof World, ignored -> true, 10_000);
        var shared = RollbackStateGraph.staticFields(OfflineBendingPlayer.class, field -> field.getName().equals("ONLINE_PLAYERS"));
        var outside = graph.capture(List.of(), shared);
        try {
            BendingPlayer.getPlayers().put(live.getUniqueId(), new BendingPlayer(live));
            normal.addPlayer(live);
            normal.addSnapshot(new Location(world, 0, 0, 0), 1);
            normal.update();
            normal.addSnapshot(new Location(world, 10, 0, 0), 1);
            normal.update();
            assertEquals(List.of(0.0, 0.0), liveHits, "normal mode still accepts its prior collider");

            var domain = RollbackDomain.create(graph, shared, List.of(player, replay, privateHits), platform(),
                    () -> BendingPlayer.getPlayers().put(player.getUniqueId(), new BendingPlayer(player)));
            domain.call(() -> {
                replay.addPlayer(player);
                replay.addSnapshot(new Location(world, 0, 0, 0), 1);
                replay.update();
                var beforeMove = domain.capture();
                replay.addSnapshot(new Location(world, 10, 0, 0), 1);
                replay.update();
                assertEquals(List.of(0.0), privateHits, "old overlapping collider must not resurrect a replayed miss");
                domain.restore(beforeMove);
                replay.addSnapshot(new Location(world, 10, 0, 0), 1);
                replay.update();
                assertEquals(List.of(0.0), privateHits);
                replay.addPlayer(player);
                replay.addSnapshot(new Location(world, 0.5, 0, 0), 1);
                replay.update();
                assertEquals(List.of(0.0, 0.5), privateHits, "current contact still runs the ability callback");
                return null;
            });
            assertEquals(100, live.getPing());
            assertSame(live, BendingPlayer.getBendingPlayer(live).getPlayer());
        } finally { outside.restore(); }
    }

    @Test void replayHitAuthorityIsIndependentOfElementAndLegacyClientFlag() {
        var domain = RollbackDomain.create(new RollbackStateGraph(value -> false, field -> true, 100), List.of(), List.of(), platform(), () -> { });
        for (Element element : List.of(Element.AIR, Element.FIRE, Element.WATER, Element.EARTH, Element.CHI, Element.AVATAR)) {
            var ability = new TestAbility(element);
            HitRegistrationPolicy previous = HitRegistrationPolicy.forTarget(ability, false);
            domain.call(() -> {
                assertEquals(HitRegistrationPolicy.SIMULATION_CURRENT, HitRegistrationPolicy.forAbility(ability));
                assertEquals(HitRegistrationPolicy.SIMULATION_CURRENT, HitRegistrationPolicy.forTarget(ability, true));
                assertEquals(HitRegistrationPolicy.SIMULATION_CURRENT, HitRegistrationPolicy.forTarget(ability, false));
                assertTrue(HitRegistrationPolicy.includePredictedEntity(ability, new Player()));
                return null;
            });
            assertEquals(previous, HitRegistrationPolicy.forTarget(ability, false));
            assertEquals(HitRegistrationPolicy.REWIND_ASSISTED, HitRegistrationPolicy.forTarget(ability, true));
        }
    }

    private static ProjectKorraPlatform platform() {
        return (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> { throw new AssertionError(method); });
    }
    private static final class TestAbility extends CoreAbility {
        final Element element;
        TestAbility(Element element) { super(null); this.element = element; }
        @Override public Element getElement() { return element; }
        @Override public void progress() { }
        @Override public boolean isSneakAbility() { return false; }
        @Override public boolean isHarmlessAbility() { return false; }
        @Override public boolean isIgniteAbility() { return false; }
        @Override public boolean isExplosiveAbility() { return false; }
        @Override public long getCooldown() { return 0; }
        @Override public String getName() { return "ReplayContact"; }
        @Override public Location getLocation() { return null; }
    }
}
