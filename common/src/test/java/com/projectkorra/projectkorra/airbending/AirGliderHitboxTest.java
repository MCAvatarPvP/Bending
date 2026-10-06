package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.colliders.AABB;
import com.projectkorra.projectkorra.util.colliders.Ray;
import com.projectkorra.projectkorra.util.colliders.Sphere;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AirGliderHitboxTest {
    private final com.projectkorra.projectkorra.support.AbilityWorld world = new com.projectkorra.projectkorra.support.AbilityWorld();
    @org.junit.jupiter.api.AfterEach void close() throws Exception { world.close(); }
    private final AirGlider glider = new AirGlider(null);
    private final BoundingBox body = new BoundingBox(new Vector(-.3, 10, -.3), new Vector(.3, 10.6, .3));
    private final Player rider = new Player() {
        @Override public World getWorld() { return world; }
        @Override public Location getLocation() { return new Location(world, 0, 10, 0); }
        @Override public Location getEyeLocation() { return getLocation(); }
        @Override public BoundingBox getBoundingBox() { return body; }
        @Override public BoundingBox getCombatBoundingBox() { return glider.getCombatBoundingBox(); }
        @Override public boolean isOnline() { return true; }
    };

    private void setup() throws Exception {
        set(CoreAbility.class, "player", rider);
        set(AirGlider.class, "state", AirGlider.State.GLIDING);
        set(AirGlider.class, "modelSpread", 1F);
        set(AirGlider.class, "modelHeightOffset", .82);
    }
    private void set(Class<?> type, String name, Object value) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(glider, value);
    }

    @Test void boundsFollowModelRotationDeploymentAndLifecycleWithoutChangingBody() throws Exception {
        setup();
        BoundingBox open = glider.getCombatBoundingBox();
        assertTrue(open.getMinX() < -2 && open.getMaxX() > 2);
        assertTrue(open.getMinZ() < -2.6 && open.getMaxZ() > 2.4);
        assertSame(body, rider.getBoundingBox());
        set(AirGlider.class, "modelYaw", (float) (Math.PI / 2));
        BoundingBox turned = glider.getCombatBoundingBox();
        assertEquals(open.getMaxZ(), turned.getMaxX(), .001);
        set(AirGlider.class, "modelRoll", (float) (Math.PI / 4));
        assertTrue(glider.getCombatBoundingBox().getMaxY() > open.getMaxY() + 1);
        set(AirGlider.class, "modelRoll", 0F);
        set(AirGlider.class, "modelYaw", 0F);
        set(AirGlider.class, "modelSpread", 0F);
        assertTrue(glider.getCombatBoundingBox().getMaxX() < .4);
        set(AirGlider.class, "state", AirGlider.State.FOLDED_DIVE);
        assertSame(body, glider.getCombatBoundingBox());
        set(AirGlider.class, "state", AirGlider.State.GLIDING);
        set(CoreAbility.class, "removed", true);
        assertSame(body, glider.getCombatBoundingBox());
    }

    @Test @SuppressWarnings("unchecked") void wingOnlyHitsReachRiderWithFiltersAndNoDuplicates() throws Exception {
        setup();
        Field f = CoreAbility.class.getDeclaredField("INSTANCES_BY_CLASS"); f.setAccessible(true);
        Map<Class<?>, Set<CoreAbility>> registry = (Map<Class<?>, Set<CoreAbility>>) f.get(null);
        Set<CoreAbility> old = registry.put(AirGlider.class, Set.of(glider));
        try {
            Location wing = new Location(world, 1.8, 10.82, .5);
            assertFalse(body.overlaps(new AABB(wing, .1).toBoundingBox()));
            assertEquals(1, new AABB(wing, .1).getEntities(e -> true).size());
            assertEquals(1, new Sphere(wing, .1).getEntities(e -> true).size());
            assertEquals(1, new Ray(new Location(world, 1.8, 12, .5), new Vector(0, -1, 0), 2)
                    .getEntities(e -> true).size());
            assertTrue(new AABB(wing, .1).getEntities(e -> false).isEmpty());
            assertEquals(1, com.projectkorra.projectkorra.util.CombatBounds.includeGliders(world,
                    new AABB(wing, .1).toBoundingBox(), e -> true, java.util.List.of(rider)).size());
            assertTrue(new AABB(new Location(new World(), 1.8, 10.82, .5), .1)
                    .getEntities(e -> true).isEmpty());
            set(AirGlider.class, "state", AirGlider.State.FOLDED_DIVE);
            assertTrue(new AABB(wing, .1).getEntities(e -> true).isEmpty());
        } finally {
            if (old == null) registry.remove(AirGlider.class); else registry.put(AirGlider.class, old);
        }
    }
}
