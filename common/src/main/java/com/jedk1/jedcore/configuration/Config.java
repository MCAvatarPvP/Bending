package com.jedk1.jedcore.configuration;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.platform.Platform;

import java.io.File;

public class Config extends com.projectkorra.projectkorra.configuration.Config {
    private BendingPlayer bPlayer;

    public Config(File file) {
        super(file);
    }

    public Config getConfig() {
        return this;
    }

    public Config getConfig(BendingPlayer bPlayer) {
        this.bPlayer = bPlayer;
        return this;
    }

    public void reloadConfig() {
        reload();
    }

    public void saveConfig() {
        options().copyDefaults(true);
        save();
    }

    public boolean getBooleanSuper(String path) {
        return super.getBoolean(path);
    }

    private com.projectkorra.projectkorra.configuration.Config styleConfig(String path) {
        if (bPlayer == null || bPlayer.getStyle() == null) return null;
        com.projectkorra.projectkorra.configuration.Config config = bPlayer.getStyle().getConfig();
        return config.contains(path) ? config : null;
    }

    @Override
    public Object get(String path) {
        com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config == null ? super.get(path) : config.get(path);
    }

    @Override
    public Object get(String path, Object def) {
        com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config == null ? super.get(path, def) : config.get(path, def);
    }

    @Override
    public String getString(String path) {
        com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config == null ? super.getString(path) : config.getString(path);
    }

    @Override
    public String getString(String path, String def) {
        com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config == null ? super.getString(path, def) : config.getString(path, def);
    }

    @Override
    public boolean getBoolean(String path) {
        com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config == null ? super.getBoolean(path) : config.getBoolean(path);
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config == null ? super.getBoolean(path, def) : config.getBoolean(path, def);
    }

    @Override
    public int getInt(String path) {
        com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config == null ? super.getInt(path) : config.getInt(path);
    }

    @Override
    public int getInt(String path, int def) {
        com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config == null ? super.getInt(path, def) : config.getInt(path, def);
    }

    @Override
    public long getLong(String path) {
        com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config == null ? super.getLong(path) : config.getLong(path);
    }

    @Override
    public long getLong(String path, long def) {
        com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config == null ? super.getLong(path, def) : config.getLong(path, def);
    }

    @Override
    public double getDouble(String path) {
        com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config == null ? super.getDouble(path) : config.getDouble(path);
    }

    @Override
    public double getDouble(String path, double def) {
        com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config == null ? super.getDouble(path, def) : config.getDouble(path, def);
    }

    public File getDataFile(String child) {
        return Platform.dataFolder().resolve(child).toFile();
    }
}
