package com.projectkorra.projectkorra.fabric.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only release notifier for the Fabric prediction client. */
public final class FabricAutoUpdater {
    private static final String MOD_ID = "projectkorra";
    private static final String DEFAULT_REPOSITORY = "MCAvatarPvP/Bending";
    private static final String API_VERSION = "2022-11-28";
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_LIGHT = 0xFFD0D0D0;
    private static final int TEXT_DIM = 0xFFA0A0A0;
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
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub returned HTTP " + response.statusCode());
        }

        JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
        String latest = cleanVersion(string(release, "tag_name"));
        if (latest.isBlank() || compareVersions(latest, cleanVersion(current)) <= 0) return null;

        URI releasePage = URI.create(string(release, "html_url"));
        if (!"https".equalsIgnoreCase(releasePage.getScheme())
                || !"github.com".equalsIgnoreCase(releasePage.getHost())) {
            throw new IllegalStateException("GitHub returned an invalid release page");
        }
        return new Release(current, latest, releasePage);
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static String cleanVersion(String value) {
        return value == null ? "" : value.trim().replaceFirst("^[vV]", "");
    }

    static int compareVersions(String left, String right) {
        List<String> a = versionParts(left);
        List<String> b = versionParts(right);
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            String x = i < a.size() ? a.get(i) : "0";
            String y = i < b.size() ? b.get(i) : "0";
            boolean xn = x.matches("\\d+");
            boolean yn = y.matches("\\d+");
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

    private record Release(String currentVersion, String latestVersion, URI releasePage) { }

    private static final class UpdatePromptScreen extends Screen {
        private final Screen parent;
        private final Release release;

        private UpdatePromptScreen(Screen parent, Release release) {
            super(Text.literal("ProjectKorra update available"));
            this.parent = parent;
            this.release = release;
        }

        @Override
        protected void init() {
            int y = height / 2 + 30;
            addDrawableChild(ButtonWidget.builder(Text.literal("Open GitHub Releases"), button -> {
                Util.getOperatingSystem().open(release.releasePage);
                client.setScreen(parent);
            }).dimensions(width / 2 - 154, y, 150, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Not now"), button -> client.setScreen(parent))
                    .dimensions(width / 2 + 4, y, 150, 20).build());
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 48, TEXT_WHITE);
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("Installed: " + release.currentVersion + "  |  Available: " + release.latestVersion),
                    width / 2, height / 2 - 22, TEXT_LIGHT);
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("ProjectKorra will not download files or change your mods folder."),
                    width / 2, height / 2, TEXT_DIM);
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("Use the official release page to download and install the update yourself."),
                    width / 2, height / 2 + 12, TEXT_DIM);
        }

        @Override
        public void close() {
            client.setScreen(parent);
        }
    }
}
