package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"ordinary/mods", "ModrinthApp/profiles/Fabulously Optimized (3)/mods",
            "com.modrinth.theseus/profiles/Fabulously Optimized (3)/mods"})
    void standaloneHelperWaitsForGameExitAndRunsWithoutMinecraftDependencies(String layout) throws Exception {
        Path mods = Files.createDirectories(directory.resolve(layout));
        Path target = Files.writeString(mods.resolve("mod with spaces.jar"), "old jar");
        assertNull(FabricUpdateInstaller.pandoraSource(target));
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
            FabricUpdateService.cleanCompleted(directory, target);
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

    @Test void pandoraUpdateSurvivesFolderRestoreWithoutChangingTheRunningJarOrSharedCache() throws Exception {
        Path instance = Files.createDirectories(directory.resolve("PandoraLauncher/instances/Fabulously Optimized (3)"));
        Files.writeString(instance.resolve("info_v1.json"), "{}");
        Path originals = Files.createDirectory(instance.resolve("original_mods"));
        Path mods = Files.createDirectories(instance.resolve(".minecraft/mods"));
        Path target = Files.writeString(mods.resolve("ProjectKorra-old-fabric.jar"), "old jar");
        Path cache = Files.writeString(directory.resolve("shared-cache.jar"), "old jar");
        Path source = Files.createLink(originals.resolve(target.getFileName()), cache);
        Path updates = Files.createDirectories(instance.resolve(".minecraft/config/projectkorra/updater"));
        Path stage = Files.createDirectory(updates.resolve("update-test"));
        Path download = Files.writeString(stage.resolve("update.jar"), "new jar");

        // The parent is still alive: only Pandora's inactive source copy may change.
        Process installer = FabricUpdateService.launchInstaller(target, download, FabricUpdateInstaller.sha256(target),
                FabricUpdateInstaller.sha256(download), ProcessHandle.current().pid());
        try {
            assertTrue(installer.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, installer.exitValue(), Files.readString(stage.resolve("installer.log")));
            assertEquals("new jar", Files.readString(source));
            assertEquals("old jar", Files.readString(target));
            assertEquals("old jar", Files.readString(cache));
            assertFalse(Files.isSameFile(cache, source));
            FabricUpdateService.cleanCompleted(updates, target);
            assertTrue(Files.exists(stage), "do not discard diagnostics before the updated jar is loaded");

            // Pandora deletes the runtime copy and renames original_mods back after exit.
            Files.delete(target);
            Files.delete(mods);
            Files.move(originals, mods);
            assertEquals("new jar", Files.readString(target));
            assertNull(FabricUpdateInstaller.pandoraSource(target));
            FabricUpdateService.cleanCompleted(updates, target);
            assertFalse(Files.exists(stage));
        } finally {
            installer.destroyForcibly();
            installer.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test void modrinthReplacementDoesNotChangeAHardLinkedCacheOrAnotherProfile() throws Exception {
        Path mods = Files.createDirectories(directory.resolve("ModrinthApp/profiles/Test (2)/mods"));
        Path cache = Files.writeString(directory.resolve("cached.jar"), "old jar");
        Path target = Files.createLink(mods.resolve("ProjectKorra.jar"), cache);
        Path download = Files.writeString(directory.resolve("update.jar"), "new jar");
        FabricUpdateInstaller.install(target, download, FabricUpdateInstaller.sha256(target), FabricUpdateInstaller.sha256(download));
        assertEquals("new jar", Files.readString(target));
        assertEquals("old jar", Files.readString(cache));
        assertFalse(Files.isSameFile(target, cache));
    }

    @Test void pandoraSourceChangedByTheUserIsReportedBeforeOfferingToQuit() throws Exception {
        Path instance = Files.createDirectories(directory.resolve("Pandora/instances/Test"));
        Files.writeString(instance.resolve("info_v1.json"), "{}");
        Path mods = Files.createDirectories(instance.resolve(".minecraft/mods"));
        Path originals = Files.createDirectory(instance.resolve("original_mods"));
        Path target = Files.writeString(mods.resolve("ProjectKorra.jar"), "old jar");
        Path source = Files.writeString(originals.resolve(target.getFileName()), "manually changed jar");
        Path stage = Files.createDirectory(directory.resolve("staged"));
        Path download = Files.writeString(stage.resolve("update.jar"), "new jar");
        assertThrows(java.io.IOException.class, () -> FabricUpdateService.launchInstaller(target, download,
                FabricUpdateInstaller.sha256(target), FabricUpdateInstaller.sha256(download), ProcessHandle.current().pid()));
        assertEquals("manually changed jar", Files.readString(source));
        assertEquals("old jar", Files.readString(target));
        assertTrue(Files.exists(stage.resolve("failure")));
        assertFalse(Files.exists(stage.resolve("ready")));
    }

    @Test void unrelatedOriginalModsDirectoryIsNotTreatedAsPandora() throws Exception {
        Path mods = Files.createDirectories(directory.resolve(".minecraft/mods"));
        Files.createDirectory(directory.resolve("original_mods"));
        assertNull(FabricUpdateInstaller.pandoraSource(mods.resolve("ProjectKorra.jar")));
    }
}
