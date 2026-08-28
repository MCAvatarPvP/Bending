package me.macieq;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Logger;

/** Cross-platform bootstrap for the bundled Molten addon. */
public final class FloorIsLava implements JavaPlugin {
    public static FloorIsLava plugin;
    public static Logger log;

    private Config config;
    private MainListener mainListener;
    private EntityLavaDmgListener lavaDamageListener;

    @Override
    public void onEnable() {
        plugin = this;
        log = Platform.logger();
        config = new Config(new File("Molten", "config.yml"));
        MainConfig.load();
        CoreAbility.registerPluginAbilities(this, "me.macieq.abilities");
        mainListener = new MainListener();
        lavaDamageListener = new EntityLavaDmgListener();
        Platform.events().registerListener(mainListener, this);
        Platform.events().registerListener(lavaDamageListener, this);
    }

    @Override
    public void onDisable() {
        if (mainListener != null) Platform.events().unregisterAll(mainListener);
        if (lavaDamageListener != null) Platform.events().unregisterAll(lavaDamageListener);
        mainListener = null;
        lavaDamageListener = null;
    }

    public static void reload() {
        if (plugin == null || plugin.config == null) return;
        plugin.config.reload();
        MainConfig.load();
    }

    @Override
    public Config getConfig() {
        return config;
    }
}
