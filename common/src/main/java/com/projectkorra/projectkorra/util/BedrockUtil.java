package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.platform.Platform;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Platform-neutral Bedrock/Geyser detection. Bukkit implements this through
 * Floodgate/PacketEvents when present; Fabric safely returns false unless a
 * Fabric-side bridge is installed.
 */
public final class BedrockUtil {
    private BedrockUtil() {
    }

    public static boolean hasClass(String className) {
        return getClass(className) != null;
    }

    public static @Nullable Class<?> getClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    public static boolean isBedrockPlayer(UUID uuid) {
        return Platform.players().isBedrockPlayer(uuid);
    }
}
