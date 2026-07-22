package com.projectkorra.projectkorra.fabric.client.prediction.config;

import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads;
import com.projectkorra.projectkorra.prediction.state.PredictionConfigSync;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Decodes and installs the authenticated Paper prediction configuration. */
public final class ClientPredictionConfig {
    private ClientPredictionConfig() { }

    public static void apply(final List<PredictionPayloads.ConfigEntry> entries) {
        final Map<String, Map<String, Object>> namespaces = new HashMap<>();
        for (PredictionPayloads.ConfigEntry entry : entries) {
            final int dot = entry.path().indexOf('.');
            if (dot <= 0 || dot == entry.path().length() - 1) continue;
            namespaces.computeIfAbsent(entry.path().substring(0, dot),
                    ignored -> new LinkedHashMap<>())
                    .put(entry.path().substring(dot + 1), decode(entry));
        }
        apply(ConfigManager.defaultConfig, namespaces.get("config"));
        apply(ConfigManager.avatarStateConfig, namespaces.get("avatarstate"));
        apply(ConfigManager.collisionConfig, namespaces.get("collision"));
        apply(ConfigManager.fireColorsConfig, namespaces.get("fire_colors"));
        apply(ConfigManager.airColorsConfig, namespaces.get("air_colors"));
        apply(ConfigManager.waterCosmeticsConfig, namespaces.get("water_cosmetics"));
        apply(ConfigManager.earthCosmeticsConfig, namespaces.get("earth_cosmetics"));
        apply(ConfigManager.fallDamageConfig, namespaces.get("fall_damage"));
        if (ConfigManager.styleConfigs != null) {
            for (int index = 0; index < ConfigManager.styleConfigs.size(); index++) {
                apply(ConfigManager.styleConfigs.get(index), namespaces.get("style_" + index));
            }
        }
        PredictionConfigSync.sources().forEach((namespace, source) ->
                apply(source, namespaces.get(namespace)));
    }

    public static Set<String> normalizePermissions(final List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) return Set.of();
        final Set<String> normalized = new HashSet<>();
        for (String permission : permissions) {
            if (permission != null && !permission.isBlank()) {
                normalized.add(permission.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(normalized);
    }

    private static void apply(final Config config, final Map<String, Object> values) {
        if (config != null && values != null) config.applyRemoteValues(values);
    }

    private static Object decode(final PredictionPayloads.ConfigEntry entry) {
        if (entry.type() == PredictionPayloads.ValueType.STRING_LIST) {
            return List.copyOf(entry.values());
        }
        final String value = entry.values().isEmpty() ? "" : entry.values().get(0);
        try {
            return switch (entry.type()) {
                case BOOLEAN -> Boolean.parseBoolean(value);
                case INTEGER -> Long.parseLong(value);
                case DECIMAL -> Double.parseDouble(value);
                default -> value;
            };
        } catch (NumberFormatException ignored) {
            return value;
        }
    }
}
