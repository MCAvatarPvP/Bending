package com.projectkorra.projectkorra.object;

import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.configuration.PKConfigurationSection;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.permissions.Permission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A named pair of wing textures used by AirGlider's outer and inner panels.
 */
public final class GliderColor {
    private static final String CONFIG_PATH = "Abilities.Air.AirGlider.Model.Colors";
    private static final String DEFAULT_NAME = "classic";
    private static final Map<String, GliderColor> COLORS = new LinkedHashMap<>();

    private final String name;
    private final String outerTexture;
    private final String innerTexture;

    private GliderColor(final String name, final String outerTexture, final String innerTexture) {
        this.name = name;
        this.outerTexture = outerTexture;
        this.innerTexture = innerTexture;
    }

    public static void reloadColors() {
        COLORS.clear();
        COLORS.put(DEFAULT_NAME, new GliderColor(DEFAULT_NAME,
                ConfigManager.defaultConfig.get().getString(
                        "Abilities.Air.AirGlider.Model.OrangeTexture", ""),
                ConfigManager.defaultConfig.get().getString(
                        "Abilities.Air.AirGlider.Model.YellowTexture", "")));
        final PKConfigurationSection section = ConfigManager.defaultConfig.get()
                .getConfigurationSection(CONFIG_PATH);
        if (section != null) {
            for (final String name : section.getKeys(false)) {
                final String outer = section.getString(name + ".OuterTexture", "");
                final String inner = section.getString(name + ".InnerTexture", "");
                if (name == null || name.isBlank() || outer.isBlank() || inner.isBlank()) continue;
                final String key = normalize(name);
                if (DEFAULT_NAME.equals(key)) continue;
                COLORS.put(key, new GliderColor(key, outer, inner));
                registerPermission(key);
            }
        }
    }

    public static boolean hasColor(final String name) {
        return getColor(name) != null;
    }

    public static GliderColor getColor(final String name) {
        if (name == null) return null;
        final String key = normalize(name);
        return COLORS.get("none".equals(key) || "default".equals(key) ? DEFAULT_NAME : key);
    }

    public static GliderColor getDefault() {
        return COLORS.get(DEFAULT_NAME);
    }

    public static List<String> getColorNames() {
        return new ArrayList<>(COLORS.keySet());
    }

    public static List<GliderColor> getColors() {
        return new ArrayList<>(COLORS.values());
    }

    private static void registerPermission(final String name) {
        if (DEFAULT_NAME.equals(name)) return;
        final String node = "bending.glidercolor." + name;
        if (Platform.permissions().getPermission(node) != null) return;
        final Permission permission = new Permission(node);
        permission.addParent(Platform.permissions().getPermission("bending.glidercolor"), true);
        Platform.permissions().addPermission(permission);
    }

    private static String normalize(final String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    public String getName() {
        return this.name;
    }

    public String getOuterTexture() {
        return this.outerTexture;
    }

    public String getInnerTexture() {
        return this.innerTexture;
    }

    /** The vanilla wool used to craft this color, or null for the classic two-tone glider. */
    public Material getWoolMaterial() {
        return DEFAULT_NAME.equals(this.name) ? null
                : Material.getMaterial(this.name.toUpperCase(Locale.ROOT) + "_WOOL");
    }
}
