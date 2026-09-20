package com.projectkorra.projectkorra.fabric.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class FabricUpdateServiceTest {
    @TempDir Path directory;
    private static final Map<String, String> INSTALLED = Map.of(
            "minecraft", "1.21.11", "fabricloader", "0.19.3", "fabric-api", "0.141.4+1.21.11", "java", "21");

    @Test void selectsOnlyThePublishedFabricJarForANewerStableRelease() {
        JsonObject json = releaseJson();
        var selected = FabricUpdateService.selectRelease(json, "MCAvatarPvP/Bending", "1.10.28");
        assertNotNull(selected);
        assertEquals("1.10.29", selected.latestVersion());
        assertTrue(selected.download().getPath().endsWith("-fabric.jar"));
        assertNull(FabricUpdateService.selectRelease(json, "MCAvatarPvP/Bending", "1.10.29"));
        assertNull(FabricUpdateService.selectRelease(json, "MCAvatarPvP/Bending", "1.11.0"));
        json.addProperty("prerelease", true);
        assertNull(FabricUpdateService.selectRelease(json, "MCAvatarPvP/Bending", "1.10.28"));
        json.addProperty("prerelease", false);
        json.addProperty("draft", true);
        assertNull(FabricUpdateService.selectRelease(json, "MCAvatarPvP/Bending", "1.10.28"));
    }

    @Test void incompleteOrWrongPlatformReleaseIsNotOffered() {
        JsonObject json = releaseJson();
        asset(json).addProperty("name", "ProjectKorra-1.10.29-bukkit.jar");
        assertNull(FabricUpdateService.selectRelease(json, "MCAvatarPvP/Bending", "1.10.28"));
        asset(json).addProperty("name", "ProjectKorra-1.10.29-fabric-sources.jar");
        assertNull(FabricUpdateService.selectRelease(json, "MCAvatarPvP/Bending", "1.10.28"));
        asset(json).addProperty("name", "ProjectKorra-1.10.29-fabric.jar");
        asset(json).addProperty("state", "starter");
        assertNull(FabricUpdateService.selectRelease(json, "MCAvatarPvP/Bending", "1.10.28"));
    }

    @Test void rejectsMissingDigestAndForeignDownloadLocations() {
        JsonObject json = releaseJson();
        asset(json).remove("digest");
        assertThrows(IllegalStateException.class, () -> FabricUpdateService.selectRelease(json, "MCAvatarPvP/Bending", "1.10.28"));
        for (String url : new String[]{"http://github.com/MCAvatarPvP/Bending/releases/download/v1.10.29/mod.jar",
                "https://github.com/other/repo/releases/download/v1.10.29/mod.jar",
                "https://example.com/MCAvatarPvP/Bending/releases/download/v1.10.29/mod.jar"}) {
            JsonObject foreign = releaseJson();
            asset(foreign).addProperty("browser_download_url", url);
            assertThrows(IllegalStateException.class, () -> FabricUpdateService.selectRelease(foreign, "MCAvatarPvP/Bending", "1.10.28"));
        }
    }

    @Test void verifiedCompatibleClientJarIsAccepted() throws Exception {
        Path jar = modJar(metadata());
        assertDoesNotThrow(() -> FabricUpdateService.verify(jar, descriptor(jar), INSTALLED));
    }

    @Test void checksumOrSizeMismatchCannotBeInstalled() throws Exception {
        Path jar = modJar(metadata());
        var expected = descriptor(jar);
        Files.writeString(jar, "partial download");
        assertThrows(IOException.class, () -> FabricUpdateService.verify(jar, expected, INSTALLED));
        var matchingSizeWrongHash = new FabricUpdateService.Release("1.10.28", "1.10.29", expected.download(),
                expected.sha256(), Files.size(jar));
        assertThrows(IOException.class, () -> FabricUpdateService.verify(jar, matchingSizeWrongHash, INSTALLED));
    }

    @Test void wrongModVersionOrMinecraftIsRejectedEvenWithMatchingChecksum() throws Exception {
        for (String field : new String[]{"id", "version", "environment", "minecraft", "fabricloader", "missing-mod"}) {
            JsonObject metadata = metadata();
            switch (field) {
                case "id" -> metadata.addProperty(field, "another_mod");
                case "version" -> metadata.addProperty(field, "1.10.30");
                case "environment" -> metadata.addProperty(field, "server");
                case "minecraft" -> metadata.getAsJsonObject("depends").addProperty(field, "=1.22");
                case "fabricloader" -> metadata.getAsJsonObject("depends").addProperty(field, ">=0.20.0");
                default -> metadata.getAsJsonObject("depends").addProperty(field, "*");
            }
            Path jar = modJar(metadata);
            assertThrows(IOException.class, () -> FabricUpdateService.verify(jar, descriptor(jar), INSTALLED), field);
        }
    }

    @Test void fabricDependencyAlternativesAndBreaksAreHonored() throws Exception {
        JsonObject metadata = metadata();
        metadata.getAsJsonObject("depends").add("minecraft", JsonParser.parseString("[\"1.21.10\", \"1.21.11\"]"));
        Path jar = modJar(metadata);
        FabricUpdateService.verify(jar, descriptor(jar), INSTALLED);
        metadata.add("breaks", JsonParser.parseString("{\"fabric-api\":\"<0.150\"}"));
        Path incompatible = modJar(metadata);
        assertThrows(IOException.class, () -> FabricUpdateService.verify(incompatible, descriptor(incompatible), INSTALLED));
    }

    private Path modJar(JsonObject metadata) throws IOException {
        Path file = Files.createTempFile(directory, "mod", ".jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(file))) {
            jar.putNextEntry(new JarEntry("fabric.mod.json"));
            jar.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return file;
    }

    private static FabricUpdateService.Release descriptor(Path jar) throws IOException {
        return new FabricUpdateService.Release("1.10.28", "1.10.29", URI.create("https://github.com/unused"),
                FabricUpdateInstaller.sha256(jar), Files.size(jar));
    }

    private static JsonObject metadata() {
        return JsonParser.parseString("""
                {"schemaVersion":1,"id":"projectkorra","version":"1.10.29",
                 "depends":{"minecraft":"=1.21.11","fabricloader":">=0.16.0","fabric-api":"*","java":">=21"}}
                """).getAsJsonObject();
    }

    private static JsonObject releaseJson() {
        return JsonParser.parseString("""
                {"tag_name":"v1.10.29","draft":false,"prerelease":false,"assets":[
                 {"name":"ProjectKorra-1.10.29-bukkit.jar","state":"uploaded"},
                 {"name":"ProjectKorra-1.10.29-fabric.jar","state":"uploaded","size":1024,
                  "digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "browser_download_url":"https://github.com/MCAvatarPvP/Bending/releases/download/v1.10.29/ProjectKorra-1.10.29-fabric.jar"}]}
                """).getAsJsonObject();
    }

    private static JsonObject asset(JsonObject json) { return json.getAsJsonArray("assets").get(1).getAsJsonObject(); }
}
