package com.projectkorra.projectkorra.prediction;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.configuration.PKConfiguration;
import com.projectkorra.projectkorra.util.Cooldown;

import java.util.*;

/**
 * Produces the public, database-free state consumed by the Fabric client.
 */
final class PaperPredictionSnapshot {
    private static final List<String> SECRET_FRAGMENTS = List.of("password", "passphrase", "secret", "token", "credential", "apikey",
            "privatekey", "webhook", "authorization", "bearer");
    private static final Set<String> DATABASE_SEGMENTS = Set.of("storage", "database", "databases", "mysql", "mariadb", "postgres", "postgresql",
            "mongodb", "redis", "sql", "sqlite", "h2", "jdbc", "dsn", "datasource", "connection", "hostname", "host", "port", "username", "user");

    private PaperPredictionSnapshot() {
    }

    static List<PaperPredictionProtocol.ConfigEntry> config() {
        Map<String, PaperPredictionProtocol.ConfigEntry> entries = new LinkedHashMap<>();
        add(entries, "config", ConfigManager.defaultConfig);
        add(entries, "avatarstate", ConfigManager.avatarStateConfig);
        add(entries, "collision", ConfigManager.collisionConfig);
        add(entries, "fire_colors", ConfigManager.fireColorsConfig);
        add(entries, "air_colors", ConfigManager.airColorsConfig);
        add(entries, "water_cosmetics", ConfigManager.waterCosmeticsConfig);
        add(entries, "earth_cosmetics", ConfigManager.earthCosmeticsConfig);
        add(entries, "fall_damage", ConfigManager.fallDamageConfig);
        if (ConfigManager.styleConfigs != null) for (int i = 0; i < ConfigManager.styleConfigs.size(); i++)
            add(entries, "style_" + i, ConfigManager.styleConfigs.get(i));
        PredictionConfigSync.sources().forEach((namespace, source) -> add(entries, namespace, source));
        return entries.values().stream().sorted(Comparator.comparing(PaperPredictionProtocol.ConfigEntry::path)).limit(16_384).toList();
    }

    static List<PaperPredictionProtocol.AbilityProfile> profiles() {
        List<PaperPredictionProtocol.AbilityProfile> result = new ArrayList<>();
        for (CoreAbility ability : CoreAbility.getAbilitiesByName()) {
            if (ability == null || ability.getName() == null || ability.getName().isBlank()) continue;
            String element = ability.getElement() == null ? "Unknown" : ability.getElement().getName();
            result.add(new PaperPredictionProtocol.AbilityProfile(ability.getName(), element,
                    PaperPredictionProtocol.VisualKind.CAST, 0, 128, safeRadius(ability),
                    0, safeCooldown(ability), "minecraft:air", ability.isHarmlessAbility(), ability.isSneakAbility()));
            if (result.size() == 2_048) break;
        }
        result.sort(Comparator.comparing(PaperPredictionProtocol.AbilityProfile::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    static Map<Integer, String> binds(BendingPlayer player) {
        if (player == null) return Map.of();
        Map<Integer, String> result = new LinkedHashMap<>();
        player.getAbilities().forEach((slot, ability) -> {
            if (slot != null && slot >= 1 && slot <= 9 && ability != null) result.put(slot, ability);
        });
        return Map.copyOf(result);
    }

    static Map<String, Long> cooldowns(BendingPlayer player) {
        if (player == null) return Map.of();
        long now = System.currentTimeMillis();
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<String, Cooldown> entry : player.getCooldowns().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue().getCooldown() > now)
                result.put(entry.getKey(), entry.getValue().getCooldown());
        }
        return Map.copyOf(result);
    }

    static List<String> elements(BendingPlayer player) {
        return player == null ? List.of() : player.getElements().stream().map(Element::getName).distinct().toList();
    }

    static List<String> subElements(BendingPlayer player) {
        return player == null ? List.of() : player.getSubElements().stream().map(Element::getName).distinct().toList();
    }

    private static void add(Map<String, PaperPredictionProtocol.ConfigEntry> out, String namespace, Config wrapper) {
        PKConfiguration config = wrapper == null ? null : wrapper.get();
        add(out, namespace, config);
    }

    private static void add(Map<String, PaperPredictionProtocol.ConfigEntry> out, String namespace, PKConfiguration config) {
        if (config == null || !publicNamespace(namespace)) return;
        for (String key : config.getKeys(true)) {
            if (!publicKey(key)) continue;
            PaperPredictionProtocol.ConfigEntry entry = encode(namespace + "." + key, config.get(key));
            if (entry != null) out.put(entry.path(), entry);
        }
    }

    private static PaperPredictionProtocol.ConfigEntry encode(String path, Object value) {
        if (value instanceof Boolean bool)
            return entry(path, PaperPredictionProtocol.ValueType.BOOLEAN, Boolean.toString(bool));
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return entry(path, PaperPredictionProtocol.ValueType.INTEGER, String.valueOf(value));
        if (value instanceof Number number)
            return entry(path, PaperPredictionProtocol.ValueType.DECIMAL, Double.toString(number.doubleValue()));
        if (value instanceof CharSequence text)
            return entry(path, PaperPredictionProtocol.ValueType.STRING, truncate(text.toString(), 8_192));
        if (value instanceof Collection<?> values)
            return new PaperPredictionProtocol.ConfigEntry(path, PaperPredictionProtocol.ValueType.STRING_LIST,
                    values.stream().limit(256).map(String::valueOf).map(valueText -> truncate(valueText, 8_192)).toList());
        return null;
    }

    private static PaperPredictionProtocol.ConfigEntry entry(String path, PaperPredictionProtocol.ValueType type, String value) {
        return new PaperPredictionProtocol.ConfigEntry(path, type, List.of(value));
    }

    private static boolean publicKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String segment : lower.split("[._\\-/]+")) if (DATABASE_SEGMENTS.contains(segment)) return false;
        for (String fragment : SECRET_FRAGMENTS) if (lower.contains(fragment)) return false;
        return true;
    }

    private static boolean publicNamespace(String namespace) {
        if (namespace == null) return false;
        for (String segment : namespace.toLowerCase(Locale.ROOT).split("[._\\-/]+"))
            if (DATABASE_SEGMENTS.contains(segment)) return false;
        return true;
    }

    private static long safeCooldown(CoreAbility ability) {
        try {
            return Math.max(0, ability.getCooldown());
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static double safeRadius(CoreAbility ability) {
        try {
            double radius = ability.getCollisionRadius();
            return Double.isFinite(radius) ? Math.max(.5, Math.min(64, radius)) : .5;
        } catch (RuntimeException ignored) {
            return .5;
        }
    }

    private static String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
