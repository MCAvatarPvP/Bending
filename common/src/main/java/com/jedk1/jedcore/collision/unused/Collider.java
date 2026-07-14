package com.jedk1.jedcore.collision.unused;

import com.projectkorra.projectkorra.platform.mc.util.Vector;

public interface Collider {
    boolean intersects(AABB aabb);

    boolean intersects(Sphere sphere);

    Vector getPosition();

    Vector getHalfExtents();

    boolean contains(Vector point);
}
