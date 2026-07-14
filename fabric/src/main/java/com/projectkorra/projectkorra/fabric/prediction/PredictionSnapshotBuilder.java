package com.projectkorra.projectkorra.fabric.prediction;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.configuration.PKConfiguration;
import com.projectkorra.projectkorra.prediction.PredictionConfigSync;
import com.projectkorra.projectkorra.util.Cooldown;
import java.util.Set;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Builds the public, prediction-only view of ProjectKorra configuration. */
public final class PredictionSnapshotBuilder {
    private static final Map<String, PredictionPayloads.AbilityProfile> OVERRIDES = new ConcurrentHashMap<>();
    private static final List<String> SECRET_FRAGMENTS = List.of(
            "password", "secret", "token", "credential", "apikey", "privatekey", "webhook"
    );
    private static final Set<String> DATABASE_SEGMENTS = Set.of(
            "storage", "database", "databases", "mysql", "mariadb", "postgres", "postgresql",
            "mongodb", "redis", "jdbc", "datasource", "connection", "hostname", "host",
            "port", "username", "user"
    );

    private PredictionSnapshotBuilder() { }

    /**
     * Addon API. Register during addon startup to replace the conservative
     * inferred hit-validation envelope. Rendering still executes addon code.
     */
    public static void registerProfile(PredictionPayloads.AbilityProfile profile) {
        if (profile != null && profile.name() != null && !profile.name().isBlank()) {
            OVERRIDES.put(profile.name().toLowerCase(Locale.ROOT), sanitize(profile));
        }
    }

    public static void unregisterProfile(String abilityName) {
        if (abilityName != null) OVERRIDES.remove(abilityName.toLowerCase(Locale.ROOT));
    }

    public static List<PredictionPayloads.ConfigEntry> publicConfig() {
        Map<String, PredictionPayloads.ConfigEntry> entries = new LinkedHashMap<>();
        addConfig(entries, "config", ConfigManager.defaultConfig == null ? null : ConfigManager.defaultConfig.get());
        addConfig(entries, "avatarstate", ConfigManager.avatarStateConfig == null ? null : ConfigManager.avatarStateConfig.get());
        addConfig(entries, "collision", ConfigManager.collisionConfig == null ? null : ConfigManager.collisionConfig.get());
        addConfig(entries, "fire_colors", ConfigManager.fireColorsConfig == null ? null : ConfigManager.fireColorsConfig.get());
        addConfig(entries, "air_colors", ConfigManager.airColorsConfig == null ? null : ConfigManager.airColorsConfig.get());
        addConfig(entries, "water_cosmetics", ConfigManager.waterCosmeticsConfig == null ? null : ConfigManager.waterCosmeticsConfig.get());
        addConfig(entries, "earth_cosmetics", ConfigManager.earthCosmeticsConfig == null ? null : ConfigManager.earthCosmeticsConfig.get());
        addConfig(entries, "fall_damage", ConfigManager.fallDamageConfig == null ? null : ConfigManager.fallDamageConfig.get());
        if (ConfigManager.styleConfigs != null) {
            int index = 0;
            for (Config style : ConfigManager.styleConfigs) addConfig(entries, "style_" + index++, style == null ? null : style.get());
        }
        PredictionConfigSync.sources().forEach((namespace, source) ->
                addConfig(entries, namespace, source == null ? null : source.get()));
        return entries.values().stream().sorted(Comparator.comparing(PredictionPayloads.ConfigEntry::path))
                .limit(PredictionPayloads.MAX_CONFIG_ENTRIES).toList();
    }

    public static List<PredictionPayloads.AbilityProfile> profiles(List<PredictionPayloads.ConfigEntry> config) {
        Map<String, String> flat = new LinkedHashMap<>();
        for (PredictionPayloads.ConfigEntry entry : config) {
            if (!entry.values().isEmpty()) flat.put(entry.path(), entry.values().get(0));
        }
        List<PredictionPayloads.AbilityProfile> result = new ArrayList<>();
        for (CoreAbility ability : CoreAbility.getAbilitiesByName()) {
            if (ability == null || ability.getName() == null || ability.getName().isBlank()) continue;
            PredictionPayloads.AbilityProfile override = OVERRIDES.get(ability.getName().toLowerCase(Locale.ROOT));
            if (override != null) {
                result.add(override);
            } else {
                try {
                    result.add(infer(ability, flat));
                } catch (RuntimeException ignored) {
                    result.add(new PredictionPayloads.AbilityProfile(ability.getName(), "Unknown",
                            PredictionPayloads.VisualKind.CAST, 0.0, 8.0, 0.75, 0L, 0L,
                            "minecraft:air", false, false));
                }
            }
        }
        result.sort(Comparator.comparing(PredictionPayloads.AbilityProfile::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result.subList(0, Math.min(result.size(), PredictionPayloads.MAX_PROFILES)));
    }

    public static Map<Integer, String> binds(BendingPlayer player) {
        if (player == null) return Map.of();
        Map<Integer, String> result = new LinkedHashMap<>();
        player.getAbilities().forEach((slot, ability) -> {
            if (slot != null && slot >= 1 && slot <= 9 && ability != null) result.put(slot, ability);
        });
        return Map.copyOf(result);
    }

    public static Map<String, Long> cooldowns(BendingPlayer player) {
        if (player == null) return Map.of();
        long now = System.currentTimeMillis();
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<String, Cooldown> entry : player.getCooldowns().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue().getCooldown() > now) {
                result.put(entry.getKey(), entry.getValue().getCooldown());
            }
        }
        return Map.copyOf(result);
    }

    public static List<String> elements(BendingPlayer player) {
        if (player == null) return List.of();
        return player.getElements().stream().map(Element::getName).distinct().toList();
    }

    public static List<String> subElements(BendingPlayer player) {
        if (player == null) return List.of();
        return player.getSubElements().stream().map(Element::getName).distinct().toList();
    }

    private static void addConfig(Map<String, PredictionPayloads.ConfigEntry> out, String namespace, PKConfiguration config) {
        if (config == null || !isPublicNamespace(namespace)) return;
        for (String key : config.getKeys(true)) {
            if (!isPublicKey(key)) continue;
            Object value = config.get(key);
            PredictionPayloads.ConfigEntry encoded = encode(namespace + "." + key, value);
            if (encoded != null) out.put(encoded.path(), encoded);
        }
    }

    private static boolean isPublicKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.equals("storage") || lower.startsWith("storage.")) return false;
        for (String segment : lower.split("[._\\-/]+")) if (DATABASE_SEGMENTS.contains(segment)) return false;
        for (String fragment : SECRET_FRAGMENTS) if (lower.contains(fragment)) return false;
        return true;
    }

    private static boolean isPublicNamespace(String namespace) {
        if (namespace == null) return false;
        for (String segment : namespace.toLowerCase(Locale.ROOT).split("[._\\-/]+")) {
            if (DATABASE_SEGMENTS.contains(segment)) return false;
        }
        return true;
    }

    private static PredictionPayloads.ConfigEntry encode(String path, Object value) {
        if (value instanceof Boolean bool) return entry(path, PredictionPayloads.ValueType.BOOLEAN, Boolean.toString(bool));
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return entry(path, PredictionPayloads.ValueType.INTEGER, String.valueOf(value));
        }
        if (value instanceof Number number) return entry(path, PredictionPayloads.ValueType.DECIMAL, Double.toString(number.doubleValue()));
        if (value instanceof CharSequence text) return entry(path, PredictionPayloads.ValueType.STRING, truncate(text.toString(), 8_192));
        if (value instanceof Collection<?> collection) {
            List<String> values = collection.stream().limit(256).map(String::valueOf).map(valueText -> truncate(valueText, 8_192)).toList();
            return new PredictionPayloads.ConfigEntry(path, PredictionPayloads.ValueType.STRING_LIST, values);
        }
        return null;
    }

    private static PredictionPayloads.ConfigEntry entry(String path, PredictionPayloads.ValueType type, String value) {
        return new PredictionPayloads.ConfigEntry(path, type, List.of(value));
    }

    private static PredictionPayloads.AbilityProfile infer(CoreAbility ability, Map<String, String> flat) {
        String name = ability.getName();
        String element = ability.getElement() == null ? "Unknown" : ability.getElement().getName();
        double speed = number(flat, name, List.of(".Speed"), 0.0);
        double range = number(flat, name, List.of(".Range", ".SelectRange", ".Radius"), 8.0);
        double radius = number(flat, name, List.of(".HitRadius", ".CollisionRadius", ".Radius"), 0.75);
        long charge = (long) number(flat, name, List.of(".ChargeTime", ".ChargeDuration"), 0.0);
        long cooldown = (long) number(flat, name, List.of(".Cooldown"), Math.max(0L, safeCooldown(ability)));
        String lower = name.toLowerCase(Locale.ROOT);
        boolean temp = isTempAbility(lower, element);
        PredictionPayloads.VisualKind kind;
        if (temp) kind = PredictionPayloads.VisualKind.TEMP_BLOCK;
        else if (speed > 0.0 && range > 0.0) kind = PredictionPayloads.VisualKind.PROJECTILE;
        else if (radius > 1.25 || lower.contains("burst") || lower.contains("shield") || lower.contains("wave")) kind = PredictionPayloads.VisualKind.AREA;
        else if (ability.isHarmlessAbility() && (lower.contains("spout") || lower.contains("jet") || lower.contains("flight") || lower.contains("scooter"))) kind = PredictionPayloads.VisualKind.SELF;
        else kind = PredictionPayloads.VisualKind.CAST;

        return sanitize(new PredictionPayloads.AbilityProfile(name, element, kind, speed, range, radius, charge, cooldown,
                tempMaterial(element), ability.isHarmlessAbility(), ability.isSneakAbility()));
    }

    private static double number(Map<String, String> flat, String ability, List<String> suffixes, double fallback) {
        String marker = "." + ability.toLowerCase(Locale.ROOT) + ".";
        for (String suffix : suffixes) {
            String wanted = suffix.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> entry : flat.entrySet()) {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (!key.contains(marker) || !key.endsWith(wanted)) continue;
                try { return Double.parseDouble(firstNumber(entry.getValue())); } catch (NumberFormatException ignored) { }
            }
        }
        return fallback;
    }

    private static String firstNumber(String value) {
        int hash = value.indexOf('#');
        if (hash >= 0) value = value.substring(0, hash);
        int comma = value.indexOf(',');
        if (comma >= 0) value = value.substring(0, comma);
        return value.trim();
    }

    private static boolean isTempAbility(String name, String element) {
        if (!(element.equalsIgnoreCase("Water") || element.equalsIgnoreCase("Ice") || element.equalsIgnoreCase("Earth")
                || element.equalsIgnoreCase("Lava") || element.equalsIgnoreCase("Plant"))) return false;
        return name.contains("wall") || name.contains("raise") || name.contains("pillar") || name.contains("spout")
                || name.contains("torrent") || name.contains("surge") || name.contains("phase") || name.contains("tunnel")
                || name.contains("grab") || name.contains("armor") || name.contains("octopus") || name.contains("lavaflow");
    }

    private static String tempMaterial(String element) {
        if (element.equalsIgnoreCase("Water")) return "minecraft:water";
        if (element.equalsIgnoreCase("Ice")) return "minecraft:packed_ice";
        if (element.equalsIgnoreCase("Lava")) return "minecraft:lava";
        if (element.equalsIgnoreCase("Plant")) return "minecraft:oak_leaves";
        return "minecraft:stone";
    }

    private static long safeCooldown(CoreAbility ability) {
        try { return ability.getCooldown(); } catch (RuntimeException ignored) { return 0L; }
    }

    private static PredictionPayloads.AbilityProfile sanitize(PredictionPayloads.AbilityProfile profile) {
        return new PredictionPayloads.AbilityProfile(profile.name(), profile.element(), profile.visualKind(),
                clamp(profile.speed(), 0, 200), clamp(profile.range(), 0, 128), clamp(profile.radius(), 0.05, 16),
                Math.max(0, Math.min(profile.chargeMillis(), 120_000)), Math.max(0, Math.min(profile.cooldownMillis(), 3_600_000)),
                profile.tempMaterial() == null ? "minecraft:air" : profile.tempMaterial(), profile.harmless(), profile.sneakAbility());
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
