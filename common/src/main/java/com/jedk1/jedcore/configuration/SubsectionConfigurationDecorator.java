package com.jedk1.jedcore.configuration;

import com.projectkorra.projectkorra.configuration.PKConfiguration;
import com.projectkorra.projectkorra.configuration.PKConfigurationOptions;
import com.projectkorra.projectkorra.configuration.PKConfigurationSection;

import java.util.List;
import java.util.Set;

public class SubsectionConfigurationDecorator implements PKConfiguration {
    private final PKConfiguration parent;
    private final String prefix;

    public SubsectionConfigurationDecorator(PKConfiguration parent, String prefix) {
        this.parent = parent;
        this.prefix = prefix == null || prefix.isBlank() ? "" : prefix + ".";
    }

    private String path(String path) {
        return this.prefix + path;
    }

    @Override
    public void set(String path, Object value) {
        parent.set(path(path), value);
    }

    @Override
    public void addDefault(String path, Object value) {
        parent.addDefault(path(path), value);
    }

    @Override
    public PKConfigurationOptions options() {
        return parent.options();
    }

    @Override
    public boolean contains(String path) {
        return parent.contains(path(path));
    }

    @Override
    public Object get(String path) {
        return parent.get(path(path));
    }

    @Override
    public Object get(String path, Object def) {
        return parent.get(path(path), def);
    }

    @Override
    public String getString(String path) {
        return parent.getString(path(path));
    }

    @Override
    public String getString(String path, String def) {
        return parent.getString(path(path), def);
    }

    @Override
    public boolean getBoolean(String path) {
        return parent.getBoolean(path(path));
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        return parent.getBoolean(path(path), def);
    }

    @Override
    public int getInt(String path) {
        return parent.getInt(path(path));
    }

    @Override
    public int getInt(String path, int def) {
        return parent.getInt(path(path), def);
    }

    @Override
    public long getLong(String path) {
        return parent.getLong(path(path));
    }

    @Override
    public long getLong(String path, long def) {
        return parent.getLong(path(path), def);
    }

    @Override
    public double getDouble(String path) {
        return parent.getDouble(path(path));
    }

    @Override
    public double getDouble(String path, double def) {
        return parent.getDouble(path(path), def);
    }

    @Override
    public List<String> getStringList(String path) {
        return parent.getStringList(path(path));
    }

    @Override
    public PKConfigurationSection getConfigurationSection(String path) {
        return parent.getConfigurationSection(path(path));
    }

    @Override
    public Set<String> getKeys(boolean deep) {
        return parent.getKeys(deep);
    }
}
