package com.projectkorra.projectkorra.object;

import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.permissions.Permission;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WaterCosmetic {

    private static final Map<String, WaterCosmetic> waterCosmetics = new HashMap<>();
    private static final List<Material> cosmeticMats = new ArrayList<>();

    private final String name;
    private final Material material;

    public WaterCosmetic(final String name, final Material material) {
        this.name = name;
        this.material = material;

        waterCosmetics.put(name, this);
        if (material != null) {
            cosmeticMats.add(material);
        }
    }

    public static void reloadCosmetics() {
        waterCosmetics.clear();
        cosmeticMats.clear();
        loadCosmetics();
    }

    public static void loadCosmetics() {
        for (final String s : ConfigManager.waterCosmeticsConfig.get().getStringList("WaterCosmetics")) {
            final String[] split = s.split(", ");
            if (split.length != 2) {
                continue;
            }

            final Material material = Material.getMaterial(split[1].toUpperCase());
            if (material == null) {
                continue;
            }

            Permission perm = Platform.permissions().getPermission("bending.watercosmetics." + split[0]);
            if (perm == null) {
                perm = new Permission("bending.watercosmetics." + split[0]);
                perm.addParent(Platform.permissions().getPermission("bending.watercosmetics"), true);
                Platform.permissions().addPermission(perm);
            }

            new WaterCosmetic(split[0], material);
        }

        new WaterCosmetic("none", null);
    }

    public static boolean hasCosmetic(final String name) {
        return waterCosmetics.containsKey(name);
    }

    public static WaterCosmetic getCosmetic(final String name) {
        return waterCosmetics.get(name);
    }

    public static List<String> getCosmeticNames() {
        return new ArrayList<>(waterCosmetics.keySet());
    }

    public static List<Material> getCosmeticMats() {
        return cosmeticMats;
    }

    public String getName() {
        return this.name;
    }

    public Material getMaterial() {
        return this.material;
    }
}
