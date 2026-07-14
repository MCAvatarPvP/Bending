package com.jedk1.jedcore.util;

import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.util.colliders.AABB;

public final class BlockUtil {
    private BlockUtil() {

    }

    public static AABB getFallingBlockBoundsFull(FallingBlock fb, double scale) {
        return new AABB(fb.getWorld(), fb.getBoundingBox()).expand(scale - 0.5);
    }
}
