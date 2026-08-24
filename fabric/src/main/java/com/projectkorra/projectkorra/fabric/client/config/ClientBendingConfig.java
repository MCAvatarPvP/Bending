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
    private static final Path PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("projectkorra-client.properties");

    private static boolean initialized;
    private static boolean enabled = true;

    private ClientBendingConfig() { }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        if (!Files.isRegularFile(PATH)) return;

        final Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
            properties.load(reader);
            final String value = properties.getProperty(CLIENT_SIDE_BENDING);
            if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                enabled = Boolean.parseBoolean(value);
            }
        } catch (IOException exception) {
            LOGGER.warn("Could not load {}", PATH, exception);
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

    private static void save() {
        final Properties properties = new Properties();
        properties.setProperty(CLIENT_SIDE_BENDING, Boolean.toString(enabled));
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
                properties.store(writer, "ProjectKorra Fabric client settings");
            }
        } catch (IOException exception) {
            LOGGER.warn("Could not save {}", PATH, exception);
        }
    }
}
