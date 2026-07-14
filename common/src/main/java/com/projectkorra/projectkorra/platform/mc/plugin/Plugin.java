package com.projectkorra.projectkorra.platform.mc.plugin;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.configuration.PKConfiguration;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.Server;
import com.projectkorra.projectkorra.platform.mc.command.PluginCommand;

import java.io.File;
import java.util.logging.Logger;

public interface Plugin {
    default String getName() {
        return getClass().getSimpleName();
    }

    default ProjectKorra.Description getDescription() {
        return ProjectKorra.plugin.getDescription();
    }

    default PKConfiguration getConfig() {
        return ProjectKorra.plugin.getConfig();
    }

    default Logger getLogger() {
        return Logger.getLogger(getName());
    }

    default Server getServer() {
        return new Server();
    }

    default boolean isEnabled() {
        return true;
    }

    default File getDataFolder() {
        return Platform.dataFolder().toFile();
    }

    default PluginCommand getCommand(String name) {
        return new PluginCommand();
    }
}
