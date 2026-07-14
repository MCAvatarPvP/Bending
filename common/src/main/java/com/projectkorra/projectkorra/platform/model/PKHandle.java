package com.projectkorra.projectkorra.platform.model;

/**
 * Base interface for platform-backed wrapper objects.
 */
public interface PKHandle {
    Object handle();

    default <T> T handle(final Class<T> type) {
        return type.cast(handle());
    }
}
