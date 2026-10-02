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
    private static final ThreadLocal<Scope> SCOPED = new ThreadLocal<>();

    private Platform() {
    }

    public static void install(final ProjectKorraPlatform platform) {
        current = Objects.requireNonNull(platform, "platform");
    }

    public static boolean isInstalled() {
        return SCOPED.get() != null || current != null;
    }

    public static ProjectKorraPlatform current() {
        final Scope scope = SCOPED.get();
        if (scope != null) return scope.platform;
        if (current == null) {
            throw new IllegalStateException("ProjectKorra platform has not been installed yet");
        }
        return current;
    }

    /** Installs isolated simulation services for the current thread until the scope closes. */
    public static Scope using(final ProjectKorraPlatform platform) {
        final Scope scope = new Scope(Objects.requireNonNull(platform, "platform"), SCOPED.get());
        SCOPED.set(scope);
        return scope;
    }

    public static final class Scope implements AutoCloseable {
        private final ProjectKorraPlatform platform;
        private final Scope previous;
        private final Thread owner = Thread.currentThread();
        private boolean closed;

        private Scope(ProjectKorraPlatform platform, Scope previous) {
            this.platform = platform;
            this.previous = previous;
        }

        @Override public void close() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Platform scope crossed threads");
            if (closed) return;
            if (SCOPED.get() != this) throw new IllegalStateException("Platform scopes closed out of order");
            if (previous == null) SCOPED.remove();
            else SCOPED.set(previous);
            closed = true;
        }
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

    public static PKMaterials materials() {
        return current().materials();
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
