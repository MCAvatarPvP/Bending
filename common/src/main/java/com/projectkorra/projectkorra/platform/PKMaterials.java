package com.projectkorra.projectkorra.platform;

import com.projectkorra.projectkorra.platform.mc.Material;

/** Platform-native properties for material-only queries without a world block. */
@FunctionalInterface
public interface PKMaterials {
    boolean isSolid(Material material);
}
