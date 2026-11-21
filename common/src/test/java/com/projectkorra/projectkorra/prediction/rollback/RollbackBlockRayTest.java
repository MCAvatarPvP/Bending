package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.FluidCollisionMode;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockRay;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RollbackBlockRayTest {
    @Test void directionMagnitudeDoesNotChangeReachAndCallerVectorsStayDetached() {
        var origin = new Vector(-0.5, 10, 2);
        var direction = new Vector(300, 400, 0);
        var ray = RollbackBlockRay.from(origin, direction, 5, FluidCollisionMode.valueOf("SOURCE_ONLY"), true);
        assertEquals(2.5, ray.endX());
        assertEquals(14, ray.endY());
        assertEquals(-1, ray.originBlock().x());
        assertEquals(RollbackBlockRay.Fluids.SOURCE_ONLY, ray.fluids());
        assertEquals(300, direction.getX());
        origin.setX(99);
        direction.setY(0);
        assertEquals(-0.5, ray.x());
        assertEquals(14, ray.endY());
    }

    @Test void malformedRaysCannotEnterNativeTraversal() {
        var origin = new Vector();
        var direction = new Vector(1, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> RollbackBlockRay.from(origin, new Vector(), 1, FluidCollisionMode.NEVER, true));
        assertThrows(IllegalArgumentException.class, () -> RollbackBlockRay.from(origin, direction, Double.NaN, FluidCollisionMode.NEVER, true));
        assertThrows(IllegalArgumentException.class, () -> RollbackBlockRay.from(origin, direction, -1, FluidCollisionMode.NEVER, true));
        assertThrows(IllegalArgumentException.class, () -> RollbackBlockRay.from(origin, direction, Double.MAX_VALUE, FluidCollisionMode.NEVER, true));
        assertThrows(IllegalArgumentException.class, () -> RollbackBlockRay.from(origin, new Vector(Double.NaN, 0, 0), 1, FluidCollisionMode.NEVER, true));
        assertThrows(IllegalArgumentException.class, () -> RollbackBlockRay.from(origin, direction, 1, FluidCollisionMode.valueOf("UNKNOWN"), true));
        var zero = RollbackBlockRay.from(origin, direction, 0, FluidCollisionMode.NEVER, true);
        assertEquals(zero.x(), zero.endX());
    }
}
