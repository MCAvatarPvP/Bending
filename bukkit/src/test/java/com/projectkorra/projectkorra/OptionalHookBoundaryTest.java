package com.projectkorra.projectkorra;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards optional integrations from disabling ProjectKorra during class linkage. */
class OptionalHookBoundaryTest {
    @Test
    void incompatibleBetonQuestApiDoesNotEscapePluginStartup() throws IOException {
        String plugin = source("src/main/java/com/projectkorra/projectkorra/BukkitProjectKorraPlugin.java");
        int method = plugin.indexOf("private void registerBetonQuestHook()");
        int apiCall = plugin.indexOf("BetonQuestHook.register(this)", method);
        int guardedLinkage = plugin.indexOf("catch (LinkageError | RuntimeException", apiCall);

        assertTrue(method >= 0 && apiCall > method && guardedLinkage > apiCall,
                "the optional BetonQuest API linkage must be contained outside BetonQuestHook itself");
    }

    @Test
    void packetEventsActionBarHookHasGuardedLifecycleAndNativePlayerBoundary() throws IOException {
        String plugin = source("src/main/java/com/projectkorra/projectkorra/BukkitProjectKorraPlugin.java");
        String hook = source("src/main/java/com/projectkorra/projectkorra/hooks/ExternalActionBarHook.java");

        assertTrue(plugin.contains("ExternalActionBarHook.register(ProjectKorra.plugin)"));
        assertTrue(plugin.contains("this.externalActionBarHook.stop()"));
        assertTrue(plugin.contains("catch (LinkageError | RuntimeException"));
        assertTrue(hook.contains("recipient instanceof org.bukkit.entity.Player nativePlayer"));
        assertTrue(hook.contains("BukkitMC.player(nativePlayer)"));
        assertTrue(hook.contains("sendPacketSilently(nativePlayer, packet)"));
    }

    private static String source(String relative) throws IOException {
        Path source = Path.of(relative);
        if (!Files.exists(source)) source = Path.of("bukkit").resolve(source);
        assertTrue(Files.exists(source), source.toString());
        return Files.readString(source);
    }
}
