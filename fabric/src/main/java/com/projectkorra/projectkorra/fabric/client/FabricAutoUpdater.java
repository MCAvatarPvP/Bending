package com.projectkorra.projectkorra.fabric.client;

import com.projectkorra.projectkorra.fabric.client.FabricUpdateService.Release;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Downloads compatible updates in-game and installs them once Minecraft exits. */
public final class FabricAutoUpdater {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static volatile Release available;
    private static Path installedJar;
    private static Path updates;
    private static Map<String, String> installedVersions;
    private static boolean promptShown;

    private FabricAutoUpdater() { }

    public static void initialize() {
        FabricLoader loader = FabricLoader.getInstance();
        if (!Boolean.parseBoolean(System.getProperty("projectkorra.updater.enabled", "true"))
                || loader.isDevelopmentEnvironment() || !INITIALIZED.compareAndSet(false, true)) return;
        ModContainer mod = loader.getModContainer("projectkorra").orElseThrow();
        if (mod.getOrigin().getKind() != ModOrigin.Kind.PATH || mod.getOrigin().getPaths().size() != 1) return;
        try {
            installedJar = mod.getOrigin().getPaths().getFirst().toRealPath();
            if (!Files.isRegularFile(installedJar) || !installedJar.getFileName().toString().endsWith(".jar")) return;
        } catch (IOException failure) {
            System.err.println("[ProjectKorraUpdater] Cannot locate installed jar: " + failure.getMessage());
            return;
        }
        updates = loader.getConfigDir().resolve("projectkorra/updater").toAbsolutePath();
        Map<String, String> versions = new HashMap<>();
        for (ModContainer container : loader.getAllMods()) {
            String version = container.getMetadata().getVersion().getFriendlyString();
            versions.put(container.getMetadata().getId(), version);
            container.getMetadata().getProvides().forEach(alias -> versions.put(alias, version));
        }
        versions.putIfAbsent("java", Runtime.version().toString());
        installedVersions = Map.copyOf(versions);
        String current = mod.getMetadata().getVersion().getFriendlyString();
        String repository = System.getProperty("projectkorra.updater.repository", FabricUpdateService.DEFAULT_REPOSITORY).trim();
        CompletableFuture.supplyAsync(() -> {
            try {
                FabricUpdateService.cleanCompleted(updates);
                return FabricUpdateService.checkLatest(repository, current);
            } catch (IOException | InterruptedException failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException(failure);
            }
        }).thenAccept(release -> available = release).exceptionally(failure -> {
            System.err.println("[ProjectKorraUpdater] Update check failed: " + rootMessage(failure));
            return null;
        });
        ClientTickEvents.END_CLIENT_TICK.register(FabricAutoUpdater::tick);
    }

    private static void tick(MinecraftClient client) {
        Release release = available;
        if (promptShown || release == null || client.world != null || !(client.currentScreen instanceof TitleScreen)) return;
        promptShown = true;
        client.setScreen(new UpdatePromptScreen(client.currentScreen, release));
    }

    static int compareVersions(String left, String right) {
        return FabricUpdateService.compareVersions(left, right);
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static final class UpdatePromptScreen extends Screen {
        private final Screen parent;
        private final Release release;
        private boolean downloading;
        private boolean staged;
        private String status = "Install this update automatically, then relaunch Minecraft.";
        private ButtonWidget installButton;
        private ButtonWidget laterButton;

        private UpdatePromptScreen(Screen parent, Release release) {
            super(Text.literal("ProjectKorra update available"));
            this.parent = parent;
            this.release = release;
        }

        @Override protected void init() {
            int y = height / 2 + 40;
            installButton = addDrawableChild(ButtonWidget.builder(Text.literal(staged ? "Quit to apply update" : "Install update"), button -> {
                if (staged) client.scheduleStop();
                else install();
            }).dimensions(width / 2 - 154, y, 150, 20).build());
            laterButton = addDrawableChild(ButtonWidget.builder(Text.literal(staged ? "Keep playing" : "Not now"), button -> close())
                    .dimensions(width / 2 + 4, y, 150, 20).build());
            installButton.active = !downloading;
            laterButton.active = !downloading;
        }

        private void install() {
            if (downloading || staged) return;
            downloading = true;
            status = "Downloading and verifying update...";
            installButton.active = false;
            laterButton.active = false;
            MinecraftClient game = client;
            CompletableFuture.runAsync(() -> {
                try {
                    Files.createDirectories(updates);
                    Path directory = Files.createTempDirectory(updates, "update-");
                    String oldHash = FabricUpdateInstaller.sha256(installedJar);
                    Path download = FabricUpdateService.stage(release, directory, installedVersions);
                    FabricUpdateService.launchInstaller(installedJar, download, oldHash, release.sha256(), ProcessHandle.current().pid());
                } catch (IOException | InterruptedException failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    throw new CompletionException(failure);
                }
            }).whenComplete((ignored, failure) -> game.execute(() -> {
                downloading = false;
                if (failure == null) {
                    staged = true;
                    status = "Update ready. It will install when Minecraft closes.";
                    installButton.setMessage(Text.literal("Quit to apply update"));
                    laterButton.setMessage(Text.literal("Keep playing"));
                } else {
                    status = "Update failed: " + rootMessage(failure);
                    System.err.println("[ProjectKorraUpdater] " + status);
                    installButton.setMessage(Text.literal("Retry update"));
                }
                installButton.active = true;
                laterButton.active = true;
            }));
        }

        @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 60, 0xFFFFFFFF);
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("Installed: " + release.currentVersion() + "  |  Available: " + release.latestVersion()),
                    width / 2, height / 2 - 38, 0xFFD0D0D0);
            int y = height / 2 - 15;
            for (var line : textRenderer.wrapLines(Text.literal(status), Math.max(100, width - 40))) {
                context.drawCenteredTextWithShadow(textRenderer, line, width / 2, y, 0xFFA0A0A0);
                y += 12;
            }
        }

        @Override public boolean shouldCloseOnEsc() { return !downloading; }
        @Override public void close() { if (!downloading) client.setScreen(parent); }
    }
}
