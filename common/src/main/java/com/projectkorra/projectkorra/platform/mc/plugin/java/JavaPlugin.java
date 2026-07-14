package com.projectkorra.projectkorra.platform.mc.plugin.java;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.platform.mc.plugin.Plugin;

public interface JavaPlugin extends Plugin {
    static <T> T getPlugin(Class<T> type) {
        return type.cast(ProjectKorra.plugin);
    }

    default void onEnable() {
    }

    default void onDisable() {
    }

    default ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }
}
