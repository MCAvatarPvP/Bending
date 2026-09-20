package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FabricUpdateInstallerTest {
    @TempDir Path directory;

    @Test void replacementKeepsOneModJarAndPreservesTheBackup() throws Exception {
        Path target = Files.writeString(directory.resolve("ProjectKorra-old-fabric.jar"), "old jar");
        Path stage = Files.createDirectory(directory.resolve("staged"));
        Path download = Files.writeString(stage.resolve("update.jar"), "new jar");
        FabricUpdateInstaller.install(target, download, FabricUpdateInstaller.sha256(target), FabricUpdateInstaller.sha256(download));
        assertEquals("new jar", Files.readString(target));
        assertEquals("old jar", Files.readString(stage.resolve("previous.jar.backup")));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().endsWith(".jar")).count());
        }
    }

    @Test void tamperedDownloadsAndChangedInstallationsLeaveTheOriginalUntouched() throws Exception {
        Path target = Files.writeString(directory.resolve("mod.jar"), "old jar");
        Path download = Files.writeString(directory.resolve("update.jar"), "new jar");
        String oldHash = FabricUpdateInstaller.sha256(target);
        String newHash = FabricUpdateInstaller.sha256(download);
        Files.writeString(download, "broken download");
        assertThrows(IllegalStateException.class, () -> FabricUpdateInstaller.install(target, download, oldHash, newHash));
        assertEquals("old jar", Files.readString(target));
        Files.writeString(download, "new jar");
        Files.writeString(target, "manually replaced jar");
        assertThrows(IllegalStateException.class, () -> FabricUpdateInstaller.install(target, download, oldHash, newHash));
        assertEquals("manually replaced jar", Files.readString(target));
    }

    @Test void standaloneHelperWaitsForGameExitAndRunsWithoutMinecraftDependencies() throws Exception {
        Path target = Files.writeString(directory.resolve("mod with spaces.jar"), "old jar");
        Path stage = Files.createDirectory(directory.resolve("staged update"));
        Path download = Files.writeString(stage.resolve("update.jar"), "new jar");
        Path fakeGameSource = directory.resolve("FakeGame.java");
        Files.writeString(fakeGameSource, "class FakeGame { public static void main(String[] args) throws Exception { System.in.read(); } }");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process game = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                fakeGameSource.toString()).redirectErrorStream(true).redirectOutput(directory.resolve("game.log").toFile()).start();
        Process installer = null;
        try {
            installer = FabricUpdateService.launchInstaller(target, download, FabricUpdateInstaller.sha256(target),
                    FabricUpdateInstaller.sha256(download), game.pid());
            assertTrue(game.isAlive());
            assertTrue(installer.isAlive());
            assertEquals("old jar", Files.readString(target));
            game.getOutputStream().close();
            assertTrue(game.waitFor(20, TimeUnit.SECONDS));
            assertTrue(installer.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, installer.exitValue(), Files.readString(stage.resolve("installer.log")));
            assertEquals("new jar", Files.readString(target));
            assertTrue(Files.exists(stage.resolve("success")));
            FabricUpdateService.cleanCompleted(directory);
            assertFalse(Files.exists(stage));
            assertTrue(Files.exists(fakeGameSource), "cleanup only removes the known installer files");
        } finally {
            game.destroyForcibly();
            game.waitFor(5, TimeUnit.SECONDS);
            if (installer != null) {
                installer.destroyForcibly();
                installer.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
