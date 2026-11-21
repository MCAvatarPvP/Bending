package com.jedk1.jedcore.configuration;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.prediction.rollback.RollbackConfiguration;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;

import java.io.File;
import java.util.*;

public class Config extends com.projectkorra.projectkorra.configuration.Config {
    private BendingPlayer bPlayer;

    public Config(File file) {
        super(file);
    }

    private Config(RollbackConfiguration.Settings settings) { super(settings); }

    @Override public Config createRollbackView(RollbackConfiguration.Settings settings) { return new Frozen(settings); }
    @Override public List<BendingPlayer> captureRollbackContext() {
        return Collections.unmodifiableList(Arrays.asList(super.captureRollbackContext().getFirst(), bPlayer));
    }
    @Override public void restoreRollbackContext(List<BendingPlayer> context) {
        if (context.size() != 2) throw new IllegalArgumentException("JedCore config reader context");
        super.restoreRollbackContext(context.subList(0, 1)); bPlayer = context.get(1);
    }
    private static final class Frozen extends Config implements RollbackStateCell<List<BendingPlayer>> {
        private Frozen(RollbackConfiguration.Settings settings) { super(settings); }
        @Override public List<BendingPlayer> captureRollbackState() { return captureRollbackContext(); }
        @Override public void restoreRollbackState(List<BendingPlayer> context) { restoreRollbackContext(context); }
        @Override public Collection<?> rollbackReferences() { return captureRollbackContext().stream().filter(Objects::nonNull).toList(); }
    }

    public Config getConfig() {
        return (Config) RollbackConfiguration.resolve(this);
    }

    public Config getConfig(BendingPlayer bPlayer) {
        Config view = (Config) RollbackConfiguration.resolve(this);
        if (view != this) return view.getConfig(bPlayer);
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
        Config view = (Config) RollbackConfiguration.resolve(this);
        if (view != this) return view.styleConfig(path);
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
