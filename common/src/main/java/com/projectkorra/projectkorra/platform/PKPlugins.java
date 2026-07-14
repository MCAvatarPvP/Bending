package com.projectkorra.projectkorra.platform;

/**
 * Plugin/mod discovery facade.
 */
public interface PKPlugins {
    <P> P getPlugin(String name);

    boolean isPluginPresent(String name);

    boolean isPluginEnabled(String name);

    default String pluginName(Object plugin) {
        return plugin == null ? "" : String.valueOf(plugin);
    }
}
