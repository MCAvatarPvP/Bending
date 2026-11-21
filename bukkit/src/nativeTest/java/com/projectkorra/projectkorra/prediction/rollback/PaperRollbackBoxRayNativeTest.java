package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.FluidCollisionMode;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBoxRay;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorldRay;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the actual Paper API math; no Minecraft world or entity fixture supplies expected hits. */
class PaperRollbackBoxRayNativeTest {
    @Test void portableEntityQueriesMatchNativeExpansionAndRayClipping() {
        Random random = new Random(781239L);
        for (int sample = 0; sample < 2_000; sample++) {
            double x = random.nextDouble(-20, 20), y = random.nextDouble(-20, 20), z = random.nextDouble(-20, 20);
            var box = new BoundingBox(new Vector(x, y, z), new Vector(x + random.nextDouble(0.01, 4), y + random.nextDouble(0.01, 4), z + random.nextDouble(0.01, 4)));
            Vector start = sample % 2 == 0 ? box.getCenter() : new Vector(random.nextDouble(-24, 24), random.nextDouble(-24, 24), random.nextDouble(-24, 24));
            Vector direction = new Vector(random.nextDouble(-1, 1), sample % 3 == 0 ? 0 : random.nextDouble(-1, 1), random.nextDouble(-1, 1));
            compare(box, start, direction, sample % 7 == 0 ? 0 : random.nextDouble(0, 50), random.nextDouble(-3, 3));
        }
    }

    @Test void axisAlignedBoundaryEndpointAndInsideOriginCasesMatchPaper() {
        var box = new BoundingBox(new Vector(0, 0, 0), new Vector(1, 1, 1));
        for (double x : new double[]{-1, 0, 0.5, 1, 2}) {
            for (double y : new double[]{0, 0.5, 1}) {
                for (double z : new double[]{0, 0.5, 1}) {
                    for (Vector direction : new Vector[]{new Vector(1, 0, 0), new Vector(-1, -0.0, 0), new Vector(0, 1, 0), new Vector(0, 0, 1)}) {
                        for (double distance : new double[]{0, 0.25, 1, 4}) compare(box, new Vector(x, y, z), direction, distance, 0);
                    }
                }
            }
        }
    }

    private static void compare(BoundingBox box, Vector start, Vector direction, double distance, double size) {
        var nativeBox = new org.bukkit.util.BoundingBox(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ()).expand(size);
        var expanded = RollbackBoxRay.expanded(box, size);
        assertEquals(nativeBox.getMinX(), expanded.minX(), 1e-12);
        assertEquals(nativeBox.getMinY(), expanded.minY(), 1e-12);
        assertEquals(nativeBox.getMinZ(), expanded.minZ(), 1e-12);
        assertEquals(nativeBox.getMaxX(), expanded.maxX(), 1e-12);
        assertEquals(nativeBox.getMaxY(), expanded.maxY(), 1e-12);
        assertEquals(nativeBox.getMaxZ(), expanded.maxZ(), 1e-12);
        var expected = nativeBox.rayTrace(new org.bukkit.util.Vector(start.getX(), start.getY(), start.getZ()),
                new org.bukkit.util.Vector(direction.getX(), direction.getY(), direction.getZ()), distance);
        var actual = RollbackBoxRay.trace(expanded, RollbackWorldRay.from(start, direction, distance, FluidCollisionMode.NEVER, true));
        if (expected == null) assertNull(actual);
        else {
            assertNotNull(actual);
            assertEquals(expected.getHitPosition().getX(), actual.getX(), 1e-10);
            assertEquals(expected.getHitPosition().getY(), actual.getY(), 1e-10);
            assertEquals(expected.getHitPosition().getZ(), actual.getZ(), 1e-10);
        }
    }
}
