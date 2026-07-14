package com.projectkorra.projectkorra.platform;

import java.util.Collection;

/**
 * World registry facade.
 */
public interface PKWorlds {
    <W> Collection<W> worlds();
}
