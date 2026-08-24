package com.projectkorra.projectkorra;

import com.projectkorra.projectkorra.command.BendingTabComplete;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.hooks.BetonQuestHook;
import com.projectkorra.projectkorra.hooks.ExternalActionBarHook;
import com.projectkorra.projectkorra.hooks.WorldGuardFlag;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.bukkit.BukkitProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.command.Command;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.prediction.server.PacketEventsEntityInterpolationHook;
import com.projectkorra.projectkorra.prediction.server.PaperPredictionServer;
import com.projectkorra.projectkorra.prediction.server.ServerEntityInterpolation;
import com.projectkorra.projectkorra.region.BukkitRegionProtectionBootstrap;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bukkit/Paper entrypoint. Common runtime code lives in the common module.
 */
public final class BukkitProjectKorraPlugin extends JavaPlugin {
    private PaperPredictionServer prediction;
    private ServerEntityInterpolation entityInterpolation;
    private PacketEventsEntityInterpolationHook entityInterpolationHook;
    private ExternalActionBarHook externalActionBarHook;

    private static CommandSender wrapSender(final org.bukkit.command.CommandSender sender) {
        if (sender instanceof Player player) {
            return BukkitMC.player(player);
        }
        return new BukkitConsoleSender(sender);
    }

    @Override
    public void onLoad() {
        // WorldGuard locks its flag registry before plugins are enabled. Registering
        // here also lets WorldGuard associate persisted values with ProjectKorra.
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            WorldGuardFlag.registerBendingWorldGuardFlag(this);
        }
    }

    @Override
    public void onEnable() {
        Platform.install(new BukkitProjectKorraPlatform(this));
        registerServerEntityInterpolation();
        ProjectKorra.initCommon();
        BukkitRegionProtectionBootstrap.registerBuiltIns();
        GeneralMethods.reloadPlugin(new BukkitConsoleSender(getServer().getConsoleSender()));
        registerCommands();
        Platform.events().registerListener(new PKListener(this));
        registerBetonQuestHook();
        registerExternalActionBarHook();
        try {
            this.prediction = PaperPredictionServer.start(this);
            // Lifecycle metadata precedes every physical TempBlock write. A
            // compatible caster renders its client-owned lifecycle and hides
            // every Paper layer owned by that accepted action; remote and
            // genuinely server-only layers retain normal vanilla authority.
            getLogger().info("Using action-owned Fabric TempBlock lifecycles with ordered Paper metadata.");
        } catch (Throwable failure) {
            if (this.prediction != null) this.prediction.stop();
            this.prediction = null;
            getLogger().warning("Could not enable exact client prediction; using server authority: "
                    + failure.getMessage());
        }
    }

    private void registerBetonQuestHook() {
        Plugin betonQuest = getServer().getPluginManager().getPlugin("BetonQuest");
        if (betonQuest == null || !betonQuest.isEnabled()) return;

        try {
            BetonQuestHook.register(this);
        } catch (LinkageError | RuntimeException failure) {
            // BetonQuest is optional and its API is not stable across major
            // versions. An incompatible installation must not disable PK.
            getLogger().warning("Could not enable the BetonQuest integration; continuing without it. "
                    + "Install a BetonQuest version compatible with its 3.1 API. Cause: " + failure);
        }
    }

    private void registerExternalActionBarHook() {
        Plugin packetEvents = getServer().getPluginManager().getPlugin("packetevents");
        if (packetEvents == null) {
            packetEvents = getServer().getPluginManager().getPlugin("PacketEvents");
        }
        if (packetEvents == null || !packetEvents.isEnabled()) return;

        try {
            this.externalActionBarHook = ExternalActionBarHook.register(ProjectKorra.plugin);
        } catch (LinkageError | RuntimeException failure) {
            this.externalActionBarHook = null;
            getLogger().warning("Could not enable external action-bar merging; continuing without it. Cause: "
                    + failure);
        }
    }

    private void registerServerEntityInterpolation() {
        Plugin packetEvents = getServer().getPluginManager().getPlugin("packetevents");
        if (packetEvents == null) {
            packetEvents = getServer().getPluginManager().getPlugin("PacketEvents");
        }
        if (packetEvents == null || !packetEvents.isEnabled()) return;

        try {
            this.entityInterpolation = ServerEntityInterpolation.start(this);
            this.entityInterpolationHook = PacketEventsEntityInterpolationHook.register(
                    this, this.entityInterpolation);
        } catch (LinkageError | RuntimeException failure) {
            if (this.entityInterpolationHook != null) this.entityInterpolationHook.stop();
            if (this.entityInterpolation != null) this.entityInterpolation.stop();
            this.entityInterpolationHook = null;
            this.entityInterpolation = null;
            getLogger().warning("Could not enable packet-driven entity interpolation; "
                    + "using Bukkit collision positions. Cause: " + failure);
        }
    }

    @Override
    public void onDisable() {
        // Keep lifecycle publication and coordinate filtering alive while
        // abilities restore their server state. Exact clients can then finish
        // their own ordered TempBlock lifecycles during a reload.
        GeneralMethods.stopBending();
        if (this.externalActionBarHook != null) {
            this.externalActionBarHook.stop();
            this.externalActionBarHook = null;
        }
        if (this.prediction != null) {
            this.prediction.stop();
            this.prediction = null;
        }
        if (this.entityInterpolationHook != null) {
            this.entityInterpolationHook.stop();
            this.entityInterpolationHook = null;
        }
        if (this.entityInterpolation != null) {
            this.entityInterpolation.stop();
            this.entityInterpolation = null;
        }
        Platform.scheduler().cancelAll();
    }

    private void registerCommands() {
        PluginCommand command = getCommand("projectkorra");
        if (command == null) {
            getLogger().warning("Unable to register /projectkorra command because it is missing from plugin.yml");
            return;
        }
        command.setExecutor((sender, ignored, label, args) ->
                Commands.dispatch(wrapSender(sender), label, args));
        command.setTabCompleter((sender, ignored, alias, args) ->
                new BendingTabComplete()
                        .onTabComplete(wrapSender(sender), new Command(), alias, args));
    }

    private record BukkitConsoleSender(org.bukkit.command.CommandSender value) implements CommandSender {
        @Override
        public String getName() {
            return value.getName();
        }

        @Override
        public void sendMessage(String message) {
            value.sendMessage(message);
        }

        @Override
        public boolean hasPermission(String permission) {
            return value.hasPermission(permission);
        }
    }
}
