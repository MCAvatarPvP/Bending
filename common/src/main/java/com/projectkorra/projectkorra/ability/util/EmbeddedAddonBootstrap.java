package com.projectkorra.projectkorra.ability.util;

import com.jedk1.jedcore.JedCore;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import me.literka.ChiRework;
import me.moros.hyperion.Hyperion;
import me.simplicitee.project.addons.ProjectAddons;

/**
 * Starts the addon sources bundled into this fork on both logical sides.
 */
public final class EmbeddedAddonBootstrap {
    private static boolean enabled;
    private static JedCore jedCore;
    private static ProjectAddons projectAddons;
    private static ChiRework chiRework;

    private EmbeddedAddonBootstrap() {
    }

    public static synchronized void enable() {
        if (!enabled) {
            enabled = true;
            jedCore = new JedCore();
            projectAddons = new ProjectAddons();
            chiRework = new ChiRework();
            tryEnable("JedCore", jedCore::onEnable);
            tryEnable("ProjectAddons", projectAddons::onEnable);
            tryEnable("ChiRework", chiRework::onEnable);
            tryEnable("Hyperion", Hyperion::enable);
        }
        // CoreAbility.registerAbilities clears the registry on reload and on a
        // new client connection. Re-register embedded packages without
        // rebuilding their config/scheduler state.
        // Hyperion and JedCore both bundle an ability named Combustion. Match
        // the embedded add-on activation table by loading Hyperion last, so a
        // Combustion bind resolves to the implementation its handlers create.
        tryEnable("JedCore abilities", () -> CoreAbility.registerPluginAbilities(jedCore, "com.jedk1.jedcore.ability"));
        tryEnable("Hyperion abilities", () -> CoreAbility.registerPluginAbilities(Hyperion.getPlugin(), "me.moros.hyperion.abilities"));
        tryEnable("ProjectAddons abilities", () -> CoreAbility.registerPluginAbilities(projectAddons, "me.simplicitee.project.addons.ability"));
        tryEnable("ChiRework abilities", () -> CoreAbility.registerPluginAbilities(chiRework, "me.literka.abilities"));
    }

    public static synchronized void disable() {
        if (!enabled) return;
        tryEnable("JedCore shutdown", jedCore::onDisable);
        tryEnable("ProjectAddons shutdown", projectAddons::onDisable);
        tryEnable("ChiRework shutdown", chiRework::onDisable);
        tryEnable("Hyperion shutdown", Hyperion::disable);
        jedCore = null;
        projectAddons = null;
        chiRework = null;
        enabled = false;
    }

    private static void tryEnable(String name, Runnable startup) {
        try {
            startup.run();
        } catch (Throwable failure) {
            ProjectKorra.log.warning("Embedded addon " + name + " could not start: " + failure.getMessage());
        }
    }
}
