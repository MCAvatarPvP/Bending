package com.projectkorra.projectkorra.platform.mc;

import com.projectkorra.projectkorra.platform.mc.entity.Player;

import java.util.UUID;

public interface OfflinePlayer {
    UUID getUniqueId();

    String getName();

    boolean isOnline();

    default boolean hasPlayedBefore() {
        return true;
    }

    default Player getPlayer() {
        return this instanceof Player player ? player : null;
    }

    default Object handle() {
        return this;
    }
}
