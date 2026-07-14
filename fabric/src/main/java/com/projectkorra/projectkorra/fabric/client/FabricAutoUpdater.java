package com.projectkorra.projectkorra.fabric.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigInteger;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarFile;

/** Checksum-verified updater for the Fabric prediction client. */
public final class FabricAutoUpdater {
    private static final String MOD_ID = "projectkorra";
    private static final String DEFAULT_REPOSITORY = "MCAvatarPvP/Bending";
    private static final String API_VERSION = "2022-11-28";
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_LIGHT = 0xFFD0D0D0;
    private static final int TEXT_DIM = 0xFFA0A0A0;
    private static final int TEXT_SUCCESS = 0xFF55FF55;
    private static final int TEXT_ERROR = 0xFFFF5555;
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static volatile Release available;
    private static volatile boolean promptShown;
    private static volatile int ticks;

    private FabricAutoUpdater() { }

    public static void initialize() {
        if (!Boolean.parseBoolean(System.getProperty("projectkorra.updater.enabled", "true"))
                || !INITIALIZED.compareAndSet(false, true)) return;
        System.out.println("[ProjectKorraUpdater] Checking " + DEFAULT_REPOSITORY + " releases for updates");
        CompletableFuture.supplyAsync(FabricAutoUpdater::checkLatest)
                .thenAccept(release -> available = release)
                .exceptionally(failure -> {
                    System.err.println("[ProjectKorraUpdater] Update check failed: " + rootMessage(failure));
                    return null;
                });
        ClientTickEvents.END_CLIENT_TICK.register(FabricAutoUpdater::tick);
    }

    private static void tick(MinecraftClient client) {
        ticks++;
        Release release = available;
        if (promptShown || release == null || ticks < 40 || client.currentScreen == null) return;
        promptShown = true;
        client.setScreen(new UpdatePromptScreen(client.currentScreen, release));
    }

    private static Release checkLatest() {
        ModContainer mod = FabricLoader.getInstance().getModContainer(MOD_ID)
                .orElseThrow(() -> new IllegalStateException("ProjectKorra mod container is unavailable"));
        String current = mod.getMetadata().getVersion().getFriendlyString();
        String repository = System.getProperty("projectkorra.updater.repository", DEFAULT_REPOSITORY).trim();
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid updater repository");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repository + "/releases/latest"))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", "ProjectKorra-Fabric-Updater/" + current)
                .GET().build();
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new CompletionException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CompletionException(exception);
        }
        if (response.statusCode() != 200) throw new IllegalStateException("GitHub returned HTTP " + response.statusCode());

        JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
        String latest = cleanVersion(string(release, "tag_name"));
        if (latest.isBlank() || compareVersions(latest, cleanVersion(current)) <= 0) return null;
        JsonArray assets = release.getAsJsonArray("assets");
        if (assets == null) throw new IllegalStateException("Latest release has no assets");
        for (JsonElement element : assets) {
            JsonObject asset = element.getAsJsonObject();
            String name = string(asset, "name");
            if (!name.toLowerCase(Locale.ROOT).endsWith("-fabric.jar")
                    || name.toLowerCase(Locale.ROOT).contains("sources")) continue;
            String digest = string(asset, "digest");
            if (!digest.startsWith("sha256:") || digest.length() != 71) {
                throw new IllegalStateException("Fabric release asset is missing its GitHub SHA-256 digest");
            }
            URI download = URI.create(string(asset, "browser_download_url"));
            if (!"https".equalsIgnoreCase(download.getScheme())
                    || !(download.getHost().equalsIgnoreCase("github.com")
                    || download.getHost().endsWith(".githubusercontent.com"))) {
                throw new IllegalStateException("Release asset has an untrusted download host");
            }
            long size = asset.has("size") ? asset.get("size").getAsLong() : -1L;
            return new Release(current, latest, name, download, digest.substring(7).toLowerCase(Locale.ROOT), size,
                    URI.create(string(release, "html_url")));
        }
        throw new IllegalStateException("Latest release does not contain a *-fabric.jar asset");
    }

    private static CompletableFuture<Path> download(Release release, DownloadState state) {
        return CompletableFuture.supplyAsync(() -> {
            Path staged = null;
            try {
                Path currentJar = currentJar();
                Path mods = currentJar.getParent();
                Files.createDirectories(mods);
                staged = mods.resolve("." + safeName(release.assetName) + ".download");
                Files.deleteIfExists(staged);
                HttpRequest request = HttpRequest.newBuilder(release.download)
                        .timeout(Duration.ofMinutes(5))
                        .header("Accept", "application/octet-stream")
                        .header("User-Agent", "ProjectKorra-Fabric-Updater/" + release.currentVersion)
                        .GET().build();
                HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) throw new IOException("Download returned HTTP " + response.statusCode());
                long total = response.headers().firstValueAsLong("Content-Length").orElse(release.size);
                state.total.set(total);
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                try (InputStream raw = response.body(); DigestInputStream input = new DigestInputStream(raw, sha256);
                     var output = Files.newOutputStream(staged, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (state.cancelled.get()) throw new IOException("Download cancelled");
                        output.write(buffer, 0, read);
                        state.downloaded.addAndGet(read);
                    }
                }
                String actual = HexFormat.of().formatHex(sha256.digest());
                if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                        release.sha256.getBytes(StandardCharsets.US_ASCII))) {
                    throw new SecurityException("SHA-256 verification failed");
                }
                if (release.size >= 0 && Files.size(staged) != release.size) throw new IOException("Downloaded size is incorrect");
                verifyFabricJar(staged);
                return staged;
            } catch (Exception exception) {
                if (staged != null) try { Files.deleteIfExists(staged); } catch (IOException ignored) { }
                throw new CompletionException(exception);
            }
        });
    }

    private static void verifyFabricJar(Path jar) throws IOException {
        try (JarFile archive = new JarFile(jar.toFile(), true)) {
            if (archive.getEntry("fabric.mod.json") == null) throw new IOException("Downloaded file is not a Fabric mod");
        }
    }

    private static Path currentJar() {
        ModContainer mod = FabricLoader.getInstance().getModContainer(MOD_ID)
                .orElseThrow(() -> new IllegalStateException("ProjectKorra mod container is unavailable"));
        Optional<Path> jar = mod.getOrigin().getPaths().stream()
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                .findFirst();
        return jar.orElseThrow(() -> new IllegalStateException("Automatic installation is disabled in a development environment"));
    }

    private static void installAfterExit(Path staged, String assetName) {
        try {
            Path current = currentJar();
            Path target = current.resolveSibling(safeName(assetName));
            long pid = ProcessHandle.current().pid();
            Path script;
            ProcessBuilder process;
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                script = Files.createTempFile("projectkorra-update-", ".ps1");
                Path failureLog = current.resolveSibling(current.getFileName() + ".update-error.log");
                String body = """
                        param(
                            [long]$MinecraftProcessId,
                            [string]$StagedJar,
                            [string]$CurrentJar,
                            [string]$TargetJar,
                            [string]$FailureLog
                        )
                        $ErrorActionPreference = 'Stop'
                        try {
                            if (Get-Process -Id $MinecraftProcessId -ErrorAction SilentlyContinue) {
                                Wait-Process -Id $MinecraftProcessId -ErrorAction SilentlyContinue
                            }
                            $backup = "$TargetJar.previous"
                            $installed = $false
                            for ($attempt = 0; $attempt -lt 80; $attempt++) {
                                try {
                                    if (Test-Path -LiteralPath $backup) {
                                        Remove-Item -LiteralPath $backup -Force
                                    }
                                    if (Test-Path -LiteralPath $TargetJar) {
                                        [System.IO.File]::Replace($StagedJar, $TargetJar, $backup, $true)
                                    } else {
                                        Move-Item -LiteralPath $StagedJar -Destination $TargetJar -Force
                                    }
                                    $installed = $true
                                    break
                                } catch {
                                    Start-Sleep -Milliseconds 250
                                }
                            }
                            if (-not $installed) {
                                throw "Could not install the new ProjectKorra jar after 20 seconds."
                            }
                            if ($CurrentJar -ne $TargetJar) {
                                try {
                                    Remove-Item -LiteralPath $CurrentJar -Force
                                } catch {
                                    Remove-Item -LiteralPath $TargetJar -Force -ErrorAction SilentlyContinue
                                    if (Test-Path -LiteralPath $backup) {
                                        Move-Item -LiteralPath $backup -Destination $TargetJar -Force
                                    }
                                    throw "Installed the new jar, but could not remove the old ProjectKorra jar: $($_.Exception.Message)"
                                }
                            }
                            if (Test-Path -LiteralPath $backup) {
                                Remove-Item -LiteralPath $backup -Force
                            }
                            Remove-Item -LiteralPath $FailureLog -Force -ErrorAction SilentlyContinue
                        } catch {
                            ($_ | Out-String) | Set-Content -LiteralPath $FailureLog -Encoding UTF8
                            exit 1
                        } finally {
                            Remove-Item -LiteralPath $PSCommandPath -Force -ErrorAction SilentlyContinue
                        }
                        """;
                Files.writeString(script, body, StandardCharsets.UTF_8);
                process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                        "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", script.toString(),
                        Long.toString(pid), staged.toAbsolutePath().toString(), current.toAbsolutePath().toString(),
                        target.toAbsolutePath().toString(),
                        failureLog.toAbsolutePath().toString());
            } else {
                script = Files.createTempFile("projectkorra-update-", ".sh");
                Path failureLog = current.resolveSibling(current.getFileName() + ".update-error.log");
                String body = "#!/bin/sh\n"
                        + "pid=" + pid + "\n"
                        + "staged=" + sh(staged) + "\n"
                        + "current=" + sh(current) + "\n"
                        + "target=" + sh(target) + "\n"
                        + "failure=" + sh(failureLog) + "\n"
                        + "backup=\"${target}.previous\"\n"
                        + "fail() { printf '%s\\n' \"$1\" > \"$failure\"; rm -f \"$0\"; exit 1; }\n"
                        + "while kill -0 \"$pid\" 2>/dev/null; do sleep 1; done\n"
                        + "installed=0\n"
                        + "attempt=0\n"
                        + "while [ \"$attempt\" -lt 20 ]; do\n"
                        + "  rm -f \"$backup\"\n"
                        + "  if [ -e \"$target\" ]; then\n"
                        + "    if mv -f \"$target\" \"$backup\" && mv -f \"$staged\" \"$target\"; then installed=1; break; fi\n"
                        + "    if [ -e \"$backup\" ]; then mv -f \"$backup\" \"$target\"; fi\n"
                        + "  elif mv -f \"$staged\" \"$target\"; then installed=1; break; fi\n"
                        + "  attempt=$((attempt + 1))\n"
                        + "  sleep 1\n"
                        + "done\n"
                        + "[ \"$installed\" -eq 1 ] || fail 'Could not install the new ProjectKorra jar after 20 seconds.'\n"
                        + "if [ \"$current\" != \"$target\" ] && ! rm -f \"$current\"; then\n"
                        + "  rm -f \"$target\"\n"
                        + "  if [ -e \"$backup\" ]; then mv -f \"$backup\" \"$target\"; fi\n"
                        + "  fail 'Installed the new jar, but could not remove the old ProjectKorra jar.'\n"
                        + "fi\n"
                        + "rm -f \"$backup\" \"$failure\" \"$0\"\n";
                Files.writeString(script, body, StandardCharsets.UTF_8);
                process = new ProcessBuilder("sh", script.toString());
            }
            process.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not stage the update installer", exception);
        }
    }

    private static String sh(Path path) { return "'" + path.toAbsolutePath().toString().replace("'", "'\\''") + "'"; }
    private static String safeName(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static String string(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }
    private static String cleanVersion(String value) { return value == null ? "" : value.trim().replaceFirst("^[vV]", ""); }

    static int compareVersions(String left, String right) {
        List<String> a = versionParts(left); List<String> b = versionParts(right);
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            String x = i < a.size() ? a.get(i) : "0"; String y = i < b.size() ? b.get(i) : "0";
            boolean xn = x.matches("\\d+"); boolean yn = y.matches("\\d+");
            int compared = xn && yn ? new BigInteger(x).compareTo(new BigInteger(y))
                    : xn != yn ? (xn ? 1 : -1) : x.compareToIgnoreCase(y);
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static List<String> versionParts(String version) {
        String[] split = cleanVersion(version).split("[.+_-]");
        List<String> result = new ArrayList<>();
        for (String part : split) if (!part.isBlank()) result.add(part);
        return result;
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private record Release(String currentVersion, String latestVersion, String assetName, URI download,
                           String sha256, long size, URI releasePage) { }

    private static final class DownloadState {
        final AtomicLong downloaded = new AtomicLong();
        final AtomicLong total = new AtomicLong(-1L);
        final AtomicBoolean cancelled = new AtomicBoolean();
    }

    private static final class UpdatePromptScreen extends Screen {
        private final Screen parent;
        private final Release release;
        private UpdatePromptScreen(Screen parent, Release release) {
            super(Text.literal("ProjectKorra update available")); this.parent = parent; this.release = release;
        }
        @Override protected void init() {
            int y = height / 2 + 28;
            addDrawableChild(ButtonWidget.builder(Text.literal("Download update"), button -> {
                DownloadState state = new DownloadState();
                DownloadScreen screen = new DownloadScreen(parent, release, state);
                client.setScreen(screen);
                screen.start();
            }).dimensions(width / 2 - 154, y, 150, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Not now"), button -> client.setScreen(parent))
                    .dimensions(width / 2 + 4, y, 150, 20).build());
        }
        @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 42, TEXT_WHITE);
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("Installed: " + release.currentVersion + "  •  Available: " + release.latestVersion),
                    width / 2, height / 2 - 18, TEXT_LIGHT);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("The update will only install after you approve closing the game."),
                    width / 2, height / 2 + 2, TEXT_DIM);
        }
        @Override public void close() { client.setScreen(parent); }
    }

    private static final class DownloadScreen extends Screen {
        private final Screen parent; private final Release release; private final DownloadState state;
        private boolean started; private volatile String failure;
        private DownloadScreen(Screen parent, Release release, DownloadState state) {
            super(Text.literal("Downloading ProjectKorra " + release.latestVersion));
            this.parent = parent; this.release = release; this.state = state;
        }
        private void start() {
            if (started) return; started = true;
            download(release, state).whenComplete((path, error) -> MinecraftClient.getInstance().execute(() -> {
                if (error != null) {
                    failure = rootMessage(error);
                    clearAndInit();
                } else {
                    client.setScreen(new InstallConfirmationScreen(parent, release, path));
                }
            }));
        }
        @Override protected void init() {
            if (failure != null) {
                addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button -> client.setScreen(parent))
                        .dimensions(width / 2 - 75, height / 2 + 42, 150, 20).build());
            } else {
                addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> {
                    state.cancelled.set(true); client.setScreen(parent);
                }).dimensions(width / 2 - 75, height / 2 + 42, 150, 20).build());
            }
        }
        @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 54, TEXT_WHITE);
            if (failure != null) {
                context.drawCenteredTextWithShadow(textRenderer, Text.literal("Download failed"), width / 2, height / 2 - 18, TEXT_ERROR);
                context.drawCenteredTextWithShadow(textRenderer, Text.literal(failure), width / 2, height / 2 + 2, TEXT_LIGHT);
                return;
            }
            long downloaded = state.downloaded.get(), total = state.total.get();
            double progress = total > 0 ? Math.min(1.0, downloaded / (double) total) : 0.0;
            int left = width / 2 - 150, top = height / 2 - 12, right = width / 2 + 150;
            context.fill(left, top, right, top + 14, 0xFF303030);
            context.fill(left + 2, top + 2, left + 2 + (int) (296 * progress), top + 12, 0xFF55AAFF);
            String amount = formatBytes(downloaded) + (total > 0 ? " / " + formatBytes(total) : "");
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(amount), width / 2, top + 20, TEXT_LIGHT);
        }
        @Override public void close() { state.cancelled.set(true); client.setScreen(parent); }
    }

    private static final class InstallConfirmationScreen extends Screen {
        private final Screen parent; private final Release release; private final Path staged; private String failure;
        private InstallConfirmationScreen(Screen parent, Release release, Path staged) {
            super(Text.literal("ProjectKorra update downloaded")); this.parent = parent; this.release = release; this.staged = staged;
        }
        @Override protected void init() {
            int y = height / 2 + 30;
            addDrawableChild(ButtonWidget.builder(Text.literal("Close game and install"), button -> {
                try {
                    installAfterExit(staged, release.assetName);
                    client.scheduleStop();
                } catch (RuntimeException exception) {
                    failure = rootMessage(exception); clearAndInit();
                }
            }).dimensions(width / 2 - 154, y, 150, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Later"), button -> client.setScreen(parent))
                    .dimensions(width / 2 + 4, y, 150, 20).build());
        }
        @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 48, TEXT_WHITE);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("Version " + release.latestVersion + " passed SHA-256 verification."),
                    width / 2, height / 2 - 22, TEXT_SUCCESS);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("Confirming will close Minecraft, replace the mod, and finish the update."),
                    width / 2, height / 2, TEXT_LIGHT);
            if (failure != null) context.drawCenteredTextWithShadow(textRenderer, Text.literal(failure), width / 2, height / 2 + 14, TEXT_ERROR);
        }
        @Override public void close() { client.setScreen(parent); }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes; String[] units = {"KiB", "MiB", "GiB"}; int unit = -1;
        do { value /= 1024.0; unit++; } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
