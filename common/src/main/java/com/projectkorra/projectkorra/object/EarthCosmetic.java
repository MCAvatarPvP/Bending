package com.projectkorra.projectkorra.object;

import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.permissions.Permission;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EarthCosmetic {

    private static final Map<String, EarthCosmetic> earthCosmetics = new HashMap<>();
    private static final List<Material> cosmeticMats = new ArrayList<>();

    private final String name;
    private final Material material;

    public EarthCosmetic(String name, Material material) {
        this.name = name;
        this.material = material;

        earthCosmetics.put(name, this);
        if (material != null) cosmeticMats.add(material);
    }

    public static boolean canReplace(EarthCosmetic cosmetic, Material material) {
        return cosmetic != null && cosmetic.getMaterial() != null
                && (EarthAbility.isEarth(material) || EarthAbility.isSand(material));
    }

    public static void reloadCosmetics() {
        earthCosmetics.clear();
        cosmeticMats.clear();
        loadCosmetics();
    }

    public static void loadCosmetics() {
        for (String s : ConfigManager.earthCosmeticsConfig.get().getStringList("EarthCosmetics")) {
            String[] split = s.split(", ");

            if (split.length != 2) continue;

            Material material = Material.getMaterial(split[1].toUpperCase());
            if (material == null) continue;

            Permission perm = Platform.permissions().getPermission("bending.earthcosmetics." + split[0]);
            if (perm == null) {
                perm = new Permission("bending.earthcosmetics." + split[0]);
                perm.addParent(Platform.permissions().getPermission("bending.earthcosmetics"), true);
                Platform.permissions().addPermission(perm);
            }

            new EarthCosmetic(split[0], material);
        }
        new EarthCosmetic("none", null);
    }

    public static boolean hasCosmetic(String name) {
        return earthCosmetics.containsKey(name);
    }

    public static EarthCosmetic getCosmetic(String name) {
        return earthCosmetics.get(name);
    }

    public static Map<String, EarthCosmetic> getCosmetics() {
        return earthCosmetics;
    }

    public static List<String> getCosmeticNames() {
        return new ArrayList<>(earthCosmetics.keySet());
    }

    public static List<Material> getCosmeticMats() {
        return cosmeticMats;
    }

    public String getName() {
        return name;
    }

    public Material getMaterial() {
        return material;
    }
}