package com.projectkorra.projectkorra.platform;

import java.util.Collection;
import java.util.UUID;

/**
 * Player registry facade. Generic return types allow legacy Bukkit callers during migration without binding this API to Bukkit.
 */
public interface PKPlayers {
    <P> Collection<P> onlinePlayers();

    <P> P getPlayer(UUID uuid);

    <P> P getPlayer(String name);

    <P> P getOfflinePlayer(UUID uuid);

    <P> P getOfflinePlayer(String name);

    /**
     * True when a Bedrock/Geyser/Floodgate bridge identifies this UUID as Bedrock.
     */
    default boolean isBedrockPlayer(UUID uuid) {
        return false;
    }

    /**
     * Platform/plugin supplied spectator state outside vanilla spectator gamemode, e.g. StrikePractice.
     */
    default boolean isExternalSpectator(Object player) {
        return false;
    }
}

