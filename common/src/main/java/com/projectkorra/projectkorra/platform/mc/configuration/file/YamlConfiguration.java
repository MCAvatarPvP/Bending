package com.projectkorra.projectkorra.platform.mc.configuration.file;

import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.prediction.rollback.RollbackConfiguration;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;

import java.io.File;
import java.util.*;

public class YamlConfiguration extends Config {
    public YamlConfiguration() {
        super(new File("metrics.yml"));
    }

    private YamlConfiguration(RollbackConfiguration.Settings settings) { super(settings); }
    @Override public YamlConfiguration createRollbackView(RollbackConfiguration.Settings settings) { return new Frozen(settings); }
    private static final class Frozen extends YamlConfiguration implements RollbackStateCell<List<BendingPlayer>> {
        private Frozen(RollbackConfiguration.Settings settings) { super(settings); }
        @Override public List<BendingPlayer> captureRollbackState() { return captureRollbackContext(); }
        @Override public void restoreRollbackState(List<BendingPlayer> context) { restoreRollbackContext(context); }
        @Override public Collection<?> rollbackReferences() { return captureRollbackContext().stream().filter(Objects::nonNull).toList(); }
    }

    public static YamlConfiguration loadConfiguration(File f) {
        return new YamlConfiguration();
    }
}
