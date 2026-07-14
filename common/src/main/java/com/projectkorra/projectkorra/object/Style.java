package com.projectkorra.projectkorra.object;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Style {

    private static List<Style> styles = new ArrayList<>();

    private String name;
    private Config config;

    public Style(String name, Config config) {
        this.name = name;
        this.config = config;

        if (hasStyle(name)) return;

        styles.add(this);
        ConfigManager.styleConfigs.add(config);
    }

    public static boolean hasStyle(String name) {
        for (Style style : styles) {
            if (name.equalsIgnoreCase(style.getName())) return true;
        }
        return false;
    }

    public static Style getStyle(String name) {
        for (Style style : styles) {
            if (name.equalsIgnoreCase(style.getName())) return style;
        }
        return null;
    }

    public static void removeStyle(String name) {
        for (Style style : styles) {
            if (style.getName().equalsIgnoreCase(name)) style.remove();
        }
    }

    public static ArrayList<String> getNames() {
        ArrayList<String> names = new ArrayList<>();
        styles.forEach(style -> names.add(style.getName()));

        return names;
    }

    public static void reloadStyles() {
        ConfigManager.styleConfigs.forEach(Config::reload);
        styles.clear();
        loadStyleConfigs(new File(ProjectKorra.plugin.getDataFolder() + File.separator + "Styles"));
    }

    public static List<Config> loadStyleConfigs(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return null;
        for (File file : files) {
            String name = file.getName();
            if (!name.contains(".yml")) continue;

            name = name.substring(0, name.lastIndexOf("."));
            new Style(name, new Config(new File("Styles" + File.separator + name + ".yml")));
        }
        return null;
    }

    public void remove() {
        styles.remove(this);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Config getConfig() {
        return config;
    }

    public void setConfig(Config config) {
        this.config = config;
    }
}