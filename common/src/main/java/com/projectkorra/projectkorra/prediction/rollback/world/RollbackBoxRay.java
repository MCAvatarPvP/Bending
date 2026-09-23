package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

/**
 * Portable projection of Paper's BoundingBox expansion and ray-query semantics.
 * Fabric's native Box.raycast differs at endpoints and for rays starting inside a box.
 * This contains no entity/ability policy; native Paper regression tests check its math.
 */
public final class RollbackBoxRay {
    private RollbackBoxRay() { }

    /** Symmetric contraction collapses an exhausted axis to its original center. */
    public static RollbackBlockStore.Box expanded(BoundingBox box, double size) {
        if (!Double.isFinite(size)) throw new IllegalArgumentException("Box expansion");
        double[] lower = {box.getMinX(), box.getMinY(), box.getMinZ()};
        double[] upper = {box.getMaxX(), box.getMaxY(), box.getMaxZ()};
        for (int axis = 0; axis < 3; axis++) {
            if (!Double.isFinite(lower[axis]) || !Double.isFinite(upper[axis]) || lower[axis] > upper[axis]) throw new IllegalArgumentException("Box bounds");
            double center = lower[axis] + (upper[axis] - lower[axis]) * 0.5;
            lower[axis] -= size; upper[axis] += size;
            if (lower[axis] > upper[axis]) lower[axis] = upper[axis] = center;
        }
        return new RollbackBlockStore.Box(lower[0], lower[1], lower[2], upper[0], upper[1], upper[2]);
    }

    /**
     * As in Paper, an inside-origin ray selects the exit face, even beyond its nominal
     * distance. The combined world query still compares that result with the block hit.
     * A degenerate native result can contain NaN; world selection discards it exactly
     * as Paper's distance comparison does, before exposing a result to an ability.
     */
    public static Vector trace(RollbackBlockStore.Box box, RollbackWorldRay ray) {
        double[] start = {ray.blocks().x(), ray.blocks().y(), ray.blocks().z()};
        double[] direction = {ray.direction().x(), ray.direction().y(), ray.direction().z()};
        double[] lower = {box.minX(), box.minY(), box.minZ()}, upper = {box.maxX(), box.maxY(), box.maxZ()};
        double near = 0, far = 0;
        for (int axis = 0; axis < 3; axis++) {
            // Paper normalizes signed zero before computing reciprocal directions.
            double component = direction[axis] == 0 ? 0 : direction[axis];
            double reciprocal = 1 / component;
            double enter = ((component >= 0 ? lower[axis] : upper[axis]) - start[axis]) * reciprocal;
            double leave = ((component >= 0 ? upper[axis] : lower[axis]) - start[axis]) * reciprocal;
            if (axis == 0) { near = enter; far = leave; }
            else {
                if (near > leave || far < enter) return null;
                if (enter > near) near = enter;
                if (leave < far) far = leave;
            }
        }
        if (far < 0 || near > ray.distance()) return null;
        double distance = near < 0 ? far : near;
        return new Vector(start[0] + direction[0] * distance, start[1] + direction[1] * distance, start[2] + direction[2] * distance);
    }
}
