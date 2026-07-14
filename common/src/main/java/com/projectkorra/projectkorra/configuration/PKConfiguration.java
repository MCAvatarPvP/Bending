package com.projectkorra.projectkorra.configuration;

/**
 * Mutable root configuration used by the common module.
 */
public interface PKConfiguration extends PKConfigurationSection {
    void set(String path, Object value);

    void addDefault(String path, Object value);

    PKConfigurationOptions options();
}
