package me.literka.abilities;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.event.AbilityVelocityAffectEntityEvent;
import com.projectkorra.projectkorra.platform.PKEventBus;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.FluidCollisionMode;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Arrow;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.RayTraceResult;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.support.AbilityWorld;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class RopeDartContactTest {
    @Test void sweepTakesTheNearestVisibleTargetAndDropsTheRetiredProjectileReference() throws Exception {
        try (var ignored = new AbilityWorld()) {
            var impulses = new ArrayList<AbilityVelocityAffectEntityEvent>();
            var ordinary = Platform.current();
            var bus = (PKEventBus) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PKEventBus.class}, (proxy, method, args) -> {
                        assertEquals("call", method.getName());
                        impulses.add((AbilityVelocityAffectEntityEvent) args[0]);
                        return null;
                    });
            Platform.install((ProjectKorraPlatform) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) ->
                            method.getName().equals("events") ? bus : method.invoke(ordinary, args)));
            var candidates = new ArrayList<Entity>();
            var wall = new double[]{3};
            World world = new World() {
                @Override public Collection<Player> getPlayers() { return java.util.List.of(); }
                @Override public Collection<Entity> getNearbyCombatEntities(BoundingBox box, Predicate<Entity> filter) {
                    return candidates.stream().filter(filter).toList();
                }
                @Override public RayTraceResult rayTraceBlocks(Location start, Vector direction, double length,
                        FluidCollisionMode mode, boolean passable) {
                    return new RayTraceResult(new Vector(wall[0], 1, 0));
                }
            };
            var hiddenId = new UUID(0, 4);
            var owner = new Player() {
                @Override public boolean canSee(Player other) { return !hiddenId.equals(other.getUniqueId()); }
                @Override public World getWorld() { return world; }
                @Override public UUID getUniqueId() { return new UUID(0, 9); }
                @Override public Location getLocation() { return new Location(world, 0, 0, 0); }
            };
            class Target extends Player {
                double x;
                final UUID id;
                Target(double x, int id) { this.x = x; this.id = new UUID(0, id); }
                @Override public UUID getUniqueId() { return id; }
                @Override public World getWorld() { return world; }
                @Override public Location getLocation() { return new Location(world, x, 0, 0); }
                @Override public double getEyeHeight() { return 1.6; }
                @Override public BoundingBox getCombatBoundingBox() {
                    return new BoundingBox(new Vector(x - .3, 0, -.3), new Vector(x + .3, 1.8, .3));
                }
            }
            var far = new Target(8, 3); var tied = new Target(5, 2); var near = new Target(5, 1);
            candidates.add(new Target(1, 4));
            candidates.add(far); candidates.add(tied); candidates.add(near);
            boolean[] removed = {false};
            var projectile = new Arrow() {
                @Override public World getWorld() { return world; }
                @Override public void remove() { assertFalse(removed[0]); removed[0] = true; }
                @Override public Location getLocation() {
                    assertFalse(removed[0], "attached rope read the retired projectile");
                    return new Location(world, 10, 1, 0);
                }
            };
            var dart = new ObjenesisStd().newInstance(RopeDart.class);
            var player = CoreAbility.class.getDeclaredField("player"); player.setAccessible(true); player.set(dart, owner);
            var arrow = RopeDart.class.getDeclaredField("arrow"); arrow.setAccessible(true); arrow.set(dart, projectile);
            var sweep = RopeDart.class.getDeclaredMethod("findTargetAlongPath", Location.class, Location.class);
            sweep.setAccessible(true);
            var from = new Location(world, 0, 1, 0); var to = new Location(world, 10, 1, 0);
            sweep.invoke(dart, from, to);
            assertFalse(removed[0], "the caster cannot see the first player and a solid wall protects the others");
            wall[0] = 9;
            set(dart, "origin", from);
            set(dart, "originVec", new Vector(1, 0, 0));
            set(dart, "previousArrowLocation", from);
            set(dart, "maxRangeRemove", 30d);
            set(dart, "minRangeRemove", 1.3d);
            set(dart, "pullSpeedOthers", .7d);
            set(dart, "pullSpeed", .9d);
            dart.progress();
            assertEquals(1, impulses.size(), "the first sweep contact must pull on its acquisition tick");
            assertSame(dart, impulses.getFirst().getAbility());
            assertSame(near, impulses.getFirst().getAffected());
            assertEquals(.7, impulses.getFirst().getVelocity().length(), 1e-9);
            assertTrue(impulses.getFirst().getVelocity().getX() < 0);
            assertTrue(removed[0]);
            assertNull(arrow.get(dart), "an attached ability must survive actor pruning at a confirmed checkpoint");
            near.x = 6;
            set(dart, "pullYourselfToEnemy", true);
            dart.progress();
            assertSame(owner, impulses.getLast().getAffected());
            assertSame(dart, impulses.getLast().getAbility());
            assertEquals(.9, impulses.getLast().getVelocity().length(), 1e-9);
            assertTrue(impulses.getLast().getVelocity().getX() > 0);
            assertEquals(new Location(world, 6, 1, 0), dart.getLocation());
            dart.setTarget(far);
            assertEquals(new Location(world, 6, 1, 0), dart.getLocation());
        }
    }

    private static void set(RopeDart dart, String name, Object value) throws ReflectiveOperationException {
        var field = RopeDart.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(dart, value);
    }
}
