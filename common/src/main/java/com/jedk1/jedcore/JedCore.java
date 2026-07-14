package com.jedk1.jedcore;

import com.google.common.reflect.ClassPath;
import com.jedk1.jedcore.command.Commands;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.scoreboard.BendingBoard;
import com.jedk1.jedcore.util.ChiRestrictor;
import com.jedk1.jedcore.util.CollisionInitializer;
import com.jedk1.jedcore.util.FireTick;
import com.jedk1.jedcore.util.RegenTempBlock;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.PKConfiguration;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.plugin.java.JavaPlugin;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;

import java.io.IOException;
import java.util.logging.Logger;

public class JedCore implements JavaPlugin {

    public static JedCore plugin;
    public static Logger log;
    public static String dev;
    public static String version;
    public static boolean logDebug;

    public static void logDebug(String message) {
        if (logDebug) {
            plugin.getLogger().info(message);
        }
    }

    @Override
    public void onEnable() {
        plugin = this;
        JedCore.log = this.getLogger();
        new JedCoreConfig(this);

        logDebug = JedCoreConfig.getConfig((World) null).getBoolean("Properties.LogDebug");

        dev = this.getDescription().getAuthors().toString().replace("[", "").replace("]", "");
        version = this.getDescription().getVersion();

        JCMethods.registerDisabledWorlds();
        CoreAbility.registerPluginAbilities(plugin, "com.jedk1.jedcore.ability");
        getServer().getPluginManager().registerEvents(new ChiRestrictor(), this);
        getServer().getScheduler().scheduleSyncRepeatingTask(this, new JCManager(this), 0, 1);

        BendingBoard.updateOnline();
        new Commands();

        FireTick.loadMethod();

        new BukkitRunnable() {
            @Override
            public void run() {
                JCMethods.registerCombos();
                BendingBoard.loadOtherCooldowns();
                initializeCollisions();
            }
        }.runTaskLater(this, 1);
    }

    public void initializeCollisions() {
        boolean enabled = this.getConfig().getBoolean("Properties.AbilityCollisions.Enabled");

        if (!enabled) {
            getLogger().info("Collisions disabled.");
            return;
        }

        try {
            ClassPath cp = ClassPath.from(this.getClassLoader());

            for (ClassPath.ClassInfo info : cp.getTopLevelClassesRecursive("com.jedk1.jedcore.ability")) {
                try {
                    @SuppressWarnings("unchecked")
                    Class<? extends CoreAbility> abilityClass = (Class<? extends CoreAbility>) Class.forName(info.getName());

                    if (abilityClass == null) continue;

                    CollisionInitializer initializer = new CollisionInitializer<>(abilityClass);
                    initializer.initialize();
                } catch (Exception e) {

                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void onDisable() {
        RegenTempBlock.revertAll();
    }

    @Override
    public PKConfiguration getConfig() {
        return JedCoreConfig.config;
    }

    public void saveConfig() {
        JedCoreConfig.config.saveConfig();
    }

    public void reloadConfig() {
        JedCoreConfig.config.reloadConfig();
    }
}
