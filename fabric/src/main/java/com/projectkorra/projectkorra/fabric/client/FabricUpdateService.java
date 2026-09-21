package com.projectkorra.projectkorra.fabric.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Release selection, validation and installation staging; never overwrites a loaded jar. */
final class FabricUpdateService {
    static final String DEFAULT_REPOSITORY = "MCAvatarPvP/Bending";
    private static final long MAX_JAR_BYTES = 128L * 1024 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private FabricUpdateService() { }

    static Release checkLatest(String repository, String current) throws IOException, InterruptedException {
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid updater repository");
        }
        var request = request(URI.create("https://api.github.com/repos/" + repository + "/releases/latest"))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28").GET().build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) return null;
        if (response.statusCode() != 200) throw new IOException("GitHub returned HTTP " + response.statusCode());
        return selectRelease(JsonParser.parseString(response.body()).getAsJsonObject(), repository, current);
    }

    static Release selectRelease(JsonObject json, String repository, String current) {
        if (flag(json, "draft") || flag(json, "prerelease")) return null;
        String latest = cleanVersion(string(json, "tag_name"));
        if (latest.isBlank() || compareVersions(latest, current) <= 0) return null;
        String expectedName = "ProjectKorra-" + latest + "-fabric.jar";
        if (!json.has("assets")) return null;
        for (JsonElement entry : json.getAsJsonArray("assets")) {
            JsonObject asset = entry.getAsJsonObject();
            if (!expectedName.equals(string(asset, "name")) || !"uploaded".equals(string(asset, "state"))) continue;
            String digest = string(asset, "digest");
            if (!digest.matches("sha256:[a-fA-F0-9]{64}")) {
                throw new IllegalStateException("The Fabric release is missing its SHA-256 checksum");
            }
            long size = asset.get("size").getAsLong();
            if (size <= 0 || size > MAX_JAR_BYTES) throw new IllegalStateException("Invalid Fabric asset size");
            URI download = URI.create(string(asset, "browser_download_url"));
            if (!"https".equalsIgnoreCase(download.getScheme()) || !"github.com".equalsIgnoreCase(download.getHost())
                    || download.getUserInfo() != null || download.getPort() != -1
                    || !download.getPath().startsWith("/" + repository + "/releases/download/")) {
                throw new IllegalStateException("Invalid Fabric release download URL");
            }
            return new Release(current, latest, download, digest.substring(7), size);
        }
        return null;
    }

    static Path stage(Release release, Path directory, Map<String, String> installedVersions)
            throws IOException, InterruptedException {
        Path download = directory.resolve("update.jar");
        try {
            var response = HTTP.send(request(release.download()).timeout(Duration.ofMinutes(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(download));
            if (response.statusCode() != 200) throw new IOException("Download returned HTTP " + response.statusCode());
            verify(download, release, installedVersions);
            return download;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            Files.deleteIfExists(download);
            throw failure;
        }
    }

    static void verify(Path download, Release release, Map<String, String> installedVersions) throws IOException {
        if (Files.size(download) != release.size() || !FabricUpdateInstaller.sha256(download).equalsIgnoreCase(release.sha256())) {
            throw new IOException("The downloaded update failed checksum verification");
        }
        try (JarFile jar = new JarFile(download.toFile())) {
            JarEntry metadata = jar.getJarEntry("fabric.mod.json");
            if (metadata == null || metadata.getSize() > 1024 * 1024) throw new IOException("Not a Fabric mod jar");
            try (var reader = new InputStreamReader(jar.getInputStream(metadata), StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                if (!"projectkorra".equals(string(json, "id"))
                        || !cleanVersion(string(json, "version")).equals(release.latestVersion())) {
                    throw new IOException("The downloaded mod identity or version does not match the release");
                }
                if ("server".equals(string(json, "environment"))) throw new IOException("This update requires a server");
                JsonObject depends = json.getAsJsonObject("depends");
                if (depends == null || !depends.has("minecraft") || !depends.has("fabricloader")) {
                    throw new IOException("The update is missing Minecraft/Loader compatibility requirements");
                }
                for (var dependency : depends.entrySet()) {
                    String installed = installedVersions.get(dependency.getKey());
                    if (installed == null || !matches(dependency.getValue(), installed)) {
                        throw new IOException("Update requires " + dependency.getKey() + " " + dependency.getValue());
                    }
                }
                JsonObject breaks = json.getAsJsonObject("breaks");
                if (breaks != null) {
                    for (var incompatible : breaks.entrySet()) {
                        String installed = installedVersions.get(incompatible.getKey());
                        if (installed != null && matches(incompatible.getValue(), installed)) {
                            throw new IOException("Update is incompatible with " + incompatible.getKey() + " " + installed);
                        }
                    }
                }
            }
        }
    }

    private static boolean matches(JsonElement predicates, String installed) throws IOException {
        try {
            Version version = Version.parse(installed);
            if (predicates.isJsonArray()) {
                for (JsonElement alternative : predicates.getAsJsonArray()) {
                    if (VersionPredicate.parse(alternative.getAsString()).test(version)) return true;
                }
                return false;
            }
            return VersionPredicate.parse(predicates.getAsString()).test(version);
        } catch (VersionParsingException invalid) {
            throw new IOException("Invalid update dependency version", invalid);
        }
    }

    static Process launchInstaller(Path installed, Path download, String oldHash, String newHash, long parentPid)
            throws IOException, InterruptedException {
        Path directory = download.getParent();
        Path helper = directory.resolve("installer.jar");
        writeInstaller(helper);
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "javaw.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Process process = new ProcessBuilder(java.toString(), "-jar", helper.toString(), Long.toString(parentPid),
                installed.toString(), download.toString(), oldHash, newHash)
                .redirectErrorStream(true).redirectOutput(directory.resolve("installer.log").toFile()).start();
        try {
            for (int attempt = 0; attempt < 100; attempt++) {
                if (Files.exists(directory.resolve("failure"))) {
                    throw new IOException("Update installer failed: " + Files.readString(directory.resolve("failure")));
                }
                if (Files.exists(directory.resolve("ready"))) return process;
                if (!process.isAlive()) throw new IOException("Update installer stopped; see " + directory.resolve("installer.log"));
                Thread.sleep(50);
            }
            throw new IOException("Update installer did not start");
        } catch (IOException | InterruptedException failed) {
            process.destroy();
            throw failed;
        }
    }

    static void writeInstaller(Path helper) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FabricUpdateInstaller.class.getName());
        String resource = FabricUpdateInstaller.class.getName().replace('.', '/') + ".class";
        try (var code = FabricUpdateInstaller.class.getResourceAsStream("/" + resource);
             var jar = new JarOutputStream(Files.newOutputStream(helper), manifest)) {
            if (code == null) throw new IOException("Update installer is missing from this mod");
            jar.putNextEntry(new JarEntry(resource));
            code.transferTo(jar);
            jar.closeEntry();
        }
    }

    /** Only remove our known files from completed installations, after a successful relaunch. */
    static void cleanCompleted(Path updates, Path installedJar) throws IOException {
        if (!Files.isDirectory(updates)) return;
        String loadedHash = FabricUpdateInstaller.sha256(installedJar);
        try (var directories = Files.list(updates)) {
            for (Path directory : directories.toList()) {
                if (!Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(directory.resolve("success"))) continue;
                if (!Files.readString(directory.resolve("success")).trim().equalsIgnoreCase(loadedHash)) {
                    System.err.println("[ProjectKorraUpdater] Previous update was not loaded; keeping diagnostics in " + directory);
                    continue;
                }
                for (String name : List.of("update.jar", "previous.jar.backup", "installer.jar", "installer.log", "ready", "success")) {
                    Files.deleteIfExists(directory.resolve(name));
                }
                try (var remaining = Files.list(directory)) {
                    if (remaining.findAny().isPresent()) continue;
                }
                Files.delete(directory);
            }
        }
    }

    static int compareVersions(String left, String right) {
        try {
            return Version.parse(cleanVersion(left)).compareTo(Version.parse(cleanVersion(right)));
        } catch (VersionParsingException invalid) {
            throw new IllegalArgumentException("Invalid release version", invalid);
        }
    }

    private static HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).header("User-Agent", "ProjectKorra-Fabric-Updater");
    }

    private static String cleanVersion(String value) { return value == null ? "" : value.trim().replaceFirst("^[vV]", ""); }
    private static boolean flag(JsonObject object, String key) { return object.has(key) && object.get(key).getAsBoolean(); }
    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    record Release(String currentVersion, String latestVersion, URI download, String sha256, long size) { }
}
