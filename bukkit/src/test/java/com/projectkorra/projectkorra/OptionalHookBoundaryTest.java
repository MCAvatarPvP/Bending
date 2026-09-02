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

    @Test
    void serverEntityInterpolationIsReloadableAndOptInByDefault() throws IOException {
        String config = source("../common/src/main/java/com/projectkorra/projectkorra/configuration/ConfigManager.java");
        String plugin = source("src/main/java/com/projectkorra/projectkorra/BukkitProjectKorraPlugin.java");
        String platform = source(
                "src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitProjectKorraPlatform.java");
        String interpolation = source(
                "src/main/java/com/projectkorra/projectkorra/prediction/server/ServerEntityInterpolation.java");

        assertTrue(config.contains(
                "config.addDefault(\"Properties.ServerEntityInterpolation.Enabled\", false)"),
                "packet-driven interpolation must remain disabled unless a server opts in");
        assertTrue(plugin.contains("synchronizeServerEntityInterpolation()")
                        && plugin.contains("stopServerEntityInterpolation()")
                        && plugin.contains("Properties.ServerEntityInterpolation.Enabled\", false"),
                "the setting must control both the packet hook and interpolation ticker");
        assertTrue(platform.contains("projectKorra.synchronizeServerEntityInterpolation()"),
                "a ProjectKorra reload must apply the new setting");
        assertTrue(interpolation.contains("this.task != null && !this.task.isCancelled()"),
                "repeated synchronization must not register duplicate tick tasks");
    }

    private static String source(String relative) throws IOException {
        Path source = Path.of(relative);
        if (!Files.exists(source) && relative.startsWith("../")) source = Path.of(relative.substring(3));
        if (!Files.exists(source)) source = Path.of("bukkit").resolve(source);
        assertTrue(Files.exists(source), source.toString());
        return Files.readString(source);
    }
}
