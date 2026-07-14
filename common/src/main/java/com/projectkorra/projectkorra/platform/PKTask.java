package com.projectkorra.projectkorra.platform;

/**
 * Opaque scheduled task handle.
 */
public interface PKTask {
    void cancel();

    boolean cancelled();

    int legacyId();
}
