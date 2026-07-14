package me.moros.hyperion;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.firebending.FireShield;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.plugin.java.JavaPlugin;
import me.moros.hyperion.abilities.earthbending.EarthShot;
import me.moros.hyperion.configuration.Config;
import me.moros.hyperion.configuration.ConfigManager;
import me.moros.hyperion.listeners.HyperionCommonListener;
import me.moros.hyperion.methods.CoreMethods;
import me.moros.hyperion.util.BendingFallingBlock;
import me.moros.hyperion.util.TempArmorStand;

import java.util.logging.Logger;

public final class Hyperion implements JavaPlugin {
    private static final Hyperion PLUGIN = new Hyperion();
    private static final String AUTHOR = "Moros";
    private static final String VERSION = "1.0";
    private static final Logger LOG = Platform.logger();
    private static final PersistentDataLayer LAYER = new PersistentDataLayer();
    private static final HyperionCommonListener LISTENER = new HyperionCommonListener();
    private static boolean enabled;

    private Hyperion() {
    }

    public static void enable() {
        if (enabled) {
            return;
        }
        enabled = true;
        new ConfigManager();
        Platform.events().registerListener(LISTENER, PLUGIN);
        CoreMethods.loadAbilities();
        Platform.scheduler().runTimer(TempArmorStand::manage, 0, 1);
        Platform.scheduler().runTimer(BendingFallingBlock::manage, 0, 5);
        Platform.scheduler().runLater(Hyperion::initializeCollisions, 1);
    }

    public static void disable() {
        BendingFallingBlock.removeAll();
        TempArmorStand.removeAll();
        Platform.events().unregisterAll(LISTENER);
        enabled = false;
    }

    public static void reload() {
        ConfigManager.config.reloadConfig();
        ConfigManager.modifiersConfig.reloadConfig();
        BendingFallingBlock.removeAll();
        TempArmorStand.removeAll();
        CoreMethods.loadAbilities();
        getLog().info("Hyperion Reloaded.");
    }

    public static void initializeCollisions() {
        final Collision fireShield = new Collision(CoreAbility.getAbility(EarthShot.class), CoreAbility.getAbility(FireShield.class), true, false);
        if (ProjectKorra.getCollisionManager() != null) {
            ProjectKorra.getCollisionManager().addCollision(fireShield);
        }
    }

    public static Hyperion getPlugin() {
        return PLUGIN;
    }

    public static String getAuthor() {
        return AUTHOR;
    }

    public static String getVersion() {
        return VERSION;
    }

    public static Logger getLog() {
        return LOG;
    }

    public static PersistentDataLayer getLayer() {
        return LAYER;
    }

    @Override
    public Config getConfig() {
        return ConfigManager.config.getConfig();
    }
}
