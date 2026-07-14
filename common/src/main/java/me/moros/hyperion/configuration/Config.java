package me.moros.hyperion.configuration;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.platform.Platform;

import java.io.File;

public class Config extends com.projectkorra.projectkorra.configuration.Config {
    private BendingPlayer bPlayer;

    public Config(final String name) {
        super(Platform.dataFolder().resolve("hyperion").resolve(name).toFile());
    }

    public Config getConfig() {
        return this;
    }

    public Config getConfig(final BendingPlayer bPlayer) {
        this.bPlayer = bPlayer;
        return this;
    }

    public void reloadConfig() {
        reload();
    }

    public void saveConfig() {
        save();
    }

    private com.projectkorra.projectkorra.configuration.Config styleConfig(final String path) {
        if (this.bPlayer == null) {
            return null;
        }
        try {
            if (this.bPlayer.getStyle() == null) {
                return null;
            }
            final com.projectkorra.projectkorra.configuration.Config config = this.bPlayer.getStyle().getConfig();
            return config.contains(path) ? config : null;
        } finally {
            this.bPlayer = null;
        }
    }

    @Override
    public Object get(final String path) {
        final com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config != null ? config.get(path) : super.get(path);
    }

    @Override
    public Object get(final String path, final Object def) {
        final com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config != null ? config.get(path, def) : super.get(path, def);
    }

    public Object getSuper(final String path) {
        return super.get(path);
    }

    public Object getSuper(final String path, final Object def) {
        return super.get(path, def);
    }

    @Override
    public String getString(final String path) {
        final com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config != null ? config.getString(path) : super.getString(path);
    }

    @Override
    public String getString(final String path, final String def) {
        final com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config != null ? config.getString(path, def) : super.getString(path, def);
    }

    public String getStringSuper(final String path) {
        return super.getString(path);
    }

    public String getStringSuper(final String path, final String def) {
        return super.getString(path, def);
    }

    @Override
    public boolean getBoolean(final String path) {
        final com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config != null ? config.getBoolean(path) : super.getBoolean(path);
    }

    @Override
    public boolean getBoolean(final String path, final boolean def) {
        final com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config != null ? config.getBoolean(path, def) : super.getBoolean(path, def);
    }

    public boolean getBooleanSuper(final String path) {
        return super.getBoolean(path);
    }

    public boolean getBooleanSuper(final String path, final boolean def) {
        return super.getBoolean(path, def);
    }

    @Override
    public int getInt(final String path) {
        final com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config != null ? config.getInt(path) : super.getInt(path);
    }

    @Override
    public int getInt(final String path, final int def) {
        final com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config != null ? config.getInt(path, def) : super.getInt(path, def);
    }

    public int getIntSuper(final String path) {
        return super.getInt(path);
    }

    public int getIntSuper(final String path, final int def) {
        return super.getInt(path, def);
    }

    @Override
    public long getLong(final String path) {
        final com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config != null ? config.getLong(path) : super.getLong(path);
    }

    @Override
    public long getLong(final String path, final long def) {
        final com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config != null ? config.getLong(path, def) : super.getLong(path, def);
    }

    public long getLongSuper(final String path) {
        return super.getLong(path);
    }

    public long getLongSuper(final String path, final long def) {
        return super.getLong(path, def);
    }

    @Override
    public double getDouble(final String path) {
        final com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config != null ? config.getDouble(path) : super.getDouble(path);
    }

    @Override
    public double getDouble(final String path, final double def) {
        final com.projectkorra.projectkorra.configuration.Config config = styleConfig(path);
        return config != null ? config.getDouble(path, def) : super.getDouble(path, def);
    }

    public double getDoubleSuper(final String path) {
        return super.getDouble(path);
    }

    public double getDoubleSuper(final String path, final double def) {
        return super.getDouble(path, def);
    }

    public File file() {
        return Platform.dataFolder().resolve("hyperion").toFile();
    }
}
