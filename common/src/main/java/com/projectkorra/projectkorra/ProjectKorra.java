package com.projectkorra.projectkorra;

import com.projectkorra.projectkorra.ability.util.CollisionInitializer;
import com.projectkorra.projectkorra.ability.util.CollisionManager;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.configuration.PKConfiguration;
import com.projectkorra.projectkorra.platform.PKTask;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.Server;
import com.projectkorra.projectkorra.platform.mc.plugin.java.JavaPlugin;
import com.projectkorra.projectkorra.util.Updater;

import java.io.File;
import java.util.List;
import java.util.logging.Logger;

/**
 * Platform-neutral ProjectKorra core facade.
 *
 * <p>The old Bukkit JavaPlugin lived behind this type. Common abilities still
 * reference ProjectKorra for logger/config/state access, so the class now owns
 * only cross-platform state and delegates server/platform behavior through
 * {@link Platform}. Bukkit and Fabric entrypoints install a platform and then
 * call the common startup sequence.</p>
 */
public final class ProjectKorra implements JavaPlugin {
    public static final ProjectKorra plugin = new ProjectKorra();
    public static Logger log = Logger.getLogger("ProjectKorra");
    public static CollisionManager collisionManager;
    public static CollisionInitializer collisionInitializer;
    public static long time_step = 1;
    public PKTask revertChecker;
    public Updater updater;

    private ProjectKorra() {
    }

    public static void initCommon() {
        log = Platform.logger();
        new ConfigManager();
        new GeneralMethods(plugin);
    }

    public static CollisionManager getCollisionManager() {
        return collisionManager;
    }

    public static void setCollisionManager(final CollisionManager collisionManager) {
        ProjectKorra.collisionManager = collisionManager;
    }

    public static CollisionInitializer getCollisionInitializer() {
        return collisionInitializer;
    }

    public static void setCollisionInitializer(final CollisionInitializer collisionInitializer) {
        ProjectKorra.collisionInitializer = collisionInitializer;
    }

    public static boolean isStatisticsEnabled() {
        return ConfigManager.getConfig().getBoolean("Properties.Statistics");
    }

    public static boolean isDatabaseCooldownsEnabled() {
        return ConfigManager.getConfig().getBoolean("Properties.DatabaseCooldowns");
    }

    public File getDataFolder() {
        return Platform.dataFolder().toFile();
    }

    public Logger getLogger() {
        return Platform.logger();
    }

    public String getName() {
        return "ProjectKorra";
    }

    public PKConfiguration getConfig() {
        return ConfigManager.defaultConfig == null ? new Config(new File("config.yml")) : ConfigManager.defaultConfig.get();
    }

    public void saveDefaultConfig() {
        if (ConfigManager.defaultConfig != null) {
            ConfigManager.defaultConfig.save();
        }
    }

    public Server getServer() {
        return new Server();
    }

    public Description getDescription() {
        return new Description("1.10.2", List.of());
    }

    public record Description(String version, List<String> depend) {
        public String getVersion() {
            return version;
        }

        public List<String> getDepend() {
            return depend;
        }

        public String getName() {
            return "ProjectKorra";
        }

        public List<String> getAuthors() {
            return List.of("ProjectKorra");
        }
    }
}
