package com.projectkorra.projectkorra.platform.mc.util;

import com.projectkorra.projectkorra.platform.mc.entity.Entity;

public class RayTraceResult {
    private final Vector hitPosition;
    private final Entity hitEntity;

    public RayTraceResult() {
        this(new Vector(), null);
    }

    public RayTraceResult(Vector hitPosition) {
        this(hitPosition, null);
    }

    public RayTraceResult(Vector hitPosition, Entity hitEntity) {
        this.hitPosition = hitPosition;
        this.hitEntity = hitEntity;
    }

    public Vector getHitPosition() {
        return hitPosition;
    }

    public Entity getHitEntity() {
        return hitEntity;
    }
}
