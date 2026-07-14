package com.projectkorra.projectkorra.object;

import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.permissions.Permission;

import java.util.ArrayList;
import java.util.List;

public class CosmeticColor {

    private static List<CosmeticColor> fireCosmeticColors = new ArrayList<>();
    private static List<CosmeticColor> airCosmeticColors = new ArrayList<>();

    private final String name;
    private final Particle.DustOptions color;
    private final Particle particle;

    public CosmeticColor(String name, Particle.DustOptions color) {
        this(name, color, null);
    }

    public CosmeticColor(String name, Particle particle) {
        this(name, null, particle);
    }

    private CosmeticColor(String name, Particle.DustOptions color, Particle particle) {
        this.name = name;
        this.color = color;
        this.particle = particle;
    }

    public static boolean hasFireColor(String name) {
        for (CosmeticColor color : fireCosmeticColors) {
            if (name.equalsIgnoreCase(color.getName())) return true;
        }
        return false;
    }

    public static boolean hasAirColor(String name) {
        for (CosmeticColor color : airCosmeticColors) {
            if (name.equalsIgnoreCase(color.getName())) return true;
        }
        return false;
    }

    public static CosmeticColor getFireColor(String name) {
        for (CosmeticColor color : fireCosmeticColors) {
            if (name.equalsIgnoreCase(color.getName())) return color;
        }
        return null;
    }

    public static CosmeticColor getAirColor(String name) {
        for (CosmeticColor color : airCosmeticColors) {
            if (name.equalsIgnoreCase(color.getName())) return color;
        }
        return null;
    }

    public static void removeFireColor(String name) {
        for (CosmeticColor color : fireCosmeticColors) {
            if (color.getName().equalsIgnoreCase(name)) color.remove();
        }
    }

    public static void removeAirColor(String name) {
        for (CosmeticColor color : airCosmeticColors) {
            if (color.getName().equalsIgnoreCase(name)) color.remove();
        }
    }

    public static ArrayList<String> getFireNames() {
        ArrayList<String> names = new ArrayList<>();
        fireCosmeticColors.forEach(color -> names.add(color.getName()));

        return names;
    }

    public static ArrayList<String> getAirNames() {
        ArrayList<String> names = new ArrayList<>();
        airCosmeticColors.forEach(color -> names.add(color.getName()));

        return names;
    }

    public static List<CosmeticColor> getFireColors() {
        return fireCosmeticColors;
    }

    public static List<CosmeticColor> getAirColors() {
        return airCosmeticColors;
    }

    public static void reloadColors() {
        fireCosmeticColors.clear();
        airCosmeticColors.clear();
        loadColors();
    }

    public static void loadColors() {
        loadFireColors();
        loadAirColors();
    }

    private static void loadFireColors() {
        for (String s : ConfigManager.fireColorsConfig.get().getStringList("FireColors")) {
            String[] arg = s.split(", ");

            String name = arg[0];
            if (arg.length == 3 && arg[2].matches("-?\\d+(\\.\\d+)?")) {
                String hex = arg[1].replace("#", "");
                float size = Float.parseFloat(arg[2]);

                if (hex.length() != 6) continue;

                java.awt.Color clr = java.awt.Color.decode("#" + hex);
                new CosmeticColor(name, new Particle.DustOptions(Color.fromRGB(clr.getRed(), clr.getGreen(), clr.getBlue()), size)).addFireColor();
                registerFirePermission(name);
                continue;
            }

            if (arg.length == 3 && arg[1].equalsIgnoreCase("particle")) {
                try {
                    new CosmeticColor(name, Particle.valueOf(arg[2].toUpperCase())).addFireColor();
                    registerFirePermission(name);
                } catch (IllegalArgumentException ignored) {
                    // Ignore invalid particle names and keep loading the rest.
                }
            }
        }

        if (!hasFireColor("greenfire")) {
            new CosmeticColor("greenfire", Particle.COPPER_FIRE_FLAME).addFireColor();
            registerFirePermission("greenfire");
        }

        new CosmeticColor("none", new Particle.DustOptions(Color.fromRGB(0, 0, 0), 0)).addFireColor();
    }

    private static void loadAirColors() {
        for (String s : ConfigManager.airColorsConfig.get().getStringList("AirColors")) {
            String[] arg = s.split(", ");

            if (arg.length != 2) return;

            String name = arg[0];
            String hex = arg[1].replace("#", "");

            if (hex.length() != 6) return;

            java.awt.Color clr = java.awt.Color.decode("#" + hex);
            new CosmeticColor(name, new Particle.DustOptions(Color.fromRGB(clr.getRed(), clr.getGreen(), clr.getBlue()), 0)).addAirColor();

            Permission perm = Platform.permissions().getPermission("bending.aircolor." + name);
            if (perm == null) {
                perm = new Permission("bending.aircolor." + name);
                perm.addParent(Platform.permissions().getPermission("bending.aircolor"), true);
                Platform.permissions().addPermission(perm);
            }
        }
        if (!hasAirColor("dust")) {
            new CosmeticColor("dust", new Particle.DustOptions(Color.fromRGB(180, 190, 210), 1)).addAirColor();
        }
        Permission dustPerm = Platform.permissions().getPermission("bending.aircolor.dust");
        if (dustPerm == null) {
            dustPerm = new Permission("bending.aircolor.dust");
            dustPerm.addParent(Platform.permissions().getPermission("bending.aircolor"), true);
            Platform.permissions().addPermission(dustPerm);
        }
        new CosmeticColor("none", new Particle.DustOptions(Color.fromRGB(0, 0, 0), 0)).addAirColor();
    }

    private static void registerFirePermission(String name) {
        Permission perm = Platform.permissions().getPermission("bending.firecolor." + name);
        if (perm == null) {
            perm = new Permission("bending.firecolor." + name);
            perm.addParent(Platform.permissions().getPermission("bending.firecolor"), true);
            Platform.permissions().addPermission(perm);
        }
    }

    public void addFireColor() {
        fireCosmeticColors.add(this);
    }

    public void addAirColor() {
        airCosmeticColors.add(this);
    }

    public void remove() {
        fireCosmeticColors.remove(this);
    }

    public String getName() {
        return name;
    }

    public Particle.DustOptions getColor() {
        return color;
    }

    public Particle getParticle() {
        return particle;
    }

    public boolean usesNativeParticle() {
        return particle != null;
    }
}
