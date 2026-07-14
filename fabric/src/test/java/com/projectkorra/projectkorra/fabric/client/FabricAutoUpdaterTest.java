package com.projectkorra.projectkorra.fabric.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricAutoUpdaterTest {
    @Test
    void semanticVersionsAreComparedNumerically() {
        assertTrue(FabricAutoUpdater.compareVersions("1.10.10", "1.10.2") > 0);
        assertTrue(FabricAutoUpdater.compareVersions("v2.0.0", "1.99.99") > 0);
        assertEquals(0, FabricAutoUpdater.compareVersions("v1.10.2", "1.10.2"));
    }

    @Test
    void stableReleaseSortsAfterPrerelease() {
        assertTrue(FabricAutoUpdater.compareVersions("1.10.3", "1.10.3-beta.2") > 0);
        assertTrue(FabricAutoUpdater.compareVersions("1.10.3-beta.10", "1.10.3-beta.2") > 0);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsInstallerReplacesTheOldJar(@TempDir Path temporaryDirectory) throws Exception {
        Path mods = Files.createDirectories(temporaryDirectory.resolve("mods folder (test)"));
        Path staged = Files.writeString(mods.resolve(".ProjectKorra-2.0-fabric.jar.download"), "new");
        Path current = Files.writeString(mods.resolve("ProjectKorra-1.0-fabric.jar"), "old");
        Path target = mods.resolve("ProjectKorra-2.0-fabric.jar");
        Path failure = mods.resolve("ProjectKorra-1.0-fabric.jar.update-error.log");
        Path script = Files.writeString(temporaryDirectory.resolve("projectkorra update test.ps1"),
                FabricAutoUpdater.windowsInstallerScript(), StandardCharsets.UTF_8);

        Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", script.toString(), "2147483646",
                staged.toString(), current.toString(), target.toString(), failure.toString())
                .redirectErrorStream(true).start();
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(finished, "Installer did not finish: " + output);
        assertEquals(0, process.exitValue(), output);
        assertEquals("new", Files.readString(target));
        assertFalse(Files.exists(current));
        assertFalse(Files.exists(failure));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsInstallerIsDetachedThroughTheProcessService(@TempDir Path temporaryDirectory) throws Exception {
        Path mods = Files.createDirectories(temporaryDirectory.resolve("detached mods folder (test)"));
        Path staged = Files.writeString(mods.resolve(".ProjectKorra-3.0-fabric.jar.download"), "detached-new");
        Path current = Files.writeString(mods.resolve("ProjectKorra-2.0-fabric.jar"), "detached-old");
        Path target = mods.resolve("ProjectKorra-3.0-fabric.jar");
        Path failure = mods.resolve("ProjectKorra-2.0-fabric.jar.update-error.log");
        Path installer = Files.writeString(temporaryDirectory.resolve("detached installer.ps1"),
                FabricAutoUpdater.windowsInstallerScript(), StandardCharsets.UTF_8);
        Path launcher = Files.writeString(temporaryDirectory.resolve("detached launcher.ps1"),
                FabricAutoUpdater.windowsDetachedLauncherScript(), StandardCharsets.UTF_8);

        Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", launcher.toString(), installer.toString(), "2147483646",
                staged.toString(), current.toString(), target.toString(), failure.toString())
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Detached launcher did not finish");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output + (Files.exists(failure) ? Files.readString(failure) : ""));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(target) && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(Files.exists(target), Files.exists(failure) ? Files.readString(failure) : "Detached installer did not create the target jar");
        assertEquals("detached-new", Files.readString(target));
        assertFalse(Files.exists(current));
        assertFalse(Files.exists(failure));
    }
}
