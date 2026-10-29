package com.projectkorra.projectkorra.fabric.client.config;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Persistent, client-only ProjectKorra preferences. */
public final class ClientBendingConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("ProjectKorra Client Config");
    private static final String CLIENT_SIDE_BENDING = "clientSideBending";
    private static final String CLIENT_SIDE_VISUALS = "clientSideVisuals";

    private static boolean initialized;
    private static boolean enabled = true;
    private static boolean visualsEnabled = true;

    private ClientBendingConfig() { }

    /** Resolved on first use so the class can load where Fabric has no config dir (unit tests). */
    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("projectkorra-client.properties");
    }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        final Path path = path();
        if (!Files.isRegularFile(path)) return;

        final Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            enabled = readBoolean(properties, CLIENT_SIDE_BENDING, enabled);
            visualsEnabled = readBoolean(properties, CLIENT_SIDE_VISUALS, visualsEnabled);
        } catch (IOException exception) {
            LOGGER.warn("Could not load {}", path, exception);
        }
    }

    public static synchronized boolean isEnabled() {
        return enabled;
    }

    public static synchronized void setEnabled(final boolean value) {
        if (enabled == value) return;
        enabled = value;
        save();
    }

    /**
     * Whether the predicting client draws its own particles, sounds, blocks and entities. When off,
     * gameplay prediction keeps running and Paper sends this player its authoritative visuals.
     */
    public static synchronized boolean isVisualsEnabled() {
        return visualsEnabled;
    }

    public static synchronized void setVisualsEnabled(final boolean value) {
        if (visualsEnabled == value) return;
        visualsEnabled = value;
        save();
    }

    private static boolean readBoolean(final Properties properties, final String key, final boolean fallback) {
        final String value = properties.getProperty(key);
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return Boolean.parseBoolean(value);
        return fallback;
    }

    private static void save() {
        final Path path = path();
        final Properties properties = new Properties();
        properties.setProperty(CLIENT_SIDE_BENDING, Boolean.toString(enabled));
        properties.setProperty(CLIENT_SIDE_VISUALS, Boolean.toString(visualsEnabled));
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "ProjectKorra Fabric client settings");
            }
        } catch (IOException exception) {
            LOGGER.warn("Could not save {}", path, exception);
        }
    }
}
