package com.projectkorra.projectkorra.platform;

import com.projectkorra.projectkorra.platform.model.PKAdapter;

import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Global access point for ProjectKorra's server-platform abstraction.
 *
 * <p>Common ProjectKorra code should depend on this package instead of calling
 * Bukkit/Paper/Fabric statics directly. Platform specific launchers install the
 * concrete implementation during bootstrap.</p>
 */
public final class Platform {
    private static ProjectKorraPlatform current;

    private Platform() {
    }

    public static void install(final ProjectKorraPlatform platform) {
        current = Objects.requireNonNull(platform, "platform");
    }

    public static boolean isInstalled() {
        return current != null;
    }

    public static ProjectKorraPlatform current() {
        if (current == null) {
            throw new IllegalStateException("ProjectKorra platform has not been installed yet");
        }
        return current;
    }

    public static PKScheduler scheduler() {
        return current().scheduler();
    }

    public static PKEventBus events() {
        return current().events();
    }

    public static PKPlayers players() {
        return current().players();
    }

    public static PKWorlds worlds() {
        return current().worlds();
    }

    public static PKPlugins plugins() {
        return current().plugins();
    }

    public static PKTags tags() {
        return current().tags();
    }

    public static PKPermissions permissions() {
        return current().permissions();
    }

    public static PKServer server() {
        return current().server();
    }

    public static PKScoreboards scoreboards() {
        return current().scoreboards();
    }

    public static PKBossBars bossBars() {
        return current().bossBars();
    }

    public static PKChunks chunks() {
        return current().chunks();
    }

    public static PKAdapter adapter() {
        return current().adapter();
    }

    public static Logger logger() {
        return current().logger();
    }

    public static Path dataFolder() {
        return current().dataFolder();
    }

    public static Object pluginHandle() {
        return current().pluginHandle();
    }

    public static <T> T pluginHandle(final Class<T> type) {
        return type.cast(pluginHandle());
    }
}
