package com.projectkorra.projectkorra.platform;

/**
 * Platform event dispatcher. Accepts opaque event objects to keep core free of platform event imports.
 */
public interface PKEventBus {
    void call(Object event);

    void registerListener(Object listener);

    default void registerListener(final Object listener, final Object owner) {
        registerListener(listener);
    }

    void unregisterAll(Object listener);
}
