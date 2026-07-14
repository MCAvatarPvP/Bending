package com.projectkorra.projectkorra.platform;

import com.projectkorra.projectkorra.platform.model.PKAdapter;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Platform-neutral services needed by ProjectKorra core.
 */
public interface ProjectKorraPlatform {
    String id();

    Object pluginHandle();

    Path dataFolder();

    Logger logger();

    PKScheduler scheduler();

    PKEventBus events();

    PKPlayers players();

    PKWorlds worlds();

    PKPlugins plugins();

    PKTags tags();

    PKPermissions permissions();

    PKServer server();

    PKScoreboards scoreboards();

    PKBossBars bossBars();

    PKChunks chunks();

    PKAdapter adapter();
}
