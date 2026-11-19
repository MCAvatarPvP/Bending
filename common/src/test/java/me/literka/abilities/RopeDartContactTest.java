package me.literka.abilities;

import com.projectkorra.projectkorra.ability.CoreAbility;
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
            var candidates = new ArrayList<Entity>();
            var wall = new double[]{3};
            World world = new World() {
                @Override public Collection<Entity> getNearbyCombatEntities(BoundingBox box, Predicate<Entity> filter) {
                    return candidates.stream().filter(filter).toList();
                }
                @Override public RayTraceResult rayTraceBlocks(Location start, Vector direction, double length,
                        FluidCollisionMode mode, boolean passable) {
                    return new RayTraceResult(new Vector(wall[0], 1, 0));
                }
            };
            var owner = new Player() {
                @Override public World getWorld() { return world; }
                @Override public UUID getUniqueId() { return new UUID(0, 9); }
            };
            class Target extends LivingEntity {
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
            candidates.add(far); candidates.add(tied); candidates.add(near);
            boolean[] removed = {false};
            var projectile = new Arrow() {
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
            assertFalse(removed[0], "solid wall blocks the dart");
            wall[0] = 9;
            sweep.invoke(dart, from, to);
            assertTrue(removed[0]);
            assertNull(arrow.get(dart), "an attached ability must survive actor pruning at a confirmed checkpoint");
            near.x = 6;
            assertEquals(new Location(world, 6, 1, 0), dart.getLocation());
            dart.setTarget(far);
            assertEquals(new Location(world, 6, 1, 0), dart.getLocation());
        }
    }
}
