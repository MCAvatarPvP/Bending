package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.util.colliders.AABB;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TornadoPullGeometryTest {
    private final World world = new World();
    private final Location base = new Location(world, 5, 64, 7);

    @Test
    void tallTornadoPullZoneIncludesVictimsAtBothGroundAndTop() throws Exception {
        final Tornado tornado = tornado(24, 4);

        assertTrue(inPullZone(tornado, new Location(world, 5, 64, 7)));
        assertTrue(inPullZone(tornado, new Location(world, 5, 88, 7)));
        assertTrue(inPullZone(tornado, new Location(world, 8.9, 76, 7)));
        assertFalse(inPullZone(tornado, new Location(world, 9.1, 76, 7)));
        assertFalse(inPullZone(tornado, new Location(world, 5, 90, 7)));
        assertFalse(inPullZone(tornado, new Location(new World(), 5, 76, 7)));
    }

    @Test
    void playerRewindColliderCoversTheFullHeightOfATallNarrowVortex() throws Exception {
        final Tornado tornado = tornado(64, 2.5);
        final Method method = Tornado.class.getDeclaredMethod("getLagCompensationCollider");
        method.setAccessible(true);
        final AABB collider = (AABB) method.invoke(tornado);

        assertTrue(collider.contains(base));
        assertTrue(collider.contains(base.clone().add(0, 64, 0)));
        assertFalse(collider.contains(base.clone().add(3, 32, 0)));
        assertEquals(96, collider.getCenter().getY(), 1.0e-9);
        assertEquals(5, collider.getMax().getX() - collider.getMin().getX(), 1.0e-9);
    }

    @Test
    void pullCollisionUsesTheSuppliedHistoricalBaseWithoutChangingCurrentPosition() throws Exception {
        final Tornado tornado = tornado(24, 4);
        final Location oldBase = base.clone().add(-12, 0, 0);
        final Entity oldVictim = entity(oldBase.clone().add(0, 2, 0));
        final Method method = Tornado.class.getDeclaredMethod("isInPullZone", Entity.class, Location.class);
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(tornado, oldVictim, oldBase));
        assertFalse((boolean) method.invoke(tornado, oldVictim, base));
        assertEquals(base, tornado.getLocation());
    }

    private Tornado tornado(final double height, final double pullRadius) throws Exception {
        // A registration-only instance lets us exercise geometry without a live server.
        final Tornado tornado = new Tornado(null);
        set(tornado, "currentLoc", base.clone());
        set(tornado, "tornadoHeight", height);
        set(tornado, "pullZoneRadius", pullRadius);
        return tornado;
    }

    private boolean inPullZone(final Tornado tornado, final Location location) throws Exception {
        final Method method = Tornado.class.getDeclaredMethod("isInPullZone", Entity.class, Location.class);
        method.setAccessible(true);
        return (boolean) method.invoke(tornado, entity(location), base);
    }

    private static Entity entity(final Location location) {
        return new Entity() {
            @Override public Location getLocation() { return location.clone(); }
            @Override public World getWorld() { return location.getWorld(); }
        };
    }

    private static void set(final Tornado tornado, final String fieldName, final Object value) throws Exception {
        final Field field = Tornado.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(tornado, value);
    }
}
