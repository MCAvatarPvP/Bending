package com.projectkorra.projectkorra.fabric.client;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Standalone, JDK-only helper: the running game must release its jar before replacement. */
public final class FabricUpdateInstaller {
    private FabricUpdateInstaller() { }

    public static void main(String[] args) {
        if (args.length != 5) throw new IllegalArgumentException("Expected PID, installed jar, download, old hash, new hash");
        Path target = Path.of(args[1]).toAbsolutePath().normalize();
        Path download = Path.of(args[2]).toAbsolutePath().normalize();
        Path directory = download.getParent();
        try {
            ProcessHandle parent = ProcessHandle.of(Long.parseLong(args[0])).orElse(null);
            Files.writeString(directory.resolve("ready"), "Waiting for Minecraft to exit");
            if (parent != null) parent.onExit().join();
            // Windows launchers/virus scanners can briefly retain a handle after exit.
            IOException last = null;
            for (int attempt = 0; attempt < 60; attempt++) {
                try {
                    install(target, download, args[3], args[4]);
                    Files.writeString(directory.resolve("success"), "Installed " + target.getFileName());
                    return;
                } catch (IOException locked) {
                    last = locked;
                    Thread.sleep(1000);
                }
            }
            throw last;
        } catch (Exception failure) {
            failure.printStackTrace();
            try {
                Files.writeString(directory.resolve("failure"), failure.toString());
            } catch (IOException ignored) { }
            System.exit(1);
        }
    }

    static void install(Path target, Path download, String oldHash, String newHash) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(download, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("The installed jar or download is missing or is a symbolic link");
        }
        if (!sha256(download).equalsIgnoreCase(newHash)) {
            throw new IllegalStateException("The staged update checksum changed");
        }
        // A launcher or user may have changed the installation while the game was open.
        String currentHash = sha256(target);
        if (currentHash.equalsIgnoreCase(newHash)) return;
        if (!currentHash.equalsIgnoreCase(oldHash)) {
            throw new IllegalStateException("The installed mod changed; leaving it untouched");
        }

        Path backup = download.resolveSibling("previous.jar.backup");
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        // Copy onto the target filesystem first, so the final rename can be atomic.
        Path replacement = Files.createTempFile(target.getParent(), ".projectkorra-update-", ".tmp");
        try {
            Files.copy(download, replacement, StandardCopyOption.REPLACE_EXISTING);
            if (!sha256(replacement).equalsIgnoreCase(newHash)) throw new IOException("Copy verification failed");
            try {
                Files.move(replacement, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failed) {
            if (!Files.exists(target)) {
                Files.copy(backup, target);
            }
            throw failed;
        } finally {
            Files.deleteIfExists(replacement);
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
