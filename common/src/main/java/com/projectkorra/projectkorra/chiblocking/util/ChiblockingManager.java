package com.projectkorra.projectkorra.chiblocking.util;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.chiblocking.Smokescreen;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

public class ChiblockingManager implements Runnable {
    public ProjectKorra plugin;

    public ChiblockingManager(final ProjectKorra plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (final Player player : Platform.players().<Player>onlinePlayers()) {
            Smokescreen.removeFromHashMap(player);
        }
    }
}
