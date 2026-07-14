package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.platform.Platform;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of public configuration files that participate in prediction.
 */
public final class PredictionConfigSync {
    private static final Map<String, Config> SOURCES = new ConcurrentHashMap<>();

    private PredictionConfigSync() {
    }

    public static void register(String namespace, Config config) {
        if (namespace != null && !namespace.isBlank() && config != null) SOURCES.put(normalize(namespace), config);
    }

    public static void registerFile(Path file, Config config) {
        Path root = Platform.dataFolder().toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        String relative = absolute.startsWith(root) ? root.relativize(absolute).toString() : absolute.getFileName().toString();
        register("file_" + relative, config);
    }

    public static Map<String, Config> sources() {
        return Map.copyOf(new LinkedHashMap<>(SOURCES));
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
    }
}
