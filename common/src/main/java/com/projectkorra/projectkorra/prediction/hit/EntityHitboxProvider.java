package com.projectkorra.projectkorra.prediction.hit;

import com.projectkorra.projectkorra.platform.mc.Location;

import java.util.List;

/**
 * Optional entity-hit geometry for abilities whose damage collider differs
 * from their ability-vs-ability collision geometry.
 */
public interface EntityHitboxProvider {
    List<Location> getEntityHitLocations();

    double getEntityHitRadius();
}
