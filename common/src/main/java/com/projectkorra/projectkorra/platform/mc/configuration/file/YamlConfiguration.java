package com.projectkorra.projectkorra.platform.mc.configuration.file;

import com.projectkorra.projectkorra.configuration.Config;

import java.io.File;

public class YamlConfiguration extends Config {
    public YamlConfiguration() {
        super(new File("metrics.yml"));
    }

    public static YamlConfiguration loadConfiguration(File f) {
        return new YamlConfiguration();
    }
}
